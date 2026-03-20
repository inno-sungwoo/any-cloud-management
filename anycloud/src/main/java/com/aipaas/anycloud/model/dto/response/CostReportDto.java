package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "GPU 비용 리포트 DTO")
public class CostReportDto {

    @Schema(description = "조회 기간", example = "7d")
    private String period;

    @Schema(description = "일별 비용 항목 목록")
    private List<DailyEntry> entries;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "일별 비용 항목")
    public static class DailyEntry {

        @Schema(description = "날짜", example = "2026-03-20")
        private String date;

        @Schema(description = "네임스페이스", example = "ml-training")
        private String namespace;

        @Schema(description = "평균 GPU 사용률 (%)", example = "65.3")
        private double avgGpuUtil;

        @Schema(description = "비용 (KRW)", example = "28800")
        private long costKrw;
    }
}
