package com.gaofeng.repair.notification.service;
import com.gaofeng.repair.asset.mapper.AssetMapper;
import com.gaofeng.repair.notification.mapper.NotificationMapper;
import com.gaofeng.repair.ticket.domain.*;
import com.gaofeng.repair.ticket.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
@Component @RequiredArgsConstructor
public class SlaScanner {
    private final NotificationMapper mapper; private final TicketMapper tickets; private final AssetMapper assets; private final NotificationService notifications; private final PlatformTransactionManager transactions;
    @Value("${repair.workers.enabled:true}") private boolean enabled;
    @Value("${repair.sla.review-hours:4}") private long reviewHours;
    @Value("${repair.sla.accept-hours:8}") private long acceptHours;
    @Value("${repair.sla.repair-hours:72}") private long repairHours;
    @Value("${repair.sla.acceptance-hours:24}") private long acceptanceHours;
    private long cursor;
    @Scheduled(fixedDelayString="${repair.sla.poll-ms:60000}",initialDelayString="${repair.sla.poll-ms:60000}") public void poll() { if(enabled) scan(); }
    public int scan() { var ids=mapper.slaCandidates(cursor); if(ids.isEmpty()) { cursor=0; return 0; } int count=0;for(long id:ids) { count+=markOne(id);cursor=id;}return count; }
    public int markOne(long id) {
        TransactionTemplate transaction=new TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(tx->{
            Ticket peek=tickets.ticket(id);if(peek==null)return 0;
            List<Long> assetIds=new ArrayList<>();if(peek.getAssetId()!=null)assetIds.add(peek.getAssetId());if(peek.getReplacementAssetId()!=null)assetIds.add(peek.getReplacementAssetId());assetIds.stream().distinct().sorted().forEach(assets::lockAsset);
            Ticket ticket=tickets.lockTicket(id);if(ticket.getVersion()!=peek.getVersion())return 0;
            String kind;Instant start;long hours;String suffix="";
            switch(ticket.getStatus()) {
                case SUBMITTED -> {kind="REVIEW";start=ticket.getSubmittedAt();hours=reviewHours;}
                case ASSIGNED -> {kind="ACCEPT";start=ticket.getAssignedAt();hours=acceptHours;}
                case IN_PROGRESS -> {kind="REPAIR";start=ticket.getFirstStartedAt();hours=repairHours;suffix=":"+ticket.getAssigneeId();}
                case WAITING_ACCEPTANCE -> {kind="ACCEPTANCE";start=ticket.getSubmittedForAcceptanceAt();hours=acceptanceHours;}
                default -> {return 0;}
            }
            if(start==null || hours<1)return 0;Instant due=start.plus(Duration.ofHours(hours));String cycle=start.toString()+suffix;
            if(due.isAfter(Instant.now()) || mapper.marked(id,kind,cycle))return 0;
            Long event=mapper.latestEvent(id);if(event==null)return 0;mapper.mark(id,kind,cycle,due);
            String action=switch(kind){case "REVIEW"->"审核";case "ACCEPT"->"工程师接单";case "REPAIR"->"维修完工";default->"门店验收";};
            for(long recipient:tickets.recipients(ticket.getStoreId(),ticket.getAssigneeId(),false)) notifications.emit(recipient,event,id,"SLA_"+kind,ticket.getTicketNo()+" · "+action+"已超过约定时限，请跟进");
            return 1;
        });
    }
}
