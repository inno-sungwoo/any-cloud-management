package com.aipaas.anycloud.model.dto.request;

import lombok.Data;

@Data
public class GpuReservationRequestDto {
    private String releaseName;
    private String namespace;
    private String clusterId;
    private Integer gpuCount;
    private Integer estimatedMinutes;
    private Integer unitPriceKrw;
    private Integer estimatedCostKrw;
}
