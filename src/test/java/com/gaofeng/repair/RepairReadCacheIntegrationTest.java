package com.gaofeng.repair;
import com.gaofeng.repair.cache.EngineerReadCache;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.gaofeng.repair.RepairIdentityIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;
@EnabledIfSystemProperty(named="repair.integration",matches="true")
class RepairReadCacheIntegrationTest {
 static EngineerReadCache cache;static StringRedisTemplate redis;
 @BeforeAll static void setup()throws Exception{prepareWith(Map.of("repair.read-cache.enabled",true,"repair.canal.enabled",false));cache=app.getBean(EngineerReadCache.class);redis=app.getBean(StringRedisTemplate.class);}
 @AfterAll static void close()throws Exception{cleanup();}
 static String read(long actor,String query,java.util.function.Supplier<String> loader){return cache.read(actor,0,query,json.constructType(String.class),loader,value->true);}
 @Test void concurrentColdReadersCoalesceOneRealDatabaseLoad()throws Exception{
  AtomicInteger loads=new AtomicInteger();var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(32);
  List<Future<Long>> futures=new ArrayList<>();
  try{
   for(int i=0;i<32;i++)futures.add(executor.submit(()->{start.await();long t=System.nanoTime();String result=read(900001,"burst",()->{loads.incrementAndGet();String count=db.queryForObject("SELECT CAST(COUNT(*) AS CHAR) FROM repair_tickets",String.class);try{Thread.sleep(150);}catch(InterruptedException e){Thread.currentThread().interrupt();}return count;});assertThat(result).isEqualTo("0");return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t);}));
   start.countDown();List<Long> times=new ArrayList<>();for(var f:futures)times.add(f.get(10,TimeUnit.SECONDS));Collections.sort(times);
   assertThat(loads.get()).isEqualTo(1);
   Files.writeString(Path.of("target/cache-burst-result.json"),json.writeValueAsString(Map.of("concurrentReaders",32,"databaseLoads",loads.get(),"p50Ms",times.get(15),"p95Ms",times.get(30),"scope","isolated real MySQL/Redis cache layer, not HTTP throughput")));
  }finally{executor.shutdownNow();}
 }
 @Test void invalidationFencesAnInFlightOldPublication()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
  try{
   var old=executor.submit(()->read(900002,"fence",()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return "old";}));
   assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();cache.invalidate(Set.of(900002L));release.countDown();assertThat(old.get(5,TimeUnit.SECONDS)).isEqualTo("old");
   assertThat(read(900002,"fence",()->"new")).isEqualTo("new");
   try(var cursor=redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(cache.prefix()+"data:900002:*").count(100).build())){
    while(cursor.hasNext())assertThat(redis.opsForValue().get(cursor.next())).doesNotContain("\"old\"");
   }
  }finally{release.countDown();executor.shutdownNow();}
 }
 @Test void emptyResultIsCachedAndActorsRemainIsolated(){
  AtomicInteger loads=new AtomicInteger();assertThat(read(900003,"empty",()->{loads.incrementAndGet();return "[]";})).isEqualTo("[]");
  assertThat(read(900003,"empty",()->{loads.incrementAndGet();return "unexpected";})).isEqualTo("[]");
  assertThat(read(900004,"empty",()->"other-engineer")).isEqualTo("other-engineer");assertThat(loads.get()).isEqualTo(1);
 }
 @Test void staleDataIsNotServedWhenBinlogHeartbeatIsMissing()throws Exception{
  assertThat(read(900005,"stale",()->"before")).isEqualTo("before");
  try(var cursor=redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(cache.prefix()+"data:900005:*").count(100).build())){
   while(cursor.hasNext()){String key=cursor.next();if(key.endsWith(":lock"))continue;var value=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(redis.opsForValue().get(key));value.put("writtenAt",System.currentTimeMillis()-4000);redis.opsForValue().set(key,value.toString(),java.time.Duration.ofMinutes(10));}
  }
  assertThat(read(900005,"stale",()->"after")).isEqualTo("after");
 }
 @Test void liveVisibilityRejectionForcesReload(){
  read(900006,"scope",()->"former-owner");
  assertThat(cache.read(900006,0,"scope",json.constructType(String.class),()->"current-owner",value->!value.equals("former-owner"))).isEqualTo("current-owner");
 }
 @Test void databaseRebuildCapacityFailsFastInsteadOfQueuingUnboundedReaders()throws Exception{
  var entered=new CountDownLatch(8);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(8);List<Future<String>> futures=new ArrayList<>();
  try{
   for(int i=0;i<8;i++)futures.add(executor.submit(()->cache.bounded(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return "ok";})));
   assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
   assertThatThrownBy(()->cache.bounded(()->"unexpected")).isInstanceOf(com.gaofeng.repair.common.RepairException.class).extracting("status","code").containsExactly(503,"READ_CACHE_BUSY");
   release.countDown();for(var future:futures)assertThat(future.get(5,TimeUnit.SECONDS)).isEqualTo("ok");
  }finally{release.countDown();executor.shutdownNow();}
 }
 @Test void redisServerGenerationIsCheckedBeforeTrustingCdcFreshness(){
  assertThat(cache.checkServerGeneration()).isTrue();
  assertThat(cache.checkServerGeneration()).isFalse();
 }
 @Test void frequentInvalidationsReuseBoundedDataKeys(){
  for(int i=0;i<25;i++){cache.invalidate(Set.of(900007L));final String value="value-"+i;assertThat(read(900007,"bounded",()->value)).isEqualTo(value);}
  List<String> keys=new ArrayList<>();try(var cursor=redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(cache.prefix()+"data:900007:*").count(100).build())){cursor.forEachRemaining(keys::add);}
  assertThat(keys).hasSize(1);
 }
}
