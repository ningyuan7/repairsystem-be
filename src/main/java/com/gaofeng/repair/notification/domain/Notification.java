package com.gaofeng.repair.notification.domain;
import lombok.Data;
import java.time.Instant;
@Data public class Notification {
    private Long id,recipientId,eventId,ticketId;
    private String type,message;
    private boolean read,ticketAccessible;
    private Instant createdAt,readAt;
}
