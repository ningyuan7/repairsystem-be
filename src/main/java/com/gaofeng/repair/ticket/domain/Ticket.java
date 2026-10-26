package com.gaofeng.repair.ticket.domain;
import lombok.Data;
import java.time.Instant;
@Data
public class Ticket {
    private Long id;
    private String ticketNo;
    private Long storeId;
    private String storeName;
    private Long reporterId;
    private Long assetId;
    private Long replacementAssetId;
    private Long categoryId;
    private String categoryName;
    private String title;
    private String description;
    private String facilitySnapshot;
    private String contactName;
    private String contactPhone;
    private String priority;
    private TicketStatus status;
    private String resolutionPlan;
    private boolean procurementRequired;
    private Long assigneeId;
    private String assigneeName;
    private Instant assignedAt;
    private Instant acceptedAt;
    private Instant firstStartedAt;
    private Instant completedRepairAt;
    private Instant submittedForAcceptanceAt;
    private Instant completedAt;
    private Instant submittedAt;
    private int version;
}
