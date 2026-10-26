package com.gaofeng.repair.procurement.domain;
import lombok.Data;
import java.time.Instant;
import java.math.BigDecimal;
@Data
public class PurchaseItem {
    private Long id;
    private Long purchaseOrderId;
    private String purpose;
    private String name;
    private String brand;
    private String model;
    private int quantity;
    private BigDecimal unitPrice;
    private int receivedQuantity;
    private boolean specConfirmed;
    private Long specConfirmedBy;
    private Instant specConfirmedAt;
    private String sourceOcrJobId;
    private Integer sourceCandidateIndex;
}
