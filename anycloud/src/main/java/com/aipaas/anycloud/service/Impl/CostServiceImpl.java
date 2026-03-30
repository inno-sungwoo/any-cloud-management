package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.model.dto.response.AlertDto;
import com.aipaas.anycloud.model.dto.response.CostEstimateDto;
import com.aipaas.anycloud.model.dto.response.CostReportDto;
import com.aipaas.anycloud.model.dto.response.CostSummaryDto;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import com.aipaas.anycloud.service.CostService;
import com.aipaas.anycloud.service.PrometheusQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CostServiceImpl implements CostService {

    private static final long GPU_HOUR_KRW = 1200;

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
        return monitUrl;
    }

    @Override
    public Object summary(String clusterName) {
        String monitUrl = getMonitUrl(clusterName);
        String gpuCountQuery = prometheusQueryService.resolve("cost", "gpu_count_by_ns", null);
        JsonNode result = executeQueryRaw(monitUrl, "query", gpuCountQuery);

        List<CostSummaryDto.TeamCost> teams = new ArrayList<>();
        long totalCost = 0;

        if (result.isArray()) {
            for (JsonNode node : result) {
                String namespace = node.path("metric").path("namespace").asText("");
                if (namespace.isEmpty() || "unknown".equals(namespace)) continue;
                int gpuCount = (int) node.path("value").get(1).asDouble(0);
                if (gpuCount <= 0) continue;
                long costKrw = gpuCount * GPU_HOUR_KRW * 24;
                teams.add(CostSummaryDto.TeamCost.builder()
                        .namespace(namespace)
                        .gpuCount(gpuCount)
                        .costKrw(costKrw)
                        .build());
                totalCost += costKrw;
            }
        }

        return CostSummaryDto.builder()
                .totalGpuCostKrw(totalCost)
                .teams(teams)
                .build();
    }

    @Override
    public Object idleWarnings(String clusterName) {
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
                    String alertName = labels.path("alertname").asText("");
                    if (alertName.toLowerCase().contains("idle") || alertName.toLowerCase().contains("gpu")) {
                        JsonNode annotations = alertNode.path("annotations");
                        JsonNode status = alertNode.path("status");
                        alerts.add(AlertDto.builder()
                                .alertName(alertName)
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
            }
            return alerts;
        } catch (Exception e) {
            log.error("Failed to fetch idle warnings from AlertManager: {}", e.getMessage(), e);
            return new ArrayList<AlertDto>();
        }
    }

    @Override
    public Object report(String clusterName) {
        String monitUrl = getMonitUrl(clusterName);
        String gpuUtilQuery = prometheusQueryService.resolve("cost", "gpu_util_range", null);

        // 1. 클러스터 전체 GPU 평균 활용률
        double clusterGpuUtil = 0.0;
        try {
            JsonNode utilResult = executeQueryRaw(monitUrl, "query",
                    "avg(nvidia_smi_utilization_gpu_ratio) * 100");
            if (utilResult.isArray() && utilResult.size() > 0) {
                clusterGpuUtil = utilResult.get(0).path("value").get(1).asDouble(0.0);
            }
        } catch (Exception ignored) {}

        // 2. GPU를 요청한 NS 목록 (현재 시점)
        Set<String> gpuNamespaces = new HashSet<>();
        try {
            JsonNode gpuPodResult = executeQueryRaw(monitUrl, "query",
                    "kube_pod_container_resource_requests{resource=\"nvidia_com_gpu\"}");
            if (gpuPodResult.isArray()) {
                for (JsonNode node : gpuPodResult) {
                    String ns = node.path("metric").path("namespace").asText("");
                    if (!ns.isEmpty()) gpuNamespaces.add(ns);
                }
            }
        } catch (Exception ignored) {}

        long end = Instant.now().getEpochSecond();
        long start = end - (7 * 24 * 3600);
        long step = 86400; // 1 day

        JsonNode result = executeQueryRange(monitUrl, gpuUtilQuery, start, end, step);
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.of("Asia/Seoul"));

        List<CostReportDto.DailyEntry> entries = new ArrayList<>();
        if (result.isArray()) {
            for (JsonNode node : result) {
                String namespace = node.path("metric").path("namespace").asText("");
                if (namespace.isEmpty() || "unknown".equals(namespace)) continue;
                JsonNode values = node.path("values");
                if (values.isArray()) {
                    for (JsonNode val : values) {
                        long timestamp = val.get(0).asLong();
                        double gpuCount = val.get(1).asDouble(0.0);
                        String date = dateFormatter.format(Instant.ofEpochSecond(timestamp));
                        long costKrw = (long) (gpuCount * GPU_HOUR_KRW * 24);
                        // GPU를 요청한 NS는 클러스터 활용률 표시, 아니면 0%
                        double avgUtil = gpuNamespaces.contains(namespace) ? clusterGpuUtil : 0.0;

                        entries.add(CostReportDto.DailyEntry.builder()
                                .date(date)
                                .namespace(namespace)
                                .avgGpuUtil(avgUtil)
                                .costKrw(costKrw)
                                .build());
                    }
                }
            }
        }

        return CostReportDto.builder()
                .period("7d")
                .entries(entries)
                .build();
    }

    @Override
    public Object estimate(int gpuCount, int hours) {
        long totalCost = (long) gpuCount * GPU_HOUR_KRW * hours;
        return CostEstimateDto.builder()
                .gpuCount(gpuCount)
                .hours(hours)
                .unitPriceKrw(GPU_HOUR_KRW)
                .totalCostKrw(totalCost)
                .build();
    }

    private JsonNode executeQueryRaw(String monitUrl, String metricType, String query) {
        try {
            String encodedQuery = UriUtils.encode(query, StandardCharsets.UTF_8);
            URI uri = UriComponentsBuilder.fromHttpUrl(monitUrl)
                    .path("/api/v1/" + metricType)
                    .queryParam("query", encodedQuery)
                    .build(true)
                    .toUri();

            String responseBody = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode rootNode = objectMapper.readTree(responseBody);
            return rootNode.path("data").path("result");
        } catch (Exception e) {
            log.error("Failed to execute Prometheus query: {}", e.getMessage(), e);
            throw new RuntimeException("Prometheus query failed", e);
        }
    }

    private JsonNode executeQueryRange(String monitUrl, String query, long start, long end, long step) {
        try {
            String encodedQuery = UriUtils.encode(query, StandardCharsets.UTF_8);
            URI uri = UriComponentsBuilder.fromHttpUrl(monitUrl)
                    .path("/api/v1/query_range")
                    .queryParam("query", encodedQuery)
                    .queryParam("start", start)
                    .queryParam("end", end)
                    .queryParam("step", step)
                    .build(true)
                    .toUri();

            String responseBody = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode rootNode = objectMapper.readTree(responseBody);
            return rootNode.path("data").path("result");
        } catch (Exception e) {
            log.error("Failed to execute Prometheus query_range: {}", e.getMessage(), e);
            throw new RuntimeException("Prometheus query_range failed", e);
        }
    }
}
