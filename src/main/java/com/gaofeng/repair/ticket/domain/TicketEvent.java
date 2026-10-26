package com.gaofeng.repair.ticket.domain;
import lombok.Data;
import java.time.Instant;
@Data
public class TicketEvent {
    private Long id;
    private Long ticketId;
    private int ticketVersion;
    private String action;
    private String fromStatus;
    private String toStatus;
    private Long actorId;
    private String actorRole;
    private String actorName;
    private String visibility;
    private String note;
    private String payload;
    private Instant createdAt;
}
