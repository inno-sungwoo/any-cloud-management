package com.aipaas.anycloud.model.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GpuReservationResponseDto {
    private Long id;
    private String releaseName;
    private String namespace;
    private String clusterId;
    private Integer gpuCount;
    private Integer estimatedMinutes;
    private Integer unitPriceKrw;
    private Integer estimatedCostKrw;
    private String deployedAt;
}
