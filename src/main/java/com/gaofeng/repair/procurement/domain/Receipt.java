package com.gaofeng.repair.procurement.domain;
import lombok.Data;
import java.time.Instant;
@Data
public class Receipt {
    private Long id;
    private Long purchaseOrderId;
    private Long receiverId;
    private String receiverName;
    private Instant receivedAt;
    private String note;
    private String requestKey;
    private Long eventId;
}
