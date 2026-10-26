package com.gaofeng.repair.procurement.domain;
import lombok.Data;
import java.time.Instant;
import java.math.BigDecimal;
@Data
public class PurchaseOrder {
    private Long id;
    private Long ticketId;
    private PurchaseStatus status;
    private String orderNumber;
    private String supplier;
    private Instant orderedAt;
    private String currency;
    private BigDecimal adjustmentAmount;
    private BigDecimal confirmedTotal;
    private Instant receivedAt;
    private int version;
}
