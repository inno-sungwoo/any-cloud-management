package com.aipaas.anycloud.service;

import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 등록된 모든 클러스터의 Prometheus 도달성을 1분 주기로 점검하고 cluster.monit_status를 갱신한다.
 *
 * 상태:
 *   ACTIVE         — http://.../-/healthy 200 응답
 *   UNREACHABLE    — URL은 있으나 도달 실패
 *   NOT_CONFIGURED — URL이 비어있거나 placeholder(@@..@@) / http(s) 스킴 아님
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ClusterMonitHealthChecker {

	public static final String STATUS_ACTIVE = "ACTIVE";
	public static final String STATUS_UNREACHABLE = "UNREACHABLE";
	public static final String STATUS_NOT_CONFIGURED = "NOT_CONFIGURED";

	private final ClusterRepository clusterRepository;
	private final WebClient.Builder webClientBuilder;
	private WebClient http;

	@PostConstruct
	void init() {
		this.http = webClientBuilder.build();
		// 부팅 직후 1회 실행하여 selector가 처음부터 정확한 상태를 보이도록
		try {
			checkAll();
		} catch (Exception e) {
			log.warn("Initial cluster health check failed: {}", e.getMessage());
		}
	}

	@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
	public void checkAll() {
		List<ClusterEntity> clusters = clusterRepository.findAll();
		for (ClusterEntity c : clusters) {
			check(c);
		}
	}

	public void check(ClusterEntity c) {
		String url = c.getMonitServerUrl();
		if (url == null || url.isBlank() || url.contains("@@")
				|| !(url.startsWith("http://") || url.startsWith("https://"))) {
			update(c, STATUS_NOT_CONFIGURED, "monit_server_url not set or invalid: '" + url + "'");
			return;
		}
		try {
			http.get()
					.uri(url.replaceAll("/+$", "") + "/-/healthy")
					.retrieve()
					.toBodilessEntity()
					.timeout(Duration.ofSeconds(3))
					.block();
			update(c, STATUS_ACTIVE, null);
		} catch (Exception e) {
			update(c, STATUS_UNREACHABLE, truncate(e.getMessage(), 480));
		}
	}

	private void update(ClusterEntity c, String status, String error) {
		boolean changed = !status.equals(c.getMonitStatus());
		c.setMonitStatus(status);
		c.setMonitLastCheck(ZonedDateTime.now());
		c.setMonitLastError(error);
		clusterRepository.save(c);
		if (changed) {
			log.info("Cluster '{}' monit status -> {}{}", c.getId(), status,
					error != null ? " (" + error + ")" : "");
		}
	}

	private String truncate(String s, int max) {
		if (s == null) return null;
		return s.length() <= max ? s : s.substring(0, max);
	}
}
