package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.model.dto.response.AlertDto;
import com.aipaas.anycloud.model.dto.response.MonitoringSummaryDto;
import com.aipaas.anycloud.model.dto.response.ReleaseStatusDto;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.model.entity.MonitEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import com.aipaas.anycloud.service.MonitService;
import com.aipaas.anycloud.service.PrometheusQueryService;
import com.aipaas.anycloud.util.FormatConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service("MonitServiceImpl")
@Slf4j
@RequiredArgsConstructor
public class MonitServiceImpl implements MonitService {

	private final ObjectMapper objectMapper;
	private final ClusterRepository clusterRepository;
	private final WebClient webClient;
	private final PrometheusQueryService prometheusQueryService;

	private String getMonitUrl(String clusterName) {
		ClusterEntity cluster = clusterRepository.findById(clusterName).orElseThrow(
				() -> new EntityNotFoundException("Cluster with Name " + clusterName + " Not Found."));
		String monitUrl = cluster.getMonitServerUrl();
		if (monitUrl == null || monitUrl.isEmpty()) {
			throw new EntityNotFoundException("Monitoring Url Not Found for cluster: " + clusterName);
		}
		log.info("Using monitUrl: {} for cluster: {}", monitUrl, clusterName);
		return monitUrl;
	}

	@Override
	public List<MonitEntity.NodeStatus> nodeStatus(String clusterName) {
		String monitUrl = getMonitUrl(clusterName);
		String query = prometheusQueryService.resolve("node", "status", null);

		Map<String, MonitEntity.NodeStatus.NodeStatusBuilder> builderMap = new HashMap<>();
		Map<String, MonitEntity.Condition> conditionMap = new HashMap<>();

		JsonNode result = executeQueryRaw(monitUrl, "query", query, null);
		for (JsonNode node : result) {
			JsonNode metric = node.get("metric");
			String nodeName = metric.get("node").asText();
			String nodeIp = metric.get("instance").asText().split(":")[0];
			String conditionStr = metric.get("condition").asText();
			String statusStr = metric.get("status").asText();

			MonitEntity.Condition cond = conditionMap.get(nodeName);
			if (cond == null) {
				cond = MonitEntity.Condition.builder().build();
				conditionMap.put(nodeName, cond);
			}

			switch (conditionStr) {
				case "Ready":
					cond.setReady(Boolean.parseBoolean(statusStr));
					break;
				case "DiskPressure":
					cond.setDiskPressure(Boolean.parseBoolean(statusStr));
					break;
				case "MemoryPressure":
					cond.setMemoryPressure(Boolean.parseBoolean(statusStr));
					break;
				case "PIDPressure":
					cond.setPIDPressure(Boolean.parseBoolean(statusStr));
					break;
				case "NetworkUnavailable":
					cond.setNetworkPressure(Boolean.parseBoolean(statusStr));
					break;
			}

			MonitEntity.NodeStatus.NodeStatusBuilder builder = builderMap.get(nodeName);
			if (builder == null) {
				builder = MonitEntity.NodeStatus.builder()
						.nodeName(nodeName)
						.nodeIp(nodeIp)
						.condition(cond);
				builderMap.put(nodeName, builder);
			}
		}

		return builderMap.values().stream()
				.map(MonitEntity.NodeStatus.NodeStatusBuilder::build)
				.collect(Collectors.toList());
	}

