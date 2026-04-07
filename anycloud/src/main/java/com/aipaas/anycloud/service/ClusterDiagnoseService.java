package com.aipaas.anycloud.service;

import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 특정 클러스터의 모니터링 가용성을 점검하는 진단 도구.
 *
 * 응답 예:
 * {
 *   "id": "ai-platform-k8s",
 *   "monitServerUrl": "http://localhost:9090",
 *   "monitStatus": "ACTIVE",
 *   "checks": {
 *     "promReachable": { "ok": true },
 *     "kubeStateMetrics": { "ok": true, "value": 1 },
 *     "nodeExporter": { "ok": true, "value": 8 },
 *     "helmExporter": { "ok": false, "message": "no metrics found" },
 *     "dcgmExporter": { "ok": false, "message": "no metrics found" }
 *   },
 *   "samples": {
 *     "cpuTotalCores": 272,
 *     "memoryTotalGiB": 438,
 *     "gpuCapacity": 2
 *   }
 * }
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ClusterDiagnoseService {

	private final ClusterRepository clusterRepository;
	private final WebClient.Builder webClientBuilder;
	private WebClient http;

	@PostConstruct
	void init() {
		this.http = webClientBuilder.build();
	}

	public Map<String, Object> diagnose(String clusterId) {
		ClusterEntity c = clusterRepository.findById(clusterId).orElseThrow(
				() -> new EntityNotFoundException("Cluster " + clusterId + " not found"));

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", c.getId());
		result.put("monitServerUrl", c.getMonitServerUrl());
		result.put("monitStatus", c.getMonitStatus());
		result.put("monitLastCheck", c.getMonitLastCheck());
		result.put("monitLastError", c.getMonitLastError());

		Map<String, Object> checks = new LinkedHashMap<>();
		Map<String, Object> samples = new LinkedHashMap<>();

		String url = c.getMonitServerUrl();
		if (url == null || url.isBlank() || url.contains("@@")
				|| !(url.startsWith("http://") || url.startsWith("https://"))) {
			checks.put("promReachable", failed("monit_server_url not configured: " + url));
			result.put("checks", checks);
			result.put("samples", samples);
			return result;
		}

		// 1. Prometheus reachability
		try {
			http.get().uri(url.replaceAll("/+$", "") + "/-/healthy")
					.retrieve().toBodilessEntity()
					.timeout(Duration.ofSeconds(3)).block();
			checks.put("promReachable", ok(null));
		} catch (Exception e) {
			checks.put("promReachable", failed(e.getMessage()));
			result.put("checks", checks);
			result.put("samples", samples);
			return result;
		}

		// 2~5. exporter probes (one PromQL per check)
		probeCount(checks, "kubeStateMetrics", url, "up{job=~\".*kube-state-metrics.*\"}");
		probeCount(checks, "nodeExporter", url, "count(up{job=~\".*node-exporter.*\"})");
		probeCount(checks, "helmExporter", url, "count(helm_chart_info)");
		probeCount(checks, "dcgmExporter", url, "count(DCGM_FI_DEV_GPU_UTIL)");

		// 6. sample real numbers
		samples.put("cpuTotalCores", queryScalar(url, "sum(machine_cpu_cores) or sum(kube_node_status_capacity{resource=\"cpu\"})"));
		samples.put("memoryTotalGiB", queryScalar(url, "sum(machine_memory_bytes)/1024/1024/1024 or sum(kube_node_status_capacity{resource=\"memory\"})/1024/1024/1024"));
		samples.put("gpuCapacity", queryScalar(url, "sum(kube_node_status_capacity{resource=\"nvidia_com_gpu\"}) or vector(0)"));

		result.put("checks", checks);
		result.put("samples", samples);
		return result;
	}

	private void probeCount(Map<String, Object> checks, String name, String url, String query) {
		try {
			JsonNode r = rawQuery(url, query);
			if (r != null && r.isArray() && r.size() > 0) {
				double val = 0;
				for (JsonNode n : r) {
					val += n.path("value").get(1).asDouble(0);
				}
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("ok", val > 0);
				m.put("value", val);
				if (val == 0) m.put("message", "metric exists but no targets up");
				checks.put(name, m);
			} else {
				checks.put(name, failed("no metrics found"));
			}
		} catch (Exception e) {
			checks.put(name, failed(e.getMessage()));
		}
	}

	private Double queryScalar(String url, String query) {
		try {
			JsonNode r = rawQuery(url, query);
			if (r != null && r.isArray() && r.size() > 0) {
				return r.get(0).path("value").get(1).asDouble(0);
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private JsonNode rawQuery(String url, String query) {
		URI uri = UriComponentsBuilder.fromHttpUrl(url)
				.path("/api/v1/query")
				.queryParam("query",
						org.springframework.web.util.UriUtils.encode(query, StandardCharsets.UTF_8))
				.build(true).toUri();
		String body = http.get().uri(uri)
				.retrieve().bodyToMono(String.class)
				.timeout(Duration.ofSeconds(4)).block();
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(body).path("data").path("result");
		} catch (Exception e) {
			return null;
		}
	}

	private Map<String, Object> ok(String msg) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("ok", true);
		if (msg != null) m.put("message", msg);
		return m;
	}

	private Map<String, Object> failed(String msg) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("ok", false);
		m.put("message", msg);
		return m;
	}
}
