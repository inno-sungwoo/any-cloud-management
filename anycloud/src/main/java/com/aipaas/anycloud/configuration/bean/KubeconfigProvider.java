package com.aipaas.anycloud.configuration.bean;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the kubeconfig file path from Spring property {@code kubernetes.kubeconfig.path}.
 *
 * <p>Path source:
 * <ol>
 *   <li>{@code kubernetes.kubeconfig.path} Spring property
 *       (typically backed by {@code KUBECONFIG_PATH} env var via {@code ${KUBECONFIG_PATH:../config/kubeconfig}} placeholder)</li>
 *   <li>If empty/null → returns null (caller falls back to legacy DB-based kubeconfig generation)</li>
 * </ol>
 *
 * <p><b>Note on the standard {@code KUBECONFIG} environment variable:</b><br>
 * This class does NOT read the standard {@code KUBECONFIG} env var directly.
 * The {@code KUBECONFIG} env var is only set <em>downstream</em> by
 * {@link com.aipaas.anycloud.service.util.HelmCommandExecutor} when spawning
 * the helm CLI child process — the helm binary follows that env var natively.
 * If you want the host's {@code KUBECONFIG} to take effect, set
 * {@code KUBECONFIG_PATH} to the same value or override
 * {@code kubernetes.kubeconfig.path} in application properties.
 */
@Component
@Slf4j
public class KubeconfigProvider {

    @Value("${kubernetes.kubeconfig.path:}")
    private String kubeconfigPath;

    /**
     * Returns the resolved kubeconfig path.
     *
     * @return absolute path to kubeconfig file, or null if not configured (caller should use legacy DB-based generation)
     */
    public String resolvePath() {
        if (kubeconfigPath == null || kubeconfigPath.isBlank()) {
            log.debug("No external kubeconfig configured — using legacy DB-based kubeconfig generation");
            return null;
        }

        String resolved = kubeconfigPath.replaceFirst("^~", System.getProperty("user.home"));
        log.info("Using external kubeconfig: {}", resolved);
        return resolved;
    }

    /**
     * Returns true if an external kubeconfig is configured.
     */
    public boolean isConfigured() {
        return resolvePath() != null;
    }
}
