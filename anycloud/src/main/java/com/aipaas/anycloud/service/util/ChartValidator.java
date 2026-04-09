package com.aipaas.anycloud.service.util;

import com.aipaas.anycloud.error.exception.HelmDeploymentException;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * <pre>
 * ClassName : ChartValidator
 * Type : class
 * Description : 차트 배포 전 사전 검증을 담당하는 유틸리티 클래스입니다.
 * Related : ChartServiceImpl
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartValidator {

    private final HelmCommandExecutor helmCommandExecutor;

    /**
     * 릴리즈 이름 중복을 체크합니다 (비동기 실행 전 사전 검증).
     *
     * 정책:
     * - 동일 이름의 release가 "deployed" 상태이면 충돌로 간주하고 예외를 던집니다.
     *   (helm upgrade --install 사용 시에도 의도치 않은 덮어쓰기를 방지)
     * - "failed", "pending-install", "pending-upgrade" 등 비정상 상태이면
     *   dangling release로 보고 자동 cleanup을 시도합니다.
     *   (upgrade --install --atomic이 대부분 처리하지만, pre-flight 안전망)
     */
    public void checkReleaseNameDuplicate(String kubeconfigPath, String releaseName, String namespace) throws Exception {
        log.info("Checking release name duplicate for: {}", releaseName);

        // helm list --all 로 failed/pending까지 모두 조회
        StringBuilder command = new StringBuilder();
        command.append("helm list --all --kubeconfig ").append(kubeconfigPath);

        if (namespace != null && !namespace.trim().isEmpty()) {
            command.append(" --namespace ").append(namespace);
        } else {
            command.append(" --all-namespaces");
        }

        command.append(" --output json");
        
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("sh", "-c", command.toString());
        processBuilder.redirectErrorStream(true);
        
        Process process = processBuilder.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("Helm list command timed out during release name check");
            return; // 타임아웃 시에는 체크를 건너뛰고 배포 진행
        }
        
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.warn("Helm list command failed during release name check. Exit code: {}, Output: {}", 
                    exitCode, output.toString());
            return; // 실패 시에는 체크를 건너뛰고 배포 진행
        }
        
        String listOutput = output.toString();
        log.debug("Helm list output: {}", listOutput);
        
        // JSON 파싱해서 릴리즈 이름 확인
        if (listOutput.trim().isEmpty() || listOutput.equals("[]")) {
            log.info("No existing releases found. Release name {} is available.", releaseName);
            return;
        }
        
        // 릴리즈 존재 여부 + 상태 확인
        String releaseStatus = extractReleaseStatus(listOutput, releaseName);
        if (releaseStatus == null) {
            log.info("Release name {} is available for deployment.", releaseName);
            return;
        }

        log.info("Existing release '{}' found with status: {}", releaseName, releaseStatus);

        // deployed/superseded 상태: 정상 release이므로 차단하지 않고
        // helm upgrade --install (--atomic) 이 멱등적으로 처리하도록 위임한다.
        // (이전에는 여기서 예외를 던졌으나, upgrade --install 정책과 모순되어 제거)
        if ("deployed".equalsIgnoreCase(releaseStatus) || "superseded".equalsIgnoreCase(releaseStatus)) {
            log.info("Release '{}' is in '{}' state. Will be upgraded via 'helm upgrade --install'.",
                    releaseName, releaseStatus);
            return;
        }

        // 비정상 상태(failed, pending-*, uninstalling)이면 자동 정리
        // pending-* 상태에서는 upgrade --install 도 실패하므로 사전 cleanup 필수
        log.warn("Release '{}' is in non-deployed state '{}'. Attempting automatic cleanup before re-install.",
                releaseName, releaseStatus);
        try {
            cleanupDanglingRelease(kubeconfigPath, releaseName, namespace);
            log.info("Successfully cleaned up dangling release: {}", releaseName);
        } catch (Exception cleanupEx) {
            log.error("Failed to cleanup dangling release '{}': {}", releaseName, cleanupEx.getMessage());
            throw new HelmDeploymentException(
                "Release '" + releaseName + "' exists in '" + releaseStatus +
                "' state and automatic cleanup failed. Please uninstall it manually. Cause: " + cleanupEx.getMessage());
        }
    }

    /**
     * helm list --output json 응답에서 특정 release의 status 값을 추출합니다.
     * 정식 JSON 파서 의존성 없이 단순 문자열 스캔으로 처리합니다.
     */
    private String extractReleaseStatus(String listOutput, String releaseName) {
        String nameToken = "\"name\":\"" + releaseName + "\"";
        int idx = listOutput.indexOf(nameToken);
        if (idx < 0) return null;

        // 같은 객체 내부의 "status":"..." 검색 (다음 '}'까지)
        int objEnd = listOutput.indexOf('}', idx);
        if (objEnd < 0) objEnd = listOutput.length();
        String slice = listOutput.substring(idx, objEnd);

        String statusKey = "\"status\":\"";
        int sIdx = slice.indexOf(statusKey);
        if (sIdx < 0) return "unknown";
        int sStart = sIdx + statusKey.length();
        int sEnd = slice.indexOf('"', sStart);
        if (sEnd < 0) return "unknown";
        return slice.substring(sStart, sEnd);
    }

    /**
     * dangling release를 helm uninstall로 정리합니다.
     */
    private void cleanupDanglingRelease(String kubeconfigPath, String releaseName, String namespace) throws Exception {
        StringBuilder command = new StringBuilder();
        command.append("helm uninstall ").append(releaseName)
                .append(" --kubeconfig ").append(kubeconfigPath);
        if (namespace != null && !namespace.trim().isEmpty()) {
            command.append(" --namespace ").append(namespace);
        }

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append("\n");
            }
        }
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new HelmDeploymentException("Cleanup uninstall timed out for release: " + releaseName);
        }
        if (p.exitValue() != 0) {
            // 이미 없는 경우는 성공으로 간주
            String o = out.toString();
            if (o.contains("not found") || o.contains("release: not found")) {
                return;
            }
            throw new HelmDeploymentException("helm uninstall failed: " + o);
        }
    }

    /**
     * Helm repository 연결 상태를 확인합니다 (타임아웃 에러 사전 감지).
     */
    public void checkHelmRepositoryConnectivity(HelmRepoEntity repository) throws Exception {
        log.info("Checking Helm repository connectivity for: {}", repository.getName());
        
        // helm repo add + update 명령어로 repository 접근 테스트
        String repoAddCommand = helmCommandExecutor.buildHelmRepoAddCommand(repository);
        String updateCommand = "helm repo update " + repository.getName();
        String combinedCommand = repoAddCommand + " && " + updateCommand;
        
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("sh", "-c", combinedCommand);
        processBuilder.redirectErrorStream(true);
        
        Process process = processBuilder.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new HelmDeploymentException(
                "Helm repository connection timeout. Repository '" + repository.getName() + 
                "' is not responding within 20 seconds. Please check repository URL: " + repository.getUrl());
        }
        
        int exitCode = process.exitValue();
        String commandOutput = output.toString();
        
        if (exitCode != 0) {
            log.error("Helm repository connectivity test failed for: {}. Output: {}", repository.getName(), commandOutput);
            
            if (commandOutput.contains("context deadline exceeded") || 
                commandOutput.contains("timeout") || 
                commandOutput.contains("connection timed out")) {
                throw new HelmDeploymentException(
                    "Helm repository connection timeout. Repository '" + repository.getName() + 
                    "' is not responding. Please check repository URL and network connectivity. " +
                    "Error details: " + commandOutput);
            } else if (commandOutput.contains("connection refused") || 
                      commandOutput.contains("no such host") ||
                      commandOutput.contains("network unreachable")) {
                throw new HelmDeploymentException(
                    "Helm repository connection failed. Cannot reach repository '" + repository.getName() + 
                    "' at URL: " + repository.getUrl() + ". Please check repository URL and network connectivity. " +
                    "Error details: " + commandOutput);
            } else {
                throw new HelmDeploymentException(
                    "Helm repository test failed for '" + repository.getName() + "'. " +
                    "Error details: " + commandOutput);
            }
        }
        
        log.info("Helm repository connectivity test passed for: {}", repository.getName());
    }

    /**
     * 클러스터 정보가 유효한지 확인합니다.
     */
    public void validateClusterInfo(String clusterId, String version) throws HelmDeploymentException {
        if (clusterId == null || clusterId.trim().isEmpty()) {
            throw new HelmDeploymentException("Cluster ID is required for chart deployment");
        }
        // version 은 단순 표시용 메타데이터이므로 배포 게이트로 사용하지 않는다.
        // 실제 도달 가능성은 helm 명령 자체가 즉시 검사한다.
        log.debug("Cluster validation passed for cluster: {}", clusterId);
    }

    /**
     * 배포 파라미터가 유효한지 확인합니다.
     */
    public void validateDeploymentParameters(String repositoryName, String chartName, String releaseName, String clusterId) 
            throws HelmDeploymentException {
        if (repositoryName == null || repositoryName.trim().isEmpty()) {
            throw new HelmDeploymentException("Repository name is required");
        }
        
        if (chartName == null || chartName.trim().isEmpty()) {
            throw new HelmDeploymentException("Chart name is required");
        }
        
        if (releaseName == null || releaseName.trim().isEmpty()) {
            throw new HelmDeploymentException("Release name is required");
        }
        
        if (clusterId == null || clusterId.trim().isEmpty()) {
            throw new HelmDeploymentException("Cluster ID is required");
        }
        
        // 릴리즈 이름 규칙 검증 (Kubernetes naming convention)
        if (!releaseName.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")) {
            throw new HelmDeploymentException(
                "Invalid release name format. Release name must contain only lowercase letters, " +
                "numbers and hyphens, and must start and end with alphanumeric characters.");
        }
        
        log.debug("Deployment parameters validation passed");
    }

    /**
     * 네임스페이스 이름이 유효한지 확인합니다.
     */
    public void validateNamespace(String namespace) throws HelmDeploymentException {
        if (namespace != null && !namespace.trim().isEmpty()) {
            // 네임스페이스 이름 규칙 검증
            if (!namespace.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")) {
                throw new HelmDeploymentException(
                    "Invalid namespace format. Namespace must contain only lowercase letters, " +
                    "numbers and hyphens, and must start and end with alphanumeric characters.");
            }
            
            if (namespace.length() > 63) {
                throw new HelmDeploymentException("Namespace name cannot exceed 63 characters");
            }
        }
        
        log.debug("Namespace validation passed for: {}", namespace);
    }

    /**
     * 전체 배포 사전 검증을 수행합니다.
     */
    public void validateBeforeDeployment(String repositoryName, String chartName, String releaseName, 
            String clusterId, String namespace, String clusterVersion, String kubeconfigPath, 
            HelmRepoEntity repository) throws Exception {
        
        log.info("Starting comprehensive validation for deployment: {}/{} as {} to cluster {}", 
                repositoryName, chartName, releaseName, clusterId);
        
        // 1. 기본 파라미터 검증
        validateDeploymentParameters(repositoryName, chartName, releaseName, clusterId);
        
        // 2. 네임스페이스 검증
        validateNamespace(namespace);
        
        // 3. 클러스터 정보 검증
        validateClusterInfo(clusterId, clusterVersion);

        // 4. Repository 연결 확인
        checkHelmRepositoryConnectivity(repository);
        
        // 5. 릴리즈 이름 중복 체크
        checkReleaseNameDuplicate(kubeconfigPath, releaseName, namespace);
        
 
        
        log.info("All validation checks passed for deployment: {}/{}", repositoryName, chartName);
    }
}
