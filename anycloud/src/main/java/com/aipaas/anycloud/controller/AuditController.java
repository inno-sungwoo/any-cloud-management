package com.aipaas.anycloud.controller;

import com.aipaas.anycloud.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/audit")
@Tag(name = "Audit", description = "감사 로그 API")
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/events")
    @Operation(summary = "네임스페이스 이벤트 조회", description = "Kubernetes 이벤트 목록 조회")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "이벤트 조회 성공"),
        @ApiResponse(responseCode = "400", description = "클러스터를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Object> getEvents(
        @Parameter(description = "클러스터 이름", required = true, example = "openstack")
        @RequestParam("cluster") String clusterName,
        @Parameter(description = "네임스페이스", required = true, example = "default")
        @RequestParam("namespace") String namespace) {
        log.info("retrieve audit events for cluster: {}, namespace: {}", clusterName, namespace);
        return new ResponseEntity<>(auditService.getEvents(clusterName, namespace), new HttpHeaders(), HttpStatus.OK);
    }
}
