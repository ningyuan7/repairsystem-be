package com.gaofeng.repair.access.domain;
import java.time.Instant;
import lombok.Data;
@Data
public class LoginSession {
    private String id;
    private Long userId;
    private int authVersion;
    private Instant expiresAt;
    private Instant revokedAt;
}
