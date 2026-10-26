package com.gaofeng.repair.access.domain;
import lombok.Data;
@Data
public class Member {
    private Long userId;
    private Role role;
    private String status;
    private String displayName;
    private String contactPhone;
    private String email;
    private int version;
    private int authVersion;
}
