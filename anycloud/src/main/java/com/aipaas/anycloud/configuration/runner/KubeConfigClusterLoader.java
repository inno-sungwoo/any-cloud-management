package com.aipaas.anycloud.configuration.runner;

import com.aipaas.anycloud.configuration.bean.KubeconfigProvider;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import com.aipaas.anycloud.service.ClusterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 백엔드 기동 시 외부 kubeconfig 파일을 읽어 모든 컨텍스트를
 * `cluster` 테이블에 자동 upsert 하는 startup loader.
 *
 * - cluster.id == kubeconfig context.name (KubernetesClientConfig 라우팅 규약)
 * - 인증 자격(server CA / client cert / client key / token)은 컨텍스트의 user 블록에서 추출
 * - 기존 행이 있으면 모니터링 관련 필드(monit_*)는 보존
 * - kubernetes.kubeconfig.path / KUBECONFIG 미설정 시 no-op
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KubeConfigClusterLoader {

    private static final String DEFAULT_CLUSTER_TYPE = "k8s";
    private static final String DEFAULT_CLUSTER_PROVIDER = "on-premise";

    private final KubeconfigProvider kubeconfigProvider;
    private final ClusterRepository clusterRepository;
    private final ClusterService clusterService;

    /**
     * 새 cluster 행을 INSERT 할 때만 사용하는 monit_server_url 기본값.
     * 환경별로 application.properties / 환경변수(MONIT_DEFAULT_URL)에서 주입.
     * 이미 존재하는 행의 monit_server_url 은 절대 덮어쓰지 않는다.
     */
    @Value("${anycloud.monit.default-url:}")
    private String defaultMonitUrl;

    @EventListener(ApplicationReadyEvent.class)
    public void loadClustersFromKubeconfig() {
        String path = kubeconfigProvider.resolvePath();
        if (path == null) {
            log.info("[KubeConfigClusterLoader] kubeconfig 미설정 — cluster 자동 등록 스킵");
            return;
        }

        try {
            String content = Files.readString(Path.of(path));
            int synced = upsertAll(content);
            log.info("[KubeConfigClusterLoader] kubeconfig 컨텍스트 {}개 cluster 테이블에 동기화 완료", synced);

            // 등록된 각 cluster 의 version/status 를 즉시 채워둔다.
            // (helm 배포 등에서 표시용 메타데이터로 사용됨. 실패해도 부팅은 막지 않는다.)
            for (ClusterEntity c : clusterRepository.findAll()) {
                try {
                    clusterService.refreshClusterStatus(c.getId());
                } catch (Exception e) {
                    log.warn("[KubeConfigClusterLoader] cluster '{}' version refresh 실패: {}",
                        c.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[KubeConfigClusterLoader] kubeconfig 로드 실패: {}", e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    int upsertAll(String kubeconfigContent) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(new StringReader(kubeconfigContent));
        if (root == null) {
            log.warn("[KubeConfigClusterLoader] kubeconfig 내용이 비어있음");
            return 0;
        }

        List<Map<String, Object>> contexts = (List<Map<String, Object>>) root.get("contexts");
        List<Map<String, Object>> clusters = (List<Map<String, Object>>) root.get("clusters");
        List<Map<String, Object>> users    = (List<Map<String, Object>>) root.get("users");

        if (contexts == null || contexts.isEmpty()) {
            log.warn("[KubeConfigClusterLoader] kubeconfig에 contexts가 없음");
            return 0;
        }

        int count = 0;
        for (Map<String, Object> ctx : contexts) {
            String ctxName = (String) ctx.get("name");
            Map<String, Object> ctxBody = (Map<String, Object>) ctx.get("context");
            if (ctxName == null || ctxBody == null) continue;

            String clusterRef = (String) ctxBody.get("cluster");
            String userRef    = (String) ctxBody.get("user");

            Map<String, Object> clusterBody = findNamedBody(clusters, clusterRef, "cluster");
            Map<String, Object> userBody    = findNamedBody(users,    userRef,    "user");

            if (clusterBody == null) {
                log.warn("[KubeConfigClusterLoader] 컨텍스트 '{}' — cluster '{}' 정의를 찾을 수 없음, 스킵",
                    ctxName, clusterRef);
                continue;
            }

            String apiServer = trimToLength((String) clusterBody.get("server"), 100);
            if (apiServer == null || apiServer.isBlank()) {
                log.warn("[KubeConfigClusterLoader] 컨텍스트 '{}' — server URL 없음, 스킵", ctxName);
                continue;
            }
            String apiServerIp = trimToLength(extractHost(apiServer), 45);
            String serverCa    = nullToEmpty((String) clusterBody.get("certificate-authority-data"));

            String clientCert  = userBody == null ? null : (String) userBody.get("client-certificate-data");
            String clientKey   = userBody == null ? null : (String) userBody.get("client-key-data");
            String token       = userBody == null ? null : (String) userBody.get("token");
            String authType    = (token != null && !token.isBlank()) ? "token" : "cert";

            Optional<ClusterEntity> existing = clusterRepository.findById(ctxName);
            ClusterEntity entity = existing.orElseGet(() -> {
                // INSERT 분기: 환경별 monit_server_url 기본값을 여기서만 채운다.
                ClusterEntity.ClusterEntityBuilder b = ClusterEntity.builder()
                    .id(ctxName)
                    .status("ACTIVE")
                    .clusterType(DEFAULT_CLUSTER_TYPE)
                    .clusterProvider(DEFAULT_CLUSTER_PROVIDER)
                    .serverCa("");
                if (defaultMonitUrl != null && !defaultMonitUrl.isBlank()) {
                    b.monitServerUrl(defaultMonitUrl);
                }
                return b.build();
            });

            // kubeconfig가 권위(authoritative)인 필드만 갱신, monit_* 필드는 보존
            entity.setApiServerUrl(apiServer);
            entity.setApiServerIp(apiServerIp);
            entity.setServerCa(serverCa);
            entity.setClientCa(clientCert);
            entity.setClientKey(clientKey);
            entity.setClientToken(token);
            entity.setAuthType(authType);
            if (entity.getDescription() == null) {
                entity.setDescription("kubeconfig context: " + ctxName);
            }
            if (entity.getStatus() == null) entity.setStatus("ACTIVE");
            if (entity.getClusterType() == null || entity.getClusterType().isBlank()) {
                entity.setClusterType(DEFAULT_CLUSTER_TYPE);
            }
            if (entity.getClusterProvider() == null || entity.getClusterProvider().isBlank()) {
                entity.setClusterProvider(DEFAULT_CLUSTER_PROVIDER);
            }

            clusterRepository.save(entity);
            log.info("[KubeConfigClusterLoader] 동기화: {} → {}", ctxName, apiServer);
            count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findNamedBody(List<Map<String, Object>> list, String name, String innerKey) {
        if (list == null || name == null) return null;
        for (Map<String, Object> item : list) {
            if (name.equals(item.get("name"))) {
                return (Map<String, Object>) item.get(innerKey);
            }
        }
        return null;
    }

    private String extractHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private String trimToLength(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
