package com.gaofeng.repair;
import com.gaofeng.repair.cache.EngineerReadCache;
import com.gaofeng.repair.report.service.ReportService;
import com.gaofeng.repair.ticket.service.TicketService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.nio.file.*;
import java.util.*;
import static com.gaofeng.repair.RepairIdentityIntegrationTest.*;
import static com.gaofeng.repair.RepairWorkflowIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;
@EnabledIfSystemProperty(named="repair.canal.integration",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepairCanalIntegrationTest {
 static EngineerReadCache cache;static StringRedisTemplate redis;static ReportService reports;static TicketService tickets;static String ticket;
 @BeforeAll static void setup()throws Exception{
  prepareWith(Map.of("repair.read-cache.enabled",true,"repair.canal.enabled",true,"repair.canal.destination","repair-test"));
  cache=app.getBean(EngineerReadCache.class);redis=app.getBean(StringRedisTemplate.class);reports=app.getBean(ReportService.class);tickets=app.getBean(TicketService.class);
  seed();await(()->{String value=redis.opsForValue().get(cache.prefix()+"caught-up");return value!=null&&System.currentTimeMillis()-Long.parseLong(value)<3000;},15000);
 }
 @AfterAll static void close()throws Exception{RepairIdentityIntegrationTest.cleanup();}
 static void await(java.util.function.BooleanSupplier condition,long millis)throws Exception{long end=System.currentTimeMillis()+millis;do{if(condition.getAsBoolean())return;Thread.sleep(50);}while(System.currentTimeMillis()<end);assertThat(condition.getAsBoolean()).describedAs("condition within "+millis+" ms").isTrue();}
 static com.gaofeng.repair.access.api.dto.AccessDtos.Page<com.gaofeng.repair.ticket.api.dto.TicketDtos.TicketView> page(String actor){return tickets.list(Long.parseLong(actor),null,null,null,null,null,null,null,1,20);}
 @Test @Order(1) void actualBinlogUpdatesPrewarmedEngineerQueueWithinThreeSeconds()throws Exception{
  assertThat(page(engineerId1).items()).isEmpty();ticket=report(null,"Canal 真实增量合成工单").path("ticketId").asText();
  ok(ticket,"approve",hq,Map.of("procurementRequired",false,"resolutionPlan","REPAIR","priority","NORMAL"));
  long start=System.currentTimeMillis();ok(ticket,"assign",hq,Map.of("engineerId",engineerId1));
  await(()->page(engineerId1).items().stream().anyMatch(t->t.id().toString().equals(ticket)),3000);
  Files.writeString(Path.of("target/canal-live-result.json"),json.writeValueAsString(Map.of("database",database,"assignmentVisibleMs",System.currentTimeMillis()-start,"heartbeatThroughBinlog",true,"provider","Canal 1.1.8 TCP")));
 }
 @Test @Order(2) void formerEngineerCannotReadCachedTicketEvenBeforeCdcCatchesUp()throws Exception{
  assertThat(page(engineerId1).items()).hasSize(1);ok(ticket,"accept",engineer1,Map.of());ok(ticket,"start",engineer1,Map.of());
  ok(ticket,"reassign",hq,Map.of("engineerId",engineerId2,"reason","调整维修人员"));
  assertThat(page(engineerId1).items()).isEmpty();assertThat(request("GET","/api/v1/repair/tickets/"+ticket,null,engineer1,null).status()).isEqualTo(404);
  await(()->page(engineerId2).items().size()==1,3000);
 }
 @Test @Order(3) void databaseCommandStateRemainsAuthoritativeWithCachedDisplay()throws Exception{
  assertThat(page(engineerId2).items()).hasSize(1);
  assertThat(command(ticket,"start",engineer2,Map.of()).status()).isEqualTo(409);
  ok(ticket,"accept",engineer2,Map.of());ok(ticket,"start",engineer2,Map.of());
  await(()->reports.dashboard(Long.parseLong(engineerId2)).statuses().stream().anyMatch(s->s.status().equals("IN_PROGRESS")&&s.count()==1),3000);
 }
 @Test @Order(4) void warmHttpBurstDoesNotRebuildHeavyEngineerReads()throws Exception{
  long afterWrites=System.currentTimeMillis();await(()->{String value=redis.opsForValue().get(cache.prefix()+"caught-up");return value!=null&&Long.parseLong(value)>afterWrites;},3000);
  reports.dashboard(Long.parseLong(engineerId2));page(engineerId2);
  var registry=app.getBean(io.micrometer.core.instrument.MeterRegistry.class);double before=registry.counter("repair.read.cache","outcome","load").count();
  var executor=java.util.concurrent.Executors.newFixedThreadPool(32);var start=new java.util.concurrent.CountDownLatch(1);List<java.util.concurrent.Future<Long>> futures=new ArrayList<>();
  try{
   for(int i=0;i<32;i++){final String path=i%2==0?"/api/v1/repair/dashboard":"/api/v1/repair/tickets?page=1&size=20";futures.add(executor.submit(()->{start.await();long t=System.nanoTime();assertThat(request("GET",path,null,engineer2,null).status()).isEqualTo(200);return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t);}));}
   start.countDown();List<Long> times=new ArrayList<>();for(var future:futures)times.add(future.get(15,java.util.concurrent.TimeUnit.SECONDS));Collections.sort(times);
   double loads=registry.counter("repair.read.cache","outcome","load").count()-before;assertThat(loads).isZero();
   Files.writeString(Path.of("target/cache-http-burst-result.json"),json.writeValueAsString(Map.of("concurrentRequests",32,"engineers",1,"errors",0,"heavyReadRebuilds",loads,"p50Ms",times.get(15),"p95Ms",times.get(30),"scope","local HTTP: real JWT, permission queries, Redis and MySQL; not a production capacity claim")));
  }finally{executor.shutdownNow();}
 }
 @Test @Order(5) void materialReceiptUpdatesCachedReadinessWithoutChangingStartRule()throws Exception{
  var purchase=com.gaofeng.repair.RepairProcurementIntegrationTest.purchase("PART_REPAIR");
  assertThat(com.gaofeng.repair.RepairProcurementIntegrationTest.save(purchase,List.of(com.gaofeng.repair.RepairProcurementIntegrationTest.item("PART",1,"10.00",true)),"0.00").status()).isEqualTo(200);
  com.gaofeng.repair.RepairProcurementIntegrationTest.place(purchase);com.gaofeng.repair.RepairProcurementIntegrationTest.assignAndAccept(purchase);
  await(()->page(engineerId1).items().stream().anyMatch(t->t.id().toString().equals(purchase.ticketId())&&!t.materialsReady()),3000);
  assertThat(command(purchase.ticketId(),"start",engineer1,Map.of()).status()).isEqualTo(409);
  var line=com.gaofeng.repair.RepairProcurementIntegrationTest.materials(purchase,managerA).path("items").get(0);
  com.gaofeng.repair.RepairProcurementIntegrationTest.receive(purchase,managerA,List.of(Map.of("purchaseItemId",line.path("id").asText(),"quantity",1)));
  await(()->page(engineerId1).items().stream().anyMatch(t->t.id().toString().equals(purchase.ticketId())&&t.materialsReady()),3000);
  ok(purchase.ticketId(),"start",engineer1,Map.of());
 }
}
