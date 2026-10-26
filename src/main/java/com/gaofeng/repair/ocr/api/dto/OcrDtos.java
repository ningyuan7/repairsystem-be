package com.gaofeng.repair.ocr.api.dto;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class OcrDtos {
    private OcrDtos() {}
    public record Create(@NotBlank @Pattern(regexp="[a-fA-F0-9-]{36}") String attachmentId) {}
    public record Retry(@NotNull @Min(0) Integer expectedVersion) {}
    public record Point(int x,int y) {}
    public record Line(String text,Double confidence,List<Point> polygon) {}
    public record Recognition(List<Line> lines,String requestId,String confidenceUnit) {}
    public record Field<T>(T value,String evidence,List<Integer> lineIndexes,Double confidence) {}
    public record Item(Field<String> name,Field<String> brand,Field<String> model,Field<Integer> quantity,Field<String> unitPrice) {}
    public record Candidates(Field<String> orderNumber,Field<String> supplier,Field<String> orderedAt,List<Item> items,Field<String> adjustmentAmount,Field<String> sourceTotal) {}
    public record Extraction(Candidates candidates,List<Line> lines,List<String> warnings,String confidenceUnit) {}
    public record JobView(String jobId,Long purchaseOrderId,String attachmentId,String status,int attempts,int version,
                          Extraction result,String errorCode,String provider,String providerRequestId,Instant createdAt) {}
    public record Availability(boolean configured,String provider) {}
}
