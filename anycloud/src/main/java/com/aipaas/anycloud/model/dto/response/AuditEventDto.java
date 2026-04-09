package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "감사 이벤트 DTO")
public class AuditEventDto {

    @Schema(description = "이벤트 사유", example = "Pulled")
    private String reason;

    @Schema(description = "이벤트 메시지", example = "Successfully pulled image nginx:latest")
    private String message;

    @Schema(description = "관련 오브젝트", example = "Pod/my-nginx-pod")
    private String involvedObject;

    @Schema(description = "네임스페이스", example = "default")
    private String namespace;

    @Schema(description = "이벤트 유형", example = "Normal")
    private String type;

    @Schema(description = "최초 발생 시간", example = "2026-03-20T10:00:00Z")
    private String firstTimestamp;

    @Schema(description = "마지막 발생 시간", example = "2026-03-20T10:30:00Z")
    private String lastTimestamp;
}