	@Override
	public Object resourceMonit(String clusterName, String Type, String key, Map<String, String> QueryFilter) {

		String monitUrl = getMonitUrl(clusterName);

		String resolve_query = prometheusQueryService.resolveFromParams(Type, key, QueryFilter);
		JsonNode result;

		if (QueryFilter.get("duration") != null) {
			Map<String, Long> timeQueryParams = timeRangeCreate(QueryFilter.get("duration"));
			result = executeQueryRaw(monitUrl, "query_range", resolve_query, timeQueryParams);
			ArrayList<MonitEntity.MetrixMonit> metrixMonits = new ArrayList<>();
			for (JsonNode node : result) {
				ArrayList<MonitEntity.Values> valuesArrayList = new ArrayList<>();
				for (JsonNode values : node.get("values")) {
					double rawValue = values.get(1).asDouble();
					double convertedValue = convertMetricValue(Type, key, rawValue);
					MonitEntity.Values value = MonitEntity.Values.builder()
							.time(UnixToDate(values.get(0).toString()))
							.value(convertedValue)
							.build();
					valuesArrayList.add(value);
				}
				MonitEntity.MetrixMonit metrixMonit = MonitEntity.MetrixMonit.builder()
						.info(node.get("metric"))
						.values(valuesArrayList)
						.build();
				metrixMonits.add(metrixMonit);
			}

			return metrixMonits;
		} else {
			result = executeQueryRaw(monitUrl, "query", resolve_query, null);
			if (result.isArray()) {
				ArrayList<MonitEntity.VectorMonit> monitArray = new ArrayList<>();
				for (JsonNode node : result) {
					double rawValue = node.get("value").get(1).asDouble();
					double convertedValue = convertMetricValue(Type, key, rawValue);
					MonitEntity.VectorMonit usage_val = MonitEntity.VectorMonit.builder()
							.info(node.get("metric"))
							.value(convertedValue)
							.build();
					monitArray.add(usage_val);
				}
				return monitArray;
			} else {
				double rawValue = result.get(0).get("value").get(1).asDouble();
				double convertedValue = convertMetricValue(Type, key, rawValue);
				MonitEntity.VectorMonit monit = MonitEntity.VectorMonit.builder()
						.info(result.get(0).get("metric"))
						.value(convertedValue)
						.build();

				return monit;
			}
		}
	}

	@Override
	public Object monitoringSummary(String clusterName) {
		String monitUrl = getMonitUrl(clusterName);

		String helmReleasesQuery = prometheusQueryService.resolve("monitoring", "helm_releases", null);
		String gpuCountQuery = prometheusQueryService.resolve("monitoring", "gpu_count", null);
		String gpuAvgUtilQuery = prometheusQueryService.resolve("monitoring", "gpu_avg_util", null);
		String activeAlertsQuery = prometheusQueryService.resolve("monitoring", "active_alerts", null);

		int helmReleaseCount = extractScalarInt(executeQueryRaw(monitUrl, "query", helmReleasesQuery, null));
		int gpuCount = extractScalarInt(executeQueryRaw(monitUrl, "query", gpuCountQuery, null));
		double avgGpuUtil = extractScalarDouble(executeQueryRaw(monitUrl, "query", gpuAvgUtilQuery, null));
		int activeAlertCount = extractScalarInt(executeQueryRaw(monitUrl, "query", activeAlertsQuery, null));

		return MonitoringSummaryDto.builder()
				.helmReleaseCount(helmReleaseCount)
				.gpuCount(gpuCount)
				.avgGpuUtil(avgGpuUtil)
				.activeAlertCount(activeAlertCount)
				.build();
	}

