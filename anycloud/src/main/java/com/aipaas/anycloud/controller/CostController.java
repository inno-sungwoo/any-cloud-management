package com.aipaas.anycloud.controller;

import com.aipaas.anycloud.model.dto.request.GpuReservationRequestDto;
import com.aipaas.anycloud.service.CostService;
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
@RequestMapping("/cost")
@Tag(name = "Cost", description = "GPU 비용 최적화 API")
public class CostController {

    private final CostService costService;

    @GetMapping("/summary")
    @Operation(summary = "비용 요약 조회", description = "클러스터의 네임스페이스별 GPU 비용 요약")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "비용 요약 조회 성공"),
        @ApiResponse(responseCode = "400", description = "cluster를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Object> summary(
        @Parameter(description = "클러스터 이름", required = true, example = "openstack")
        @RequestParam("cluster") String clusterName) {
        log.info("retrieve cost summary for cluster: {}", clusterName);
        return new ResponseEntity<>(costService.summary(clusterName), new HttpHeaders(), HttpStatus.OK);
    }

    @GetMapping("/idle-warnings")
    @Operation(summary = "유휴 GPU 경고 조회", description = "AlertManager에서 GPU 유휴 관련 경고 조회")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "유휴 경고 조회 성공"),
        @ApiResponse(responseCode = "400", description = "cluster를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Object> idleWarnings(
        @Parameter(description = "클러스터 이름", required = true, example = "openstack")
        @RequestParam("cluster") String clusterName) {
        log.info("retrieve idle warnings for cluster: {}", clusterName);
        return new ResponseEntity<>(costService.idleWarnings(clusterName), new HttpHeaders(), HttpStatus.OK);
    }

    @GetMapping("/report")
    @Operation(summary = "비용 리포트 조회", description = "최근 7일간 네임스페이스별 GPU 사용량 및 비용 리포트")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "리포트 조회 성공"),
        @ApiResponse(responseCode = "400", description = "cluster를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Object> report(
        @Parameter(description = "클러스터 이름", required = true, example = "openstack")
        @RequestParam("cluster") String clusterName) {
        log.info("retrieve cost report for cluster: {}", clusterName);
        return new ResponseEntity<>(costService.report(clusterName), new HttpHeaders(), HttpStatus.OK);
    }

    @GetMapping("/estimate")
    @Operation(summary = "비용 예측", description = "GPU 수와 시간을 기반으로 예상 비용 계산")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "비용 예측 성공"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<Object> estimate(
        @Parameter(description = "GPU 수", required = true, example = "2")
        @RequestParam("gpuCount") int gpuCount,
        @Parameter(description = "사용 시간", required = true, example = "24")
        @RequestParam("hours") int hours) {
        log.info("estimate cost for gpuCount: {}, hours: {}", gpuCount, hours);
        return new ResponseEntity<>(costService.estimate(gpuCount, hours), new HttpHeaders(), HttpStatus.OK);
    }

    // --- GPU Reservation API ---

    @GetMapping("/reservations")
    @Operation(summary = "GPU 예약 목록", description = "클러스터의 GPU 사용 예약 목록 조회")
    public ResponseEntity<Object> getReservations(
        @RequestParam("cluster") String clusterName) {
        log.info("retrieve gpu reservations for cluster: {}", clusterName);
        return new ResponseEntity<>(costService.getReservations(clusterName), new HttpHeaders(), HttpStatus.OK);
    }

    @PostMapping("/reservations")
    @Operation(summary = "GPU 예약 등록", description = "배포 시 GPU 사용 예약 등록")
    public ResponseEntity<Object> createReservation(@RequestBody GpuReservationRequestDto dto) {
        log.info("create gpu reservation: {}", dto.getReleaseName());
        return new ResponseEntity<>(costService.createReservation(dto), new HttpHeaders(), HttpStatus.CREATED);
    }

    @PutMapping("/reservations/{releaseName}/extend")
    @Operation(summary = "GPU 예약 연장", description = "GPU 사용 예약 시간 연장")
    public ResponseEntity<Object> extendReservation(
        @PathVariable String releaseName,
        @RequestParam("cluster") String clusterName,
        @RequestParam("minutes") int additionalMinutes) {
        log.info("extend gpu reservation: {} +{}min", releaseName, additionalMinutes);
        return new ResponseEntity<>(costService.extendReservation(releaseName, clusterName, additionalMinutes), new HttpHeaders(), HttpStatus.OK);
    }

    @DeleteMapping("/reservations/{releaseName}")
    @Operation(summary = "GPU 예약 삭제", description = "GPU 사용 예약 삭제")
    public ResponseEntity<Object> deleteReservation(
        @PathVariable String releaseName,
        @RequestParam("cluster") String clusterName) {
        log.info("delete gpu reservation: {}", releaseName);
        costService.deleteReservation(releaseName, clusterName);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
