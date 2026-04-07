package com.aipaas.anycloud.configuration.bean;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the kubeconfig file path from application properties or environment variable.
 *
 * Priority: KUBECONFIG env var > application.properties > null (fallback to DB-based generation)
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