	@Override
	public Object monitoringReleases(String clusterName) {
		String monitUrl = getMonitUrl(clusterName);

		// 1. Helm 릴리즈 목록 조회
		String releaseQuery = prometheusQueryService.resolve("monitoring", "release_status", null);
		JsonNode releaseResult = executeQueryRaw(monitUrl, "query", releaseQuery, null);

		// 2. GPU 요청 Pod 조회 (어떤 릴리즈가 GPU를 쓰는지)
		// kube_pod_container_resource_requests{resource="nvidia_com_gpu"} → namespace, pod 라벨
		JsonNode gpuPodResult = executeQueryRaw(monitUrl, "query",
				"kube_pod_container_resource_requests{resource=\"nvidia_com_gpu\"}", null);
		Set<String> gpuNamespaces = new HashSet<>();
		if (gpuPodResult.isArray()) {
			for (JsonNode node : gpuPodResult) {
				String ns = node.path("metric").path("namespace").asText("");
				if (!ns.isEmpty()) gpuNamespaces.add(ns);
			}
		}

		// 3. GPU 메트릭 조회 (UUID별 — 멀티 GPU 대응)
		// nvidia_smi_* 메트릭은 uuid 라벨로 GPU별 구분
		Map<String, Map<String, Object>> gpuDataByUuid = new HashMap<>();
		JsonNode gpuInfoResult = executeQueryRaw(monitUrl, "query", "nvidia_smi_gpu_info", null);
		if (gpuInfoResult.isArray()) {
			for (JsonNode node : gpuInfoResult) {
				JsonNode m = node.get("metric");
				String uuid = m.path("uuid").asText("");
				if (!uuid.isEmpty()) {
					Map<String, Object> data = new HashMap<>();
					data.put("name", m.path("name").asText(""));
					gpuDataByUuid.put(uuid, data);
				}
			}
		}
		// 각 GPU UUID별 메트릭 수집
		for (String metricQuery : new String[]{
				"nvidia_smi_utilization_gpu_ratio * 100",
				"nvidia_smi_temperature_gpu",
				"nvidia_smi_power_draw_watts",
				"nvidia_smi_memory_used_bytes / 1048576",
				"nvidia_smi_memory_total_bytes / 1048576"}) {
			JsonNode metricResult = executeQueryRaw(monitUrl, "query", metricQuery, null);
			if (metricResult.isArray()) {
				for (JsonNode node : metricResult) {
					String uuid = node.path("metric").path("uuid").asText("");
					double value = node.path("value").get(1).asDouble(0.0);
					Map<String, Object> data = gpuDataByUuid.computeIfAbsent(uuid, k -> new HashMap<>());
					if (metricQuery.contains("utilization_gpu")) data.put("util", value);
					else if (metricQuery.contains("temperature")) data.put("temp", value);
					else if (metricQuery.contains("power_draw")) data.put("power", value);
					else if (metricQuery.contains("memory_used")) data.put("vramUsed", value);
					else if (metricQuery.contains("memory_total")) data.put("vramTotal", value);
				}
			}
		}

		// 4. 릴리즈 목록 파싱
		List<ReleaseStatusDto> releases = new ArrayList<>();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
				.withZone(ZoneId.of("Asia/Seoul"));

		if (releaseResult.isArray()) {
			for (JsonNode node : releaseResult) {
				JsonNode metric = node.get("metric");
				String releaseName = metric.has("release") ? metric.get("release").asText() :
						(metric.has("name") ? metric.get("name").asText() : "");
				// helm-exporter의 namespace는 exporter pod 네임스페이스(monitoring)이므로
			// 실제 릴리즈 네임스페이스인 exported_namespace를 우선 사용
			String namespace = metric.has("exported_namespace") ? metric.get("exported_namespace").asText() :
					(metric.has("namespace") ? metric.get("namespace").asText() : "");
				String description = metric.has("description") ? metric.get("description").asText() : "";
				String status = description.contains("complete") ? "deployed" :
								description.contains("failed") ? "failed" : description;

				// updated 타임스탬프 변환
				String updatedRaw = metric.has("updated") ? metric.get("updated").asText() : "";
				String updatedFormatted = updatedRaw;
				try {
					long ts = Long.parseLong(updatedRaw);
					if (ts > 1000000000000L) ts = ts / 1000;
					updatedFormatted = formatter.format(Instant.ofEpochSecond(ts));
				} catch (NumberFormatException ignored) {}

				// GPU 데이터: 해당 릴리즈의 namespace가 GPU를 사용하는 경우만 표시
				ReleaseStatusDto.ReleaseStatusDtoBuilder builder = ReleaseStatusDto.builder()
						.name(releaseName)
						.namespace(namespace)
						.status(status)
						.chart(metric.has("chart") ? metric.get("chart").asText() : "")
						.chartVersion(metric.has("version") ? metric.get("version").asText() : "")
						.updated(updatedFormatted);

				if (gpuNamespaces.contains(namespace) && !gpuDataByUuid.isEmpty()) {
					// 이 릴리즈가 GPU를 사용하는 namespace에 있으면 GPU 데이터 표시
					Map<String, Object> gpu = gpuDataByUuid.values().iterator().next();
					builder.gpuUtil((Double) gpu.getOrDefault("util", null))
							.gpuName((String) gpu.getOrDefault("name", null))
							.gpuTemp((Double) gpu.getOrDefault("temp", null))
							.gpuPowerWatt((Double) gpu.getOrDefault("power", null))
							.vramUsedMb((Double) gpu.getOrDefault("vramUsed", null))
							.vramTotalMb((Double) gpu.getOrDefault("vramTotal", null));
				}
				// GPU를 사용하지 않는 릴리즈는 null (프론트에서 "-" 표시)

				releases.add(builder.build());
			}
		}
		return releases;
	}

