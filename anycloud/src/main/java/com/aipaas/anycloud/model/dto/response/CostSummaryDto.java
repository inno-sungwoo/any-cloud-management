package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "GPU 비용 요약 DTO")
public class CostSummaryDto {

    @Schema(description = "총 GPU 비용 (KRW)", example = "1440000")
    private long totalGpuCostKrw;

    @Schema(description = "팀(네임스페이스)별 비용 목록")
    private List<TeamCost> teams;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "팀별 GPU 비용")
    public static class TeamCost {

        @Schema(description = "네임스페이스", example = "ml-training")
        private String namespace;

        @Schema(description = "GPU 수", example = "2")
        private int gpuCount;

        @Schema(description = "비용 (KRW)", example = "720000")
        private long costKrw;
    }
}
