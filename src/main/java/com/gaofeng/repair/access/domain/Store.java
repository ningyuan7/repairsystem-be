package com.gaofeng.repair.access.domain;
import lombok.Data;
import java.time.Instant;
@Data
public class Store {
    private Long id;
    private String code;
    private String name;
    private String region;
    private String address;
    private String status;
    private int version;
    private Instant createdAt;
}