	@Override
	public Object monitoringAlerts(String clusterName) {
		String monitUrl = getMonitUrl(clusterName);
		try {
			URI alertUri = UriComponentsBuilder.fromHttpUrl(monitUrl)
					.replacePath("/api/v2/alerts")
					.queryParam("active", "true")
					.build()
					.toUri();

			String responseBody = webClient.get()
					.uri(alertUri)
					.retrieve()
					.bodyToMono(String.class)
					.block();

			JsonNode alertsArray = objectMapper.readTree(responseBody);
			List<AlertDto> alerts = new ArrayList<>();

			if (alertsArray.isArray()) {
				for (JsonNode alertNode : alertsArray) {
					JsonNode labels = alertNode.path("labels");
					JsonNode annotations = alertNode.path("annotations");
					JsonNode status = alertNode.path("status");

					alerts.add(AlertDto.builder()
							.alertName(labels.path("alertname").asText(""))
							.severity(labels.path("severity").asText(""))
							.namespace(labels.path("namespace").asText(""))
							.message(annotations.path("description").asText(
									annotations.path("message").asText("")))
							.startsAt(alertNode.path("startsAt").asText(""))
							.status(status.path("state").asText(
									alertNode.path("state").asText("")))
							.build());
				}
			}
			return alerts;
		} catch (Exception e) {
			log.error("Failed to fetch alerts from AlertManager: {}", e.getMessage(), e);
			return new ArrayList<AlertDto>();
		}
	}

	@Override
	public Object monitoringGpuStatus(String clusterName) {
		String monitUrl = getMonitUrl(clusterName);

		// GPU 카드별 정보 수집 — DCGM Exporter의 DCGM_FI_DEV_GPU_UTIL을 기준으로 UUID 목록 확보
		List<Map<String, Object>> gpuList = new ArrayList<>();
		JsonNode gpuInfoResult = executeQueryRaw(monitUrl, "query", "DCGM_FI_DEV_GPU_UTIL", null);
		if (gpuInfoResult.isArray()) {
			for (JsonNode node : gpuInfoResult) {
				JsonNode m = node.get("metric");
				String uuid = m.path("UUID").asText("");
				Map<String, Object> gpu = new LinkedHashMap<>();
				gpu.put("uuid", uuid);
				gpu.put("name", m.path("modelName").asText(""));
				gpu.put("driverVersion", m.path("DCGM_FI_DRIVER_VERSION").asText(""));
				gpuList.add(gpu);
			}
		}

		// 각 GPU UUID별 실시간 메트릭 수집 (DCGM 메트릭)
		Map<String, String> metricQueries = new LinkedHashMap<>();
		metricQueries.put("utilization", "DCGM_FI_DEV_GPU_UTIL");
		metricQueries.put("memoryUtilization", "DCGM_FI_DEV_MEM_COPY_UTIL");
		metricQueries.put("temperature", "DCGM_FI_DEV_GPU_TEMP");
		metricQueries.put("powerDraw", "DCGM_FI_DEV_POWER_USAGE");
		metricQueries.put("vramUsedMb", "DCGM_FI_DEV_FB_USED");
		metricQueries.put("vramTotalMb", "(DCGM_FI_DEV_FB_USED + DCGM_FI_DEV_FB_FREE)");
		metricQueries.put("fanSpeed", "DCGM_FI_DEV_FAN_SPEED");

		Map<String, Map<String, Double>> metricsByUuid = new HashMap<>();
		for (Map.Entry<String, String> entry : metricQueries.entrySet()) {
			JsonNode metricResult = executeQueryRaw(monitUrl, "query", entry.getValue(), null);
			if (metricResult.isArray()) {
				for (JsonNode node : metricResult) {
					String uuid = node.path("metric").path("UUID").asText("");
					double value = node.path("value").get(1).asDouble(0.0);
					metricsByUuid.computeIfAbsent(uuid, k -> new HashMap<>())
							.put(entry.getKey(), value);
				}
			}
		}

		// GPU 정보 + 메트릭 합침
		for (Map<String, Object> gpu : gpuList) {
			String uuid = (String) gpu.get("uuid");
			Map<String, Double> metrics = metricsByUuid.getOrDefault(uuid, new HashMap<>());
			gpu.put("utilization", metrics.getOrDefault("utilization", 0.0));
			gpu.put("memoryUtilization", metrics.getOrDefault("memoryUtilization", 0.0));
			gpu.put("temperature", metrics.getOrDefault("temperature", 0.0));
			gpu.put("powerDraw", metrics.getOrDefault("powerDraw", 0.0));
			gpu.put("vramUsedMb", metrics.getOrDefault("vramUsedMb", 0.0));
			gpu.put("vramTotalMb", metrics.getOrDefault("vramTotalMb", 0.0));
			gpu.put("fanSpeed", metrics.getOrDefault("fanSpeed", 0.0));
		}

		// nvidia_smi exporter가 없는 경우 kube GPU 리소스 요청 정보로 fallback
		if (gpuList.isEmpty()) {
			log.info("No nvidia_smi metrics found, falling back to kube GPU resource requests");
			JsonNode gpuRequestResult = executeQueryRaw(monitUrl, "query",
					"kube_pod_container_resource_requests{resource=\"nvidia_com_gpu\"}", null);
			if (gpuRequestResult.isArray()) {
				int idx = 0;
				for (JsonNode node : gpuRequestResult) {
					JsonNode m = node.get("metric");
					double gpuCount = node.path("value").get(1).asDouble(0.0);
					Map<String, Object> gpu = new LinkedHashMap<>();
					gpu.put("uuid", "gpu-alloc-" + idx++);
					gpu.put("name", "GPU (할당 정보)");
					gpu.put("driverVersion", "-");
					gpu.put("utilization", 0.0);
					gpu.put("memoryUtilization", 0.0);
					gpu.put("temperature", 0.0);
					gpu.put("powerDraw", 0.0);
					gpu.put("vramUsedMb", 0.0);
					gpu.put("vramTotalMb", 0.0);
					gpu.put("fanSpeed", 0.0);
					gpu.put("allocatedGpu", gpuCount);
					gpu.put("namespace", m.path("namespace").asText(""));
					gpu.put("pod", m.path("pod").asText(""));
					gpu.put("node", m.path("node").asText(""));
					gpuList.add(gpu);
				}
			}
		}

		return gpuList;
	}

