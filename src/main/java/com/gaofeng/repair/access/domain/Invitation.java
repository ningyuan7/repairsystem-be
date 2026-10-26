package com.gaofeng.repair.access.domain;
import lombok.Data;
import java.time.Instant;
@Data
public class Invitation {
    private String id;
    private String email;
    private Role role;
    private String storeIds;
    private String tokenHash;
    private Instant expiresAt;
    private Instant consumedAt;
    private Long inviterId;
}
