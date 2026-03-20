package com.aipaas.anycloud.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "인증 API (개발용)")
public class AuthController {

    @PostMapping("/login")
    @Operation(summary = "로그인 (개발용)", description = "테스트용 로그인 - 아무 계정으로 로그인 가능")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String memberId = body.getOrDefault("member_id", "admin");
        log.info("Dev login: {}", memberId);

        // 간단한 Base64 JWT 형태 토큰 생성 (실제 JWT 서명 없음, 테스트용)
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + memberId + "\",\"role\":\"admin\",\"iat\":" + System.currentTimeMillis() / 1000 + "}").getBytes());
        String token = header + "." + payload + ".dev-signature";

        return ResponseEntity.ok(Map.of(
                "access_token", token,
                "refresh_token", "dev-refresh-" + System.currentTimeMillis(),
                "token_type", "bearer",
                "expires_in", 86400
        ));
    }

    @PostMapping("/refresh")
    @Operation(summary = "토큰 갱신 (개발용)")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody Map<String, String> body) {
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"admin\",\"role\":\"admin\",\"iat\":" + System.currentTimeMillis() / 1000 + "}").getBytes());
        String token = header + "." + payload + ".dev-signature";

        return ResponseEntity.ok(Map.of(
                "access_token", token,
                "refresh_token", "dev-refresh-" + System.currentTimeMillis(),
                "token_type", "bearer",
                "expires_in", 86400
        ));
    }
}