	private int extractScalarInt(JsonNode result) {
		try {
			if (result.isArray() && result.size() > 0) {
				return (int) result.get(0).get("value").get(1).asDouble(0);
			}
		} catch (Exception e) {
			log.warn("Failed to extract scalar int from result: {}", e.getMessage());
		}
		return 0;
	}

	private double extractScalarDouble(JsonNode result) {
		try {
			if (result.isArray() && result.size() > 0) {
				return result.get(0).get("value").get(1).asDouble(0.0);
			}
		} catch (Exception e) {
			log.warn("Failed to extract scalar double from result: {}", e.getMessage());
		}
		return 0.0;
	}

	private JsonNode executeQueryRaw(String monitUrl, String metricType, String query,
			Map<String, Long> timeQueryParams) {
		try {
			String encodedQuery = UriUtils.encode(query, StandardCharsets.UTF_8);

			log.debug("query : {} ", query);
			log.debug("encodedQuery : {} ", encodedQuery);
			UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(monitUrl)
					.path("/api/v1/" + metricType)
					.queryParam("query", encodedQuery);

			// query_range면 시간 파라미터 추가
			if ("query_range".equals(metricType) && timeQueryParams != null) {
				builder.queryParam("start", timeQueryParams.get("start"))
						.queryParam("end", timeQueryParams.get("end"))
						.queryParam("step", timeQueryParams.get("step"));
			}
			URI uri = builder.build(true).toUri();
			// URI uri = UriComponentsBuilder.fromHttpUrl(monitUrl)
			// .path("/api/v1/"+ metricType)
			// .queryParam("query", encodedQuery) // + 기호가 미리 인코딩된 쿼리
			// .build(true) // 이미 인코딩된 값이므로 추가 인코딩 안함
			// .toUri();

			String responseBody = webClient.get()
					.uri(uri)
					.retrieve()
					.bodyToMono(String.class)
					.block();

			JsonNode rootNode = objectMapper.readTree(responseBody);
			log.debug("responseBody : {} ", responseBody);
			JsonNode resultArray = rootNode.path("data").path("result");

			// if (resultArray.isArray() && resultArray.size() > 0) {
			// 	return resultArray;
			// }

			return resultArray;
			// throw new IllegalStateException("No valid data in Prometheus response");

		} catch (Exception e) {
			log.error("Failed to execute Prometheus query: {}", e.getMessage(), e);
			throw new RuntimeException("Prometheus query failed", e);
		}
	}

