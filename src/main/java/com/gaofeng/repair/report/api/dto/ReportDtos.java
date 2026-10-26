package com.gaofeng.repair.report.api.dto;
import com.gaofeng.repair.access.api.dto.AccessDtos.Page;
import java.time.Instant;
import java.util.List;
public final class ReportDtos {
    private ReportDtos() {}
    public record Period(Instant from,Instant to,Long storeId) {}
    public record Overview(Period period,Instant generatedAt,long submitted,long cohortCompleted,long completed,long currentOpen,
                           String completionRate,String averageRepairHours,String averageCycleHours,String averageArrivalHours,
                           String purchaseAmount,String confirmedCompletedCostAmount,long confirmedCostCount,long pendingCostCount) {}
    public record StoreRow(Long id,String name,String region,long submitted,long completed,long currentOpen,long pendingCostCount,String purchaseAmount,String confirmedCompletedCostAmount,String averageRepairHours) {}
    public record EngineerRow(Long id,String name,long completed,long currentOpen,long returned,String averageRepairHours) {}
    public record FacilityFault(String brand,String model,long count) {}
    public record PurchaseRank(String name,String brand,String model,long quantity,String amount) {}
    public record StoreReport(Period period,Page<StoreRow> stores,List<FacilityFault> originalFacilityFaults,List<PurchaseRank> purchasedItems) {}
    public record EngineerDispatchRow(Long id,String name,String contactPhone,String memberStatus,
                                      long assigned,long accepted,long inProgress,long waitingAcceptance,long currentOpen) {}
    public record DispatchTicket(Long id,String ticketNo,String title,String storeName,String priority,String status,Instant assignedAt) {}
    public record StatusCount(String status,long count) {}
    public record Task(Long id,String ticketNo,String title,String storeName,String status,String priority,Instant submittedAt) {}
    public record Dashboard(String role,Instant generatedAt,List<StatusCount> statuses,List<Task> tasks,long overdueCount,long unreadCount) {}
}
