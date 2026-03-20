package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "GPU 비용 예측 DTO")
public class CostEstimateDto {

    @Schema(description = "GPU 수", example = "2")
    private int gpuCount;

    @Schema(description = "사용 시간", example = "24")
    private int hours;

    @Schema(description = "시간당 단가 (KRW)", example = "1200")
    private long unitPriceKrw;

    @Schema(description = "총 예상 비용 (KRW)", example = "57600")
    private long totalCostKrw;
}
