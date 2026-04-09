package com.aipaas.anycloud.configuration.bean;

import com.aipaas.anycloud.model.entity.ClusterEntity;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 외부 kubeconfig 파일의 정적 자격증명만으로 KubernetesClient를 생성합니다.
 *
 * - DB 기반 cert/token 인증, exec 플러그인 인증은 모두 제거되었습니다.
 * - cluster.id를 kubeconfig 컨텍스트 이름으로 사용해 멀티클러스터를 라우팅합니다.
 */
@Getter
@Configuration
@Slf4j
public class KubernetesClientConfig {

	private final String kubeconfigPath;
	private KubernetesClient client;

	/** Spring 빈 생성용 (실제 사용은 ClusterEntity 기반 생성자) */
	public KubernetesClientConfig() {
		this.kubeconfigPath = null;
	}

	public KubernetesClientConfig(ClusterEntity cluster, String kubeconfigPath) {
		log.info("KubernetesClientConfig init — cluster={}, kubeconfigPath={}", cluster.getId(), kubeconfigPath);

		if (kubeconfigPath == null || kubeconfigPath.isBlank()) {
			throw new IllegalStateException(
				"kubeconfig path is required (set kubernetes.kubeconfig.path or KUBECONFIG env var)");
		}
		this.kubeconfigPath = kubeconfigPath.replaceFirst("^~", System.getProperty("user.home"));

		try {
			Config config = buildConfig(cluster);
			this.client = new KubernetesClientBuilder()
				.withConfig(config)
				.build();
			log.info("Successfully created Kubernetes client for cluster '{}'", cluster.getId());
		} catch (KubernetesClientTimeoutException e) {
			log.error("타임아웃 오류: {}", e.getMessage(), e);
			throw new RuntimeException("클러스터 클라이언트 생성 실패 (타임아웃)", e);
		} catch (KubernetesClientException e) {
			int code = e.getCode();
			if (code == 401) {
				log.error("인증 실패 (401 Unauthorized)");
				throw new RuntimeException("클러스터 클라이언트 생성 실패 (인증 실패)", e);
			} else if (code == 403) {
				log.error("권한 없음 (403 Forbidden)");
				throw new RuntimeException("클러스터 클라이언트 생성 실패 (권한 없음)", e);
			} else if (code == 404) {
				log.error("요청한 리소스를 찾을 수 없음 (404 Not Found)");
				throw new RuntimeException("클러스터 클라이언트 생성 실패 (리소스 찾을 수 없음)", e);
			}
			log.error("KubernetesClientException: {} - {}", code, e.getMessage(), e);
			throw new RuntimeException("클러스터 클라이언트 생성 실패 (Kubernetes 오류)", e);
		} catch (Exception e) {
			log.error("예상치 못한 예외: {}", e.getMessage(), e);
			throw new RuntimeException("클러스터 클라이언트 생성 실패", e);
		}
	}

	public void closeClient() {
		if (client != null) {
			client.close();
		}
	}

	private Config buildConfig(ClusterEntity cluster) throws IOException {
		log.info("Loading kubeconfig from external file: {}", kubeconfigPath);
		String kubeconfigContent = Files.readString(Path.of(kubeconfigPath));

		// 멀티클러스터 지원: cluster.id를 컨텍스트 이름으로 사용.
		// 일치하는 컨텍스트가 없으면 kubeconfig의 current-context로 폴백.
		String contextName = resolveContextName(kubeconfigContent, cluster.getId());
		log.info("Using kubeconfig context '{}' for cluster '{}'", contextName, cluster.getId());

		return Config.fromKubeconfig(contextName, kubeconfigContent, kubeconfigPath);
	}

	/**
	 * cluster.id와 일치하는 kubeconfig 컨텍스트를 찾는다.
	 * 1) context.name == clusterId
	 * 2) context.context.cluster == clusterId
	 * 일치하는 컨텍스트가 없으면 예외 — current-context 폴백은 의도치 않은 클러스터 접속을 유발하므로 사용하지 않는다.
	 */
	@SuppressWarnings("unchecked")
	private String resolveContextName(String kubeconfigContent, String clusterId) {
		Yaml yaml = new Yaml();
		Map<String, Object> config = yaml.load(new StringReader(kubeconfigContent));
		List<Map<String, Object>> contexts = (List<Map<String, Object>>) config.get("contexts");
		if (contexts != null && clusterId != null) {
			for (Map<String, Object> ctx : contexts) {
				if (clusterId.equals(ctx.get("name"))) {
					return clusterId;
				}
			}
			for (Map<String, Object> ctx : contexts) {
				Map<String, Object> inner = (Map<String, Object>) ctx.get("context");
				if (inner != null && clusterId.equals(inner.get("cluster"))) {
					return (String) ctx.get("name");
				}
			}
		}
		throw new IllegalStateException(
			"No kubeconfig context matched cluster id '" + clusterId + "'");
	}
}
