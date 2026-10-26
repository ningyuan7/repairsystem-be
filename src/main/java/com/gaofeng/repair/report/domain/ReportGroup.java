package com.gaofeng.repair.report.domain;
import lombok.Data;
import java.math.BigDecimal;
@Data public class ReportGroup {
    private Long id;private String name,region;
    private long submitted,completed,open,pendingCosts,returned;
    private BigDecimal purchaseAmount,completedCostAmount,averageRepairSeconds;
}
