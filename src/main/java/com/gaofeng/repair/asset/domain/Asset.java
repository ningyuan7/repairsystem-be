package com.gaofeng.repair.asset.domain;
import lombok.Data;
@Data
public class Asset {
    private Long id;
    private Long storeId;
    private String assetCode;
    private String name;
    private String zone;
    private String brand;
    private String model;
    private String serialNumber;
    private String lifecycle;
    private String healthStatus;
    private Long sourcePurchaseItemId;
    private Long replacedByAssetId;
    private int version;
}
