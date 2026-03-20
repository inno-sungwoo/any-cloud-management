package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm 릴리즈 상태 DTO")
public class ReleaseStatusDto {

    @Schema(description = "릴리즈 이름", example = "my-nginx")
    private String name;

    @Schema(description = "네임스페이스", example = "default")
    private String namespace;

    @Schema(description = "릴리즈 상태", example = "deployed")
    private String status;

    @Schema(description = "차트 이름", example = "nginx")
    private String chart;

    @Schema(description = "차트 버전", example = "15.4.4")
    private String chartVersion;

    @Schema(description = "마지막 업데이트 시간", example = "2026-03-20T10:30:00Z")
    private String updated;

    @Schema(description = "GPU 사용률 (%)", example = "72.5")
    private Double gpuUtil;
}
