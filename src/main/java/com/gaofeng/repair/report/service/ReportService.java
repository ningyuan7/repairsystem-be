package com.gaofeng.repair.report.service;
import com.gaofeng.repair.access.api.dto.AccessDtos.Page;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.repair.access.service.*;
import com.gaofeng.repair.common.RepairException;
import com.gaofeng.repair.notification.mapper.NotificationMapper;
import com.gaofeng.repair.report.api.dto.ReportDtos.*;
import com.gaofeng.repair.report.domain.ExportRow;
import com.gaofeng.repair.report.mapper.ReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.Instant;
import java.util.List;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ReportService {
    private final ReportMapper mapper; private final RepairAccess access; private final AccessMapper directory; private final NotificationMapper notifications;
    private final com.gaofeng.repair.cache.EngineerReadCache readCache;
    private final com.gaofeng.repair.cache.mapper.EngineerCacheMapper cacheDirectory;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    public record EngineerSummary(Instant generatedAt,List<StatusCount> statuses,List<Task> tasks) {}
    @Value("${repair.sla.review-hours:4}") private long reviewHours;
    @Value("${repair.sla.accept-hours:8}") private long acceptHours;
    @Value("${repair.sla.repair-hours:72}") private long repairHours;
    @Value("${repair.sla.acceptance-hours:24}") private long acceptanceHours;
    public Period period(long actor,Instant from,Instant to,Long storeId) { access.hq(actor); if(from==null || to==null || !from.isBefore(to)) throw RepairException.invalid("开始时间必须早于结束时间，查询范围为左闭右开"); if(storeId!=null && directory.store(storeId)==null) throw RepairException.missing(); return new Period(from,to,storeId); }
    public Overview overview(long actor,Period period) {
        access.hq(actor);var tickets=mapper.tickets(period);var purchases=mapper.purchases(period);var costs=mapper.costs(period);
        String ratio=tickets.getSubmitted()==0?null:BigDecimal.valueOf(tickets.getCohortCompleted()).divide(BigDecimal.valueOf(tickets.getSubmitted()),6,RoundingMode.HALF_UP).toPlainString();
        return new Overview(period,Instant.now(),tickets.getSubmitted(),tickets.getCohortCompleted(),tickets.getCompleted(),tickets.getOpen(),ratio,hours(tickets.getAverageRepairSeconds()),hours(tickets.getAverageCycleSeconds()),hours(purchases.getAverageArrivalSeconds()),money(purchases.getPurchaseAmount()),money(costs.getCompletedCostAmount()),costs.getConfirmedCosts(),costs.getPendingCosts());
    }
    public StoreReport stores(long actor,Period period,int page,int size) { access.hq(actor);AccessService.checkPage(page,size);return new StoreReport(period,new Page<>(mapper.stores(period,(page-1)*size,size).stream().map(row->new StoreRow(row.getId(),row.getName(),row.getRegion(),row.getSubmitted(),row.getCompleted(),row.getOpen(),row.getPendingCosts(),money(row.getPurchaseAmount()),money(row.getCompletedCostAmount()),hours(row.getAverageRepairSeconds()))).toList(),page,size,mapper.storeCount(period.storeId())),mapper.faults(period),mapper.purchaseRanks(period)); }
    public Page<EngineerRow> engineers(long actor,Period period,int page,int size) { access.hq(actor);AccessService.checkPage(page,size);return new Page<>(mapper.engineers(period,(page-1)*size,size).stream().map(row->new EngineerRow(row.getId(),row.getName(),row.getCompleted(),row.getOpen(),row.getReturned(),hours(row.getAverageRepairSeconds()))).toList(),page,size,mapper.engineerCount()); }
    public Page<EngineerDispatchRow> engineerDispatch(long actor,int page,int size) {
        access.hq(actor);AccessService.checkPage(page,size);
        return new Page<>(mapper.engineerDispatch((page-1)*size,size),page,size,mapper.engineerCount());
    }
    public Page<DispatchTicket> dispatchTickets(long actor,long engineerId,String status,int page,int size) {
        access.hq(actor);AccessService.checkPage(page,size);
        var member=directory.member(engineerId);
        if(member==null||member.getRole()!=com.gaofeng.repair.access.domain.Role.ENGINEER)throw RepairException.missing();
        if(status!=null&&!java.util.Set.of("ASSIGNED","ACCEPTED","IN_PROGRESS","WAITING_ACCEPTANCE").contains(status))
            throw RepairException.invalid("只能查询工程师当前未结工单状态");
        return new Page<>(mapper.dispatchTickets(engineerId,status,(page-1)*size,size),page,size,mapper.dispatchTicketCount(engineerId,status));
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Dashboard dashboard(long actor) {
        var member=access.active(actor);String role=member.getRole().name();
        java.util.function.Supplier<EngineerSummary> load=()->new EngineerSummary(Instant.now(),mapper.statuses(actor,role),mapper.tasks(actor,role));
        EngineerSummary summary="ENGINEER".equals(role)?readCache.read(actor,member.getAuthVersion(),"dashboard",
            objectMapper.constructType(EngineerSummary.class),load,
            result->{var ids=result.tasks().stream().map(Task::id).toList();return ids.isEmpty()||cacheDirectory.owned(actor,ids)==ids.size();}):load.get();
        return new Dashboard(role,summary.generatedAt(),summary.statuses(),summary.tasks(),mapper.overdue(actor,role,reviewHours,acceptHours,repairHours,acceptanceHours),notifications.count(actor,role,true));
    }
    public List<ExportRow> export(long actor,Period period) { access.hq(actor);var rows=mapper.export(period);if(rows.size()>10000)throw new RepairException(422,"EXPORT_LIMIT_EXCEEDED","导出最多 10,000 行，请缩小时间或门店范围");return rows; }
    public static String money(BigDecimal value){return value==null?"0.00":value.setScale(2,RoundingMode.UNNECESSARY).toPlainString();}
    private static String hours(BigDecimal seconds){return seconds==null?null:seconds.divide(BigDecimal.valueOf(3600),3,RoundingMode.HALF_UP).toPlainString();}
}
