package com.gaofeng.repair.report.domain;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
@Data public class ExportRow {
    private String ticketNo,title,storeName,status,facilityName,originalBrand,originalModel,assigneeName;
    private Instant submittedAt,completedAt;private BigDecimal purchaseAmount,totalAmount;
}
