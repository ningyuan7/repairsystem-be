package com.gaofeng.repair.report.mapper;
import com.gaofeng.repair.report.api.dto.ReportDtos.*;
import com.gaofeng.repair.report.domain.*;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
public interface ReportMapper {
    ReportTotals tickets(Period period);
    ReportTotals purchases(Period period);
    ReportTotals costs(Period period);
    List<ReportGroup> stores(@Param("period") Period period,@Param("offset") int offset,@Param("size") int size);
    long storeCount(Long storeId);
    List<ReportGroup> engineers(@Param("period") Period period,@Param("offset") int offset,@Param("size") int size);
    long engineerCount();
    List<EngineerDispatchRow> engineerDispatch(@Param("offset") int offset,@Param("size") int size);
    List<DispatchTicket> dispatchTickets(@Param("engineerId") long engineerId,@Param("status") String status,@Param("offset") int offset,@Param("size") int size);
    long dispatchTicketCount(@Param("engineerId") long engineerId,@Param("status") String status);
    List<FacilityFault> faults(Period period);
    List<PurchaseRank> purchaseRanks(Period period);
    List<StatusCount> statuses(@Param("actor") long actor,@Param("role") String role);
    List<Task> tasks(@Param("actor") long actor,@Param("role") String role);
    long overdue(@Param("actor") long actor,@Param("role") String role,@Param("reviewHours") long reviewHours,@Param("acceptHours") long acceptHours,@Param("repairHours") long repairHours,@Param("acceptanceHours") long acceptanceHours);
    List<ExportRow> export(Period period);
}
