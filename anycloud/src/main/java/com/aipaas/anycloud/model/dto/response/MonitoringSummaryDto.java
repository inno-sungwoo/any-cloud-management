package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "모니터링 대시보드 요약 DTO")
public class MonitoringSummaryDto {

    @Schema(description = "Helm 릴리즈 수", example = "12")
    private int helmReleaseCount;

    @Schema(description = "GPU 장치 수", example = "4")
    private int gpuCount;

    @Schema(description = "평균 GPU 사용률 (%)", example = "65.3")
    private double avgGpuUtil;

    @Schema(description = "활성 알림 수", example = "2")
    private int activeAlertCount;
}
