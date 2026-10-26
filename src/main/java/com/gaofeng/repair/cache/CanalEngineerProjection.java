package com.gaofeng.repair.cache;

import com.alibaba.otter.canal.client.*;
import com.alibaba.otter.canal.protocol.*;
import com.gaofeng.repair.cache.mapper.EngineerCacheMapper;
import com.gaofeng.repair.report.service.ReportService;
import com.gaofeng.repair.ticket.service.TicketService;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.net.InetSocketAddress;
import java.sql.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Binlog events invalidate derived reads; they never execute business commands. */
@Component
@ConditionalOnProperty(name={"repair.read-cache.enabled","repair.canal.enabled"},havingValue="true")
public class CanalEngineerProjection {
    private static final Logger log=LoggerFactory.getLogger(CanalEngineerProjection.class);
    private final EngineerReadCache cache;
    private final EngineerCacheMapper mapper;
    private final ReportService reports;
    private final TicketService tickets;
    private final DataSource dataSource;
    private final String host,destination,username,password;
    private final int port;
    private final Set<Long> pending=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean fullWarm=new AtomicBoolean(true);
    private final ExecutorService executor=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"repair-canal-projection");t.setDaemon(true);return t;});
    private volatile boolean running=true,leader;
    private volatile CanalConnector connector;
    private final String token=UUID.randomUUID().toString();
    private volatile String schema;
    public CanalEngineerProjection(EngineerReadCache cache,EngineerCacheMapper mapper,ReportService reports,
            TicketService tickets,DataSource dataSource,
            @Value("${repair.canal.host:127.0.0.1}") String host,@Value("${repair.canal.port:11112}") int port,
            @Value("${repair.canal.destination:repair}") String destination,
            @Value("${repair.canal.username:}") String username,@Value("${repair.canal.password:}") String password){
        this.cache=cache;this.mapper=mapper;this.reports=reports;this.tickets=tickets;this.dataSource=dataSource;
        this.host=host;this.port=port;this.destination=destination;this.username=username;this.password=password;
    }
    @EventListener(ApplicationReadyEvent.class) public void start(){executor.submit(this::consume);executor.submit(this::warm);}
    private void consume(){
        while(running){
            try{
                if(!cache.lease(token)){pause(1000);continue;}
                leader=true;
                try(var connection=dataSource.getConnection();var statement=connection.createStatement();
                        var result=statement.executeQuery("SELECT DATABASE(),@@log_bin,@@binlog_format,@@binlog_row_image")){
                    result.next();schema=result.getString(1);
                    if(!result.getBoolean(2)||!"ROW".equals(result.getString(3))||!"FULL".equals(result.getString(4)))
                        throw new IllegalStateException("Canal needs MySQL log_bin=ON, binlog_format=ROW, binlog_row_image=FULL");
                }
                cache.invalidateAll();fullWarm.set(true);
                connector=CanalConnectors.newSingleConnector(new InetSocketAddress(host,port),destination,username,password);
                connector.connect();connector.subscribe(schema+"\\.repair_.*");connector.rollback();
                log.info("Repair Canal consumer connected: destination={}",destination);
                long lastHeartbeat=0;
                while(running&&cache.renew(token)){
                    if(cache.checkServerGeneration())fullWarm.set(true);
                    long now=System.currentTimeMillis();
                    if(now-lastHeartbeat>=1000){mapper.heartbeat(now);lastHeartbeat=now;}
                    Message message=connector.getWithoutAck(512,1L,TimeUnit.SECONDS);
                    if(message.getId()==-1||message.getEntries().isEmpty())continue;
                    try{
                        long heartbeat=process(message.getEntries());
                        connector.ack(message.getId());
                        if(heartbeat>0)cache.caughtUp(heartbeat);
                    }catch(Exception e){connector.rollback(message.getId());throw e;}
                }
            }catch(Exception | LinkageError e){if(running)log.warn("Repair Canal unavailable; engineer reads use bounded short-lived cache ({})",e.getClass().getSimpleName());}
            finally{
                leader=false;
                CanalConnector previous=connector;connector=null;
                if(previous!=null)try{previous.disconnect();}catch(Exception ignored){}
                try{cache.release(token);}catch(Exception ignored){}
            }
            pause(1000);
        }
    }
    long process(List<CanalEntry.Entry> entries)throws Exception{
        Set<Long> actors=new HashSet<>(),ticketIds=new HashSet<>(),orderIds=new HashSet<>();boolean all=false;long heartbeat=0;
        for(var entry:entries){
            if(entry.getEntryType()!=CanalEntry.EntryType.ROWDATA||!schema.equals(entry.getHeader().getSchemaName()))continue;
            String table=entry.getHeader().getTableName();
            var change=CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            if(change.getIsDdl()){all=true;continue;}
            if(table.equals("repair_cdc_heartbeat")){
                for(var row:change.getRowDatasList())for(var column:row.getAfterColumnsList())
                    if(column.getName().equals("sent_at")&&!column.getIsNull())heartbeat=Math.max(heartbeat,Long.parseLong(column.getValue()));
            }else if(table.equals("repair_tickets")){
                for(var row:change.getRowDatasList()){
                    assignee(row.getBeforeColumnsList(),actors);assignee(row.getAfterColumnsList(),actors);
                }
            }else if(Set.of("repair_purchase_orders","repair_cost_confirmations").contains(table)){
                for(var row:change.getRowDatasList()){columnIds(row.getBeforeColumnsList(),"ticket_id",ticketIds);columnIds(row.getAfterColumnsList(),"ticket_id",ticketIds);}
            }else if(Set.of("repair_purchase_items","repair_material_receipts").contains(table)){
                for(var row:change.getRowDatasList()){columnIds(row.getBeforeColumnsList(),"purchase_order_id",orderIds);columnIds(row.getAfterColumnsList(),"purchase_order_id",orderIds);}
            }else if(Set.of("repair_members","repair_stores","repair_categories").contains(table))all=true;
            // Receipt item writes also update purchase_items.received_quantity in the same transaction.
        }
        if(!ticketIds.isEmpty())actors.addAll(mapper.ownersByTickets(new ArrayList<>(ticketIds)));
        if(!orderIds.isEmpty())actors.addAll(mapper.ownersByOrders(new ArrayList<>(orderIds)));
        if(all){cache.invalidateAll();fullWarm.set(true);}
        else if(!actors.isEmpty()){
            cache.invalidate(actors);
            if(pending.size()+actors.size()>2000)fullWarm.set(true);else pending.addAll(actors);
        }
        return heartbeat;
    }
    private static void assignee(List<CanalEntry.Column> columns,Set<Long> actors){columnIds(columns,"assignee_id",actors);}
    private static void columnIds(List<CanalEntry.Column> columns,String name,Set<Long> ids){
        for(var c:columns)if(c.getName().equals(name)&&!c.getIsNull()&&!c.getValue().isBlank())ids.add(Long.parseLong(c.getValue()));
    }
    private void warm(){
        long reconcile=0;
        while(running){
            try{
                if(leader){
                    long now=System.currentTimeMillis();
                    if(fullWarm.getAndSet(false)||now-reconcile>=60_000){
                        reconcile=now;long after=0;
                        while(running&&leader){
                            var batch=mapper.engineers(after,100);if(batch.isEmpty())break;
                            for(long actor:batch){if(!running||!leader)break;warmActor(actor);after=actor;}
                        }
                    }
                    for(long actor:pending.toArray(Long[]::new)){pending.remove(actor);warmActor(actor);}
                }
            }catch(Exception e){if(running)log.debug("Engineer prewarm deferred: {}",e.getClass().getSimpleName());}
            pause(100);
        }
    }
    private void warmActor(long actor){
        try{
            reports.dashboard(actor);
            tickets.list(actor,null,null,null,null,null,null,null,1,20);
            tickets.list(actor,null,com.gaofeng.repair.ticket.domain.TicketStatus.ASSIGNED,null,null,null,null,null,1,20);
        }catch(Exception e){log.debug("Engineer prewarm deferred for {}: {}",actor,e.getClass().getSimpleName());}
    }
    private static void pause(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    @PreDestroy public void stop(){running=false;executor.shutdownNow();}
}
