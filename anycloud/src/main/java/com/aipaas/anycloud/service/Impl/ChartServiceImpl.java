package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.configuration.bean.KubeconfigProvider;
import com.aipaas.anycloud.configuration.bean.KubernetesClientConfig;
import com.aipaas.anycloud.error.exception.HelmChartNotFoundException;
import com.aipaas.anycloud.error.exception.HelmDeploymentException;
import com.aipaas.anycloud.error.exception.HelmRepositoryNotFoundException;
import com.aipaas.anycloud.model.dto.response.*;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import com.aipaas.anycloud.service.ChartService;
import com.aipaas.anycloud.service.ClusterService;
import com.aipaas.anycloud.service.CostService;
import com.aipaas.anycloud.service.HelmRepoService;
import com.aipaas.anycloud.service.util.HelmCommandExecutor;
import com.aipaas.anycloud.service.util.HelmReleaseScanner;
import com.aipaas.anycloud.service.util.ChartValidator;
import com.aipaas.anycloud.service.util.ChartParser;
import com.aipaas.anycloud.service.util.DeploymentOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartServiceImpl implements ChartService {

    private final HelmRepoService helmRepoService;
    private final ClusterService clusterService;
    private final CostService costService;
    private final KubeconfigProvider kubeconfigProvider;

    private final RestTemplate restTemplate;
    private final HelmCommandExecutor helmCommandExecutor;
    private final ChartValidator chartValidator;
    private final ChartParser chartParser;
    private final DeploymentOrchestrator deploymentOrchestrator;
    private final HelmReleaseScanner helmReleaseScanner;

    @Override
    public ChartListDto getChartList(String repositoryName) {
        log.info("Getting chart list for repository: {}", repositoryName);

        HelmRepoEntity repository = getRepository(repositoryName);

        try {
            String indexUrl = repository.getUrl().endsWith("/") ? repository.getUrl() + "index.yaml"
                    : repository.getUrl() + "/index.yaml";

            log.debug("Fetching index.yaml from URL: {}", indexUrl);

            HttpHeaders headers = createAuthHeaders(repository);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    indexUrl,
                    HttpMethod.GET,
                    entity,
                    String.class);

            log.debug("Response status: {}, Content length: {}",
                    response.getStatusCode(),
                    response.getBody() != null ? response.getBody().length() : 0);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return chartParser.parseIndexYaml(repositoryName, response.getBody());
            } else {
                throw new HelmChartNotFoundException(
                        "Unable to fetch index.yaml from repository: " + repositoryName +
                                " (HTTP " + response.getStatusCode() + ")");
            }

        } catch (HelmChartNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get chart list for repository: {} from URL: {}", repositoryName, repository.getUrl(),
                    e);
            throw new HelmChartNotFoundException(
                    "Failed to fetch charts from repository: " + repositoryName +
                            " - " + e.getMessage());
        }
    }

    @Override
    public ChartDetailDto getChartDetail(String repositoryName, String chartName, String version) {
        log.info("Getting chart detail for repository: {}, chart: {}, version: {}", repositoryName, chartName, version);

        HelmRepoEntity repository = getRepository(repositoryName);

        try {
            String indexUrl = repository.getUrl().endsWith("/") ? repository.getUrl() + "index.yaml"
                    : repository.getUrl() + "/index.yaml";

            log.debug("Fetching index.yaml from URL: {}", indexUrl);

            HttpHeaders headers = createAuthHeaders(repository);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    indexUrl,
                    HttpMethod.GET,
                    entity,
                    String.class);

            log.debug("Response status: {}, Content length: {}",
                    response.getStatusCode(),
                    response.getBody() != null ? response.getBody().length() : 0);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return chartParser.parseChartDetail(repositoryName, chartName, version, response.getBody());
            } else {
                throw new HelmChartNotFoundException(
                        "Unable to fetch index.yaml from repository: " + repositoryName +
                                " (HTTP " + response.getStatusCode() + ")");
            }

        } catch (HelmChartNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get chart detail for repository: {}, chart: {} from URL: {}", repositoryName,
                    chartName, repository.getUrl(), e);
            throw new HelmChartNotFoundException(
                    "Failed to fetch chart detail from repository: " + repositoryName +
                            " - " + e.getMessage());
        }
    }

    @Override
    public ChartValuesDto getChartValues(String repositoryName, String chartName, String version) {
        log.info("Getting values for chart: {}/{}, version: {}", repositoryName, chartName, version);

        HelmRepoEntity repository = getRepository(repositoryName);

        try {
            String command = helmCommandExecutor.buildHelmShowCommand("values", repository, chartName, version);
            String valuesContent = helmCommandExecutor.executeHelmCommandWithoutKubeconfig(command);

            return ChartValuesDto.builder()
                    .repositoryName(repositoryName)
                    .chartName(chartName)
                    .version(version)
                    .valuesContent(valuesContent)
                    .build();

        } catch (Exception e) {
            log.error("Failed to get values for chart: {}/{}", repositoryName, chartName, e);
            throw new HelmChartNotFoundException(repositoryName, chartName);
        }
    }

    @Override
    public ChartReadmeDto getChartReadme(String repositoryName, String chartName, String version) {
        log.info("Getting README for chart: {}/{}, version: {}", repositoryName, chartName, version);

        HelmRepoEntity repository = getRepository(repositoryName);

        try {
            String command = helmCommandExecutor.buildHelmShowCommand("readme", repository, chartName, version);
            String readmeContent = helmCommandExecutor.executeHelmCommandWithoutKubeconfig(command);

            return ChartReadmeDto.builder()
                    .repositoryName(repositoryName)
                    .chartName(chartName)
                    .version(version)
                    .readmeContent(readmeContent)
                    .build();

        } catch (Exception e) {
            log.error("Failed to get README for chart: {}/{}", repositoryName, chartName, e);
            throw new HelmChartNotFoundException(repositoryName, chartName);
        }
    }

    @Override
    public ChartDeployResponseDto deployChart(String repositoryName, String chartName, String releaseName,
            String clusterId, String namespace, String version, MultipartFile valuesFile) {
        log.info("Starting deployment request for chart: {}/{} as release: {} to cluster: {}",
                repositoryName, chartName, releaseName, clusterId);

        HelmRepoEntity repository = getRepository(repositoryName);
        ClusterEntity cluster = getCluster(clusterId);

        try {
            String testKubeconfigPath = createKubeconfigFile(cluster);

            try {
                KubernetesClientConfig manager = new KubernetesClientConfig(cluster, kubeconfigProvider.resolvePath());
                KubernetesClient client = manager.getClient();
                client.getApiVersion();

                chartValidator.validateBeforeDeployment(repositoryName, chartName, releaseName,
                        clusterId, namespace, cluster.getVersion(), testKubeconfigPath, repository);

                String dryRunCommand = helmCommandExecutor.buildHelmDryRunCommand(repository, chartName, releaseName,
                        namespace, version, valuesFile, testKubeconfigPath);
                String dryRunResult = helmCommandExecutor.executeHelmCommand(dryRunCommand, testKubeconfigPath);
                log.info("Dry-run completed successfully for release: {}", releaseName);

            } finally {
                deleteKubeconfigFile(testKubeconfigPath);
            }

        } catch (Exception e) {
            log.error("Failed kubeconfig or connectivity test for cluster: {}", clusterId, e);
            throw new HelmDeploymentException(
                    "Cannot connect to cluster: " + clusterId + ". Error: " + e.getMessage());
        }

        try {
            String kubeconfigPath = createKubeconfigFile(cluster);
            try {
                String command = helmCommandExecutor.buildHelmInstallCommand(repository, chartName, releaseName,
                        namespace, version, valuesFile, kubeconfigPath);
                helmCommandExecutor.executeHelmCommand(command, kubeconfigPath);
                log.info("Successfully deployed release: {} to cluster: {}", releaseName, clusterId);
            } finally {
                deleteKubeconfigFile(kubeconfigPath);
            }
        } catch (Exception e) {
            log.error("Failed to deploy release: {} to cluster: {}", releaseName, clusterId, e);
            throw new HelmDeploymentException(
                    "Deployment failed for release " + releaseName + ": " + e.getMessage());
        }

        return ChartDeployResponseDto.builder()
                .success(true)
                .message("Release " + releaseName + " deployed successfully to cluster " + clusterId)
                .build();
    }

    @Override
    public ChartDeployResponseDto getChartStatus(String releaseName, String clusterId, String namespace) {
        log.info("Getting chart status for release: {} in cluster: {}", releaseName, clusterId);

        ClusterEntity cluster = getCluster(clusterId);
        String targetNamespace = namespace != null ? namespace : "default";

        try {
            String kubeconfigPath = createKubeconfigFile(cluster);

            try {
                String command = helmCommandExecutor.buildHelmStatusCommand(releaseName, targetNamespace,
                        kubeconfigPath);
                String output = helmCommandExecutor.executeHelmCommand(command, kubeconfigPath);

                String status = chartParser.parseHelmStatusOutput(output);

                return ChartDeployResponseDto.builder()
                        .success(true)
                        .message("Release " + releaseName + " status: " + status)
                        .build();

            } finally {
                deleteKubeconfigFile(kubeconfigPath);
            }

        } catch (Exception e) {
            log.error("Failed to get chart status for release: {} in cluster: {}", releaseName, clusterId, e);
            return ChartDeployResponseDto.builder()
                    .success(false)
                    .message("Failed to get chart status for release: " + releaseName + " in cluster " + clusterId
                            + ". " + e.getMessage())
                    .build();
        }
    }

    private HelmRepoEntity getRepository(String repositoryName) {
        if (!helmRepoService.isHelmExist(repositoryName)) {
            throw new HelmRepositoryNotFoundException(repositoryName);
        }
        return helmRepoService.getHelmRepo(repositoryName);
    }

    private ClusterEntity getCluster(String clusterId) {
        if (clusterId == null || clusterId.trim().isEmpty()) {
            throw new IllegalArgumentException("Cluster ID is required for chart deployment");
        }
        return clusterService.getCluster(clusterId);
    }

    /**
     * 외부 kubeconfig 경로를 그대로 반환한다. DB 기반 임시파일 생성 분기는 제거되었습니다.
     */
    private String createKubeconfigFile(ClusterEntity cluster) throws IOException {
        String configuredPath = kubeconfigProvider.resolvePath();
        if (configuredPath == null) {
            throw new IOException("kubeconfig path is not configured (cluster: " + cluster.getId() + ")");
        }
        return configuredPath;
    }

    private void deleteKubeconfigFile(String kubeconfigPath) {
        // 외부 kubeconfig만 사용하므로 삭제할 임시파일이 없습니다.
    }

    private HttpHeaders createAuthHeaders(HelmRepoEntity repository) {
        HttpHeaders headers = new HttpHeaders();

        if (repository.getUsername() != null && repository.getPassword() != null) {
            String auth = repository.getUsername() + ":" + repository.getPassword();
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + encodedAuth);
        }

        return headers;
    }

    @Override
    public ChartReleasesResponseDto getReleases(String clusterId, String namespace) {
        log.info("Getting releases for cluster: {}, namespace: {}", clusterId, namespace);

        try {
            ClusterEntity cluster = getCluster(clusterId);

            String kubeconfigPath = createKubeconfigFile(cluster);

            try {
                String command = helmCommandExecutor.buildHelmListCommand(namespace, kubeconfigPath);
                String output = helmCommandExecutor.executeHelmCommand(command, kubeconfigPath);

                List<ChartReleasesResponseDto.ReleaseInfo> releases = chartParser.parseHelmListOutput(output);

                log.info("Successfully retrieved {} releases for cluster: {}", releases.size(), clusterId);

                return ChartReleasesResponseDto.builder()
                        .success(true)
                        .message("Releases retrieved successfully")
                        .releases(releases)
                        .build();

            } finally {
                deleteKubeconfigFile(kubeconfigPath);
            }

        } catch (Exception e) {
            log.error("Failed to get releases for cluster: {}", clusterId, e);
            return ChartReleasesResponseDto.builder()
                    .success(false)
                    .message("Failed to retrieve releases: " + e.getMessage())
                    .releases(new ArrayList<>())
                    .build();
        }
    }

    @Override
    public List<? extends HasMetadata> getHelmResources(String clusterName, String namespace, String releaseName) {
        ClusterEntity cluster = clusterService.getCluster(clusterName);
        return helmReleaseScanner.scanReleaseResources(cluster, namespace, releaseName);
    }

    @Override
    public ChartDeployResponseDto uninstallRelease(String releaseName, String clusterId, String namespace) {
        log.info("Uninstalling release: {} from cluster: {}, namespace: {}", releaseName, clusterId, namespace);

        ClusterEntity cluster = clusterService.getCluster(clusterId);
        String kubeconfigPath = kubeconfigProvider.resolvePath();
        if (kubeconfigPath == null) {
            throw new HelmDeploymentException("kubeconfig path is not configured");
        }

        try {
            String command = helmCommandExecutor.buildHelmUninstallCommand(releaseName, namespace, kubeconfigPath);
            String output = helmCommandExecutor.executeHelmCommand(command, kubeconfigPath);
            log.info("Successfully uninstalled release: {}. Output: {}", releaseName, output);

            try {
                costService.deleteReservation(releaseName, clusterId);
                log.info("Deleted GPU reservation for release: {}", releaseName);
            } catch (Exception ex) {
                log.warn("No GPU reservation found for release: {} (or already deleted): {}", releaseName, ex.getMessage());
            }

            return ChartDeployResponseDto.builder()
                    .success(true)
                    .message("릴리즈 '" + releaseName + "'이(가) 삭제되었습니다.")
                    .build();

        } catch (Exception e) {
            log.error("Failed to uninstall release: {}", releaseName, e);
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("not found") || msg.contains("release: not found")) {
                log.info("Release '{}' already removed from cluster", releaseName);
                return ChartDeployResponseDto.builder()
                        .success(true)
                        .message("릴리즈 '" + releaseName + "'은(는) 이미 삭제되었습니다.")
                        .build();
            }
            throw new HelmDeploymentException("릴리즈 삭제 실패: " + e.getMessage());
        }
    }
}
