package com.gaofeng.repair.procurement.api.dto;
import com.gaofeng.repair.attachment.api.dto.AttachmentDtos.AttachmentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class ProcurementDtos {
    private ProcurementDtos() {}
    public enum ItemPurpose { PART, REPLACEMENT }
    public record CreateOrder(@NotNull @Min(0) Integer expectedTicketVersion) {}
    public record ItemInput(@Pattern(regexp="[1-9][0-9]{0,17}") String id,@NotNull ItemPurpose purpose,
                             @NotBlank @Size(max=128) String name,@Size(max=128) String brand,@Size(max=128) String model,
                             @NotNull @Min(1) @Max(999) Integer quantity,@NotBlank @Pattern(regexp="[0-9]{1,10}(\\.[0-9]{1,2})?") String unitPrice,
                             boolean specConfirmed,@Min(0) @Max(99) Integer sourceCandidateIndex,
                             @Pattern(regexp="[a-fA-F0-9-]{36}") String sourceOcrJobId) {}
    public record DraftInput(@NotNull @Min(0) Integer expectedVersion,@NotNull @Min(0) Integer expectedTicketVersion,
                              @NotNull @Size(max=128) String supplier,@Size(max=128) String orderNumber,Instant orderedAt,
                              @NotNull @Pattern(regexp="CNY") String currency,@NotNull @Size(max=100) List<@NotNull @Valid ItemInput> items,
                              @NotBlank @Pattern(regexp="-?[0-9]{1,12}(\\.[0-9]{1,2})?") String adjustmentAmount,
                              @Pattern(regexp="[a-fA-F0-9-]{36}") String sourceOcrJobId) {}
    public record VersionInput(@NotNull @Min(0) Integer expectedVersion,@NotNull @Min(0) Integer expectedTicketVersion,@Size(max=500) String reason) {}
    public record ReceiptItemInput(@NotBlank @Pattern(regexp="[1-9][0-9]{0,17}") String purchaseItemId,@NotNull @Min(1) @Max(999) Integer quantity) {}
    public record ReceiptInput(@NotNull @Min(0) Integer expectedVersion,@NotNull @Min(0) Integer expectedTicketVersion,
                                @NotEmpty @Size(max=100) List<@NotNull @Valid ReceiptItemInput> items,@Size(max=500) String note,
                                @Size(max=6) List<@NotBlank @Pattern(regexp="[a-fA-F0-9-]{36}") String> attachmentIds) {}
    public record CostInput(@NotNull @Min(0) Integer expectedVersion,@Min(0) Integer expectedCostVersion,
                             @NotBlank @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,2})?") String laborAmount,
                             @NotBlank @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,2})?") String otherAmount,@NotNull @Size(max=1000) String note) {}
    public record ItemView(Long id,String purpose,String name,String brand,String model,int quantity,String unitPrice,int receivedQuantity,
                            boolean specConfirmed,Long specConfirmedBy,Instant specConfirmedAt,String sourceOcrJobId,Integer sourceCandidateIndex) {}
    public record OrderView(Long id,Long ticketId,String status,int version,int ticketVersion,String resolutionPlan,String orderNumber,String supplier,
                             Instant orderedAt,String currency,String adjustmentAmount,String confirmedTotal,Instant receivedAt,List<ItemView> items,List<AttachmentView> screenshots) {}
    public record OrderResult(Long purchaseOrderId,int purchaseOrderVersion,Long ticketId,String ticketNo,String status,int version,Long receiptId) {}
    public record MaterialLine(Long id,String purpose,String name,String brand,String model,int quantity,int receivedQuantity) {}
    public record Materials(Long ticketId,boolean procurementRequired,Long purchaseOrderId,Integer purchaseOrderVersion,int ticketVersion,
                             String status,boolean materialsReady,List<MaterialLine> items) {}
    public record ReceiptLine(Long purchaseItemId,String name,int quantity) {}
    public record ReceiptView(Long id,String receiverName,Instant receivedAt,String note,List<ReceiptLine> items,List<AttachmentView> attachments) {}
    public record CostView(Long ticketId,String status,Integer version,int ticketVersion,String purchaseAmount,String laborAmount,String otherAmount,
                            String totalAmount,String note,Long confirmedBy,Instant confirmedAt) {}
}
