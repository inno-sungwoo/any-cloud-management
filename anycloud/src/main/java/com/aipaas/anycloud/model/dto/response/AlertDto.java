package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "알림 DTO")
public class AlertDto {

    @Schema(description = "알림 이름", example = "HighGpuUtilization")
    private String alertName;

    @Schema(description = "심각도", example = "warning")
    private String severity;

    @Schema(description = "네임스페이스", example = "gpu-workload")
    private String namespace;

    @Schema(description = "알림 메시지", example = "GPU utilization exceeds 90%")
    private String message;

    @Schema(description = "알림 시작 시간", example = "2026-03-20T10:30:00Z")
    private String startsAt;

    @Schema(description = "알림 상태", example = "firing")
    private String status;
}
