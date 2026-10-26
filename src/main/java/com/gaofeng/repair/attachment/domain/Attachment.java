package com.gaofeng.repair.attachment.domain;
import lombok.Data;
import java.time.Instant;
@Data
public class Attachment {
    private String id;
    private Long uploaderId;
    private Long storeId;
    private Long ticketId;
    private Long purchaseOrderId;
    private String purpose;
    private String storageBackend;
    private String objectKey;
    private String contentType;
    private int size;
    private String sha256;
    private String status;
    private Instant expiresAt;
    private Long boundEventId;
}
