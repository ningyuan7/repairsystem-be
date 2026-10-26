package com.gaofeng.repair.report.domain;
import lombok.Data;
import java.math.BigDecimal;
@Data public class ReportTotals {
    private long submitted,cohortCompleted,completed,open,pendingCosts,confirmedCosts;
    private BigDecimal averageRepairSeconds,averageCycleSeconds,purchaseAmount,completedCostAmount,averageArrivalSeconds;
}
