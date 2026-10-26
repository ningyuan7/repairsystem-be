package com.gaofeng.repair.asset.domain;
import lombok.Data;
@Data
public class Category {
    private Long id;
    private String code;
    private String name;
    private String status;
    private int version;
}
