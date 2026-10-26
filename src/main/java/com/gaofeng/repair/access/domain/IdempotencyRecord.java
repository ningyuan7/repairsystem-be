package com.gaofeng.repair.access.domain;
import lombok.Data;
@Data
public class IdempotencyRecord {
    private String requestHash;
    private String responseJson;
    private int httpStatus;
    private int authVersion;
}