	private Map<String, Long> timeRangeCreate(String duration) {
		Map<String, Long> timeRange = new HashMap<>();
		if (duration != null && !duration.isEmpty()) {

			// 1. duration 파싱 (숫자 검증)
			long durationInput;
			try {
				durationInput = Long.parseLong(duration);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("duration 값이 숫자가 아닙니다: " + duration);
			}

			// 2. duration 단위 판별 (기본: 분)
			// 1시간 이상인데 10000 이상이면 초 단위로 간주
			boolean isSeconds = durationInput > 10000;
			long durationSeconds = isSeconds ? durationInput : durationInput * 60;

			// 3. 기준 시각 하나로 고정
			long end = Instant.now().getEpochSecond();
			long start = end - durationSeconds;

			// 4. step 계산 (20 포인트)
			int points = 20;
			long step = (end - start) / points;
 
			// 5. 사람이 읽기 쉽게 변환
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
					.withZone(ZoneId.of("Asia/Seoul"));
			String startHuman = formatter.format(Instant.ofEpochSecond(start));
			String endHuman = formatter.format(Instant.ofEpochSecond(end));

			// 6. 로그 출력
			log.info("[Prometheus Time Range]");
			log.info("duration (입력): " + durationInput + (isSeconds ? " sec" : " min"));
			log.info("start = " + start + " (" + startHuman + ")");
			log.info("end   = " + end + " (" + endHuman + ")");
			log.info("step  = " + step + " sec");

			// 7. 쿼리 문자열 생성
			timeRange.put("start", start);
			timeRange.put("end", end);
			timeRange.put("step", step);
		}
		return timeRange;

	}

	private Date UnixToDate(String unixtime) {
		if (unixtime == null || unixtime.isEmpty()) {
			return null; // 입력이 없으면 null 반환
		}

		try {
			long epochSeconds = Long.parseLong(unixtime);
			Instant instant = Instant.ofEpochSecond(epochSeconds);
			return Date.from(instant);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid Unix timestamp: " + unixtime, e);
		}
	}

	/**
	 * 메트릭 타입과 키에 따라 값을 적절한 단위로 변환
	 * 
	 * @param type 메트릭 타입 (cpu, memory, disk, network, filesystem, gpu, pod 등)
	 * @param key 메트릭 키 (usage, total, request, limit 등)
	 * @param rawValue 원본 값
	 * @return 변환된 값
	 */
	private double convertMetricValue(String type, String key, double rawValue) {
		if (rawValue < 0) {
			return rawValue; // 음수는 그대로 반환
		}

		switch (type.toLowerCase()) {
			case "cpu":
				// CPU는 이미 cores 단위이므로 변환 불필요
				return rawValue;

			case "memory":
				// Memory: bytes → GB
				if (key.contains("usage") || key.contains("total") || key.contains("request") 
						|| key.contains("limit") || key.contains("usage_namespace")) {
					return FormatConverter.convertBytesToGB(rawValue);
				}
				return rawValue;

			case "disk":
				// Disk: bytes/sec → MB/s
				if (key.contains("read_bytes") || key.contains("write_bytes")) {
					return FormatConverter.convertBytesPerSecToMBPerSec(rawValue);
				}
				return rawValue;

			case "network":
				// Network: bytes/sec → MB/s
				if (key.contains("receive_bytes") || key.contains("transmit_bytes")) {
					return FormatConverter.convertBytesPerSecToMBPerSec(rawValue);
				}
				// drop, err는 개수이므로 변환 불필요
				return rawValue;

			case "filesystem":
				// Filesystem: bytes → GB
				if (key.contains("usage") || key.contains("total") || key.contains("usage_namespace")) {
					return FormatConverter.convertBytesToGB(rawValue);
				}
				return rawValue;

			case "gpu":
				// GPU는 개수 또는 퍼센트이므로 변환 불필요
				return rawValue;

			case "pod":
				// Pod는 개수이므로 변환 불필요
				return rawValue;

			case "tpu":
				// TPU는 개수이므로 변환 불필요
				return rawValue;

			default:
				// 알 수 없는 타입은 그대로 반환
				return rawValue;
		}
	}
}
