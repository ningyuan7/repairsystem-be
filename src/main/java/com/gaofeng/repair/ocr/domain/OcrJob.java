package com.gaofeng.repair.ocr.domain;
import lombok.Data;
import java.time.Instant;
@Data
public class OcrJob {
    private String id,sourceAttachmentId,sourceSha256,status,provider,leaseToken,result,errorCode,providerRequestId;
    private Long purchaseOrderId,requesterId;
    private int attempts,version;
    private Instant nextRunAt,leaseUntil,createdAt;
}
