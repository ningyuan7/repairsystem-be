package com.gaofeng.repair.attachment.api.dto;
import java.time.Instant;
public final class AttachmentDtos {
    private AttachmentDtos() {}
    public enum Purpose { FAULT, ORDER_SCREENSHOT, WORK_RECORD, RESULT, RECEIPT, AVATAR }
    public record AttachmentView(String id,String purpose,String status,int size,String contentType,Instant expiresAt) {}
    public record Download(String url,int expiresIn,boolean needAuthorization) {}
}
