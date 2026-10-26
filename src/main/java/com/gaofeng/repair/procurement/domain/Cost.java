package com.gaofeng.repair.procurement.domain;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
@Data
public class Cost {
    private Long ticketId;
    private BigDecimal laborAmount;
    private BigDecimal otherAmount;
    private String note;
    private Long confirmedBy;
    private Instant confirmedAt;
    private int version;
}
