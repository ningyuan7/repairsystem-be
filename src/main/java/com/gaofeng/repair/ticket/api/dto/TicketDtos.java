package com.gaofeng.repair.ticket.api.dto;

import com.gaofeng.repair.attachment.api.dto.AttachmentDtos.AttachmentView;
import com.gaofeng.repair.ticket.domain.TicketStatus;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class TicketDtos {
    private TicketDtos() {}
    public enum Priority { NORMAL, URGENT }
    public enum ResolutionPlan { REPAIR, PART_REPAIR, REPLACEMENT }
    public record Snapshot(String storeName,String facilityName,String zone,String assetCode,String brand,String model,String serialNumber) {}
    public record CreateTicket(@NotBlank @Pattern(regexp="[1-9][0-9]{0,17}") String storeId,
                                @Pattern(regexp="[1-9][0-9]{0,17}") String assetId,
                                @NotBlank @Pattern(regexp="[1-9][0-9]{0,17}") String categoryId,
                                @NotBlank @Size(max=100) String title,@NotBlank @Size(max=2000) String description,
                                @Size(max=128) String facilityName,@Size(max=128) String zone,@Size(max=128) String brand,@Size(max=128) String model,
                                @NotBlank @Size(max=64) String contactName,@NotBlank @Size(max=32) String contactPhone,
                                @NotNull Priority priority,@NotNull @Size(max=6) List<@NotBlank @Pattern(regexp="[a-fA-F0-9-]{36}") String> attachmentIds) {}
    public record Command(@NotNull @Min(0) Integer expectedVersion,@Size(max=500) String reason,@Size(max=1000) String note,
                           Boolean procurementRequired,ResolutionPlan resolutionPlan,Priority priority,
                           @Pattern(regexp="[1-9][0-9]{0,17}") String engineerId,@Pattern(regexp="[1-9][0-9]{0,17}") String assetId,
                           Instant scheduledAt,@Size(max=2000) String resultNote,@Size(max=128) String replacementSerialNumber,
                           @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,2})?") String laborSuggestedAmount,
                           @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,2})?") String otherSuggestedAmount,
                           @Size(max=6) List<@NotBlank @Pattern(regexp="[a-fA-F0-9-]{36}") String> attachmentIds) {}
    public record CommandResult(Long ticketId,String ticketNo,TicketStatus status,int version) {}
    public record TicketView(Long id,String ticketNo,Long storeId,String storeName,Long assetId,Long replacementAssetId,
                             Long categoryId,String categoryName,String title,String description,Snapshot facilitySnapshot,
                             String contactName,String contactPhone,String priority,TicketStatus status,String resolutionPlan,
                             boolean procurementRequired,Long assigneeId,String assigneeName,Instant submittedAt,Instant assignedAt,
                             Instant acceptedAt,Instant firstStartedAt,Instant completedAt,int version,boolean materialsReady,
                             String costStatus,List<String> allowedActions,List<AttachmentView> attachments) {}
    public record EventPayload(int payloadVersion,Snapshot facilitySnapshot,Instant scheduledAt,String resultNote,
                               String replacementSerialNumber,String laborSuggestedAmount,String otherSuggestedAmount) {
        public static EventPayload empty() { return new EventPayload(1,null,null,null,null,null,null); }
        public EventPayload participantView() { return new EventPayload(payloadVersion,facilitySnapshot,scheduledAt,resultNote,replacementSerialNumber,null,null); }
    }
    public record EventView(Long id,int ticketVersion,String action,String fromStatus,String toStatus,String actorName,
                            String actorRole,String note,EventPayload payload,Instant createdAt,List<AttachmentView> attachments) {}
    public record Events(List<EventView> items,Long nextCursor) {}
}
