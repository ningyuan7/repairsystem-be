package com.gaofeng.repair.notification.api.dto;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class NotificationDtos {
    private NotificationDtos() {}
    public record Read(@NotEmpty @Size(max=100) List<@NotBlank @Pattern(regexp="[1-9][0-9]{0,17}") String> ids) {}
    public record View(Long id,Long ticketId,String type,String message,boolean read,Instant createdAt) {}
    public record Feed(List<View> items,long unread,int page,int size,long total) {}
    public record ReadResult(int updated,long unread) {}
}
