package com.gaofeng.repair.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaofeng.repair.common.RepairException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Derived read data only. Every caller must validate live membership and ticket ownership. */
@Component
public class EngineerReadCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final String prefix;
    private final Semaphore rebuilds=new Semaphore(8);
    private volatile long redisRetryAt;
    private String redisRunId;
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>(
        "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Long.class);
    private static final DefaultRedisScript<Long> PUBLISH=new DefaultRedisScript<>(
        "if redis.call('get',KEYS[1])==ARGV[1] and redis.call('get',KEYS[2])==ARGV[2] and (redis.call('get',KEYS[3]) or '0')==ARGV[3] then redis.call('psetex',KEYS[4],ARGV[4],ARGV[5]); return 1 else return 0 end",Long.class);
    public EngineerReadCache(StringRedisTemplate redis,ObjectMapper json,MeterRegistry metrics,
            @Value("${repair.read-cache.enabled:false}") boolean enabled,
            @Value("${spring.datasource.url}") String url) {
        this.redis=redis;this.json=json;this.metrics=metrics;this.enabled=enabled;
        try { prefix="repair:read:v2:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.split("\\?")[0].getBytes(StandardCharsets.UTF_8))).substring(0,16)+":"; }
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public boolean enabled(){return enabled;}
    public record CacheStatus(boolean enabled,boolean cdcFresh,Long heartbeatAgeMillis) {}
    public CacheStatus status(){
        if(!enabled)return new CacheStatus(false,false,null);
        try{String value=redis(()->redis.opsForValue().get(prefix+"caught-up"));Long age=value==null?null:System.currentTimeMillis()-Long.parseLong(value);return new CacheStatus(true,age!=null&&age>=0&&age<3000,age);}
        catch(RuntimeException e){return new CacheStatus(true,false,null);}
    }
    public String prefix(){return prefix;}
    /** A Redis snapshot may restore superseded data and epochs together. Fence it before renewing CDC freshness. */
    public synchronized boolean checkServerGeneration(){
        String current=redis(()->redis.execute((org.springframework.data.redis.core.RedisCallback<String>)connection->{
            var info=connection.serverCommands().info("server");
            return info==null?null:info.getProperty("run_id");
        }));
        if(current==null||current.isBlank())throw new IllegalStateException("Redis INFO server/run_id is required for CDC freshness");
        if(current.equals(redisRunId))return false;
        invalidateAll();redisRunId=current;return true;
    }
    public record Stamp(long writtenAt,String generation,String actor,com.fasterxml.jackson.databind.JsonNode value) {}
    private record Keys(String global,String actor,String data) {}
    private static final class RedisUnavailable extends RuntimeException { RedisUnavailable(Throwable e){super(e);} }
    private <T>T redis(Supplier<T> operation) {
        if(System.currentTimeMillis()<redisRetryAt)throw new RedisUnavailable(null);
        try{return operation.get();}catch(org.springframework.dao.DataAccessException e){redisRetryAt=System.currentTimeMillis()+1000;throw new RedisUnavailable(e);}
    }
    private String global(){return redis(()->{redis.opsForValue().setIfAbsent(prefix+"generation",UUID.randomUUID().toString());return redis.opsForValue().get(prefix+"generation");});}
    private Keys keys(long actor,int authVersion,String query){
        String g=global(),a=redis(()->redis.opsForValue().get(prefix+"actor:"+actor));
        if(a==null)a="0";
        return new Keys(g,a,prefix+"data:"+actor+":"+authVersion+":"+query);
    }
    public <T>T read(long actor,int authVersion,String query,JavaType type,Supplier<T> loader,Predicate<T> visible){
        if(!enabled)return loader.get();
        for(int attempt=0;attempt<2;attempt++){
            T value;
            try{value=cached(actor,authVersion,query,type,loader);}
            catch(RedisUnavailable e){metrics.counter("repair.read.cache","outcome","redis_fallback").increment();value=bounded(loader);}
            if(visible.test(value))return value;
            try{invalidate(Set.of(actor));}catch(RedisUnavailable ignored){}
        }
        throw busy();
    }
    private <T>T cached(long actor,int authVersion,String query,JavaType type,Supplier<T> loader){
        Keys keys=keys(actor,authVersion,query);
        T hit=lookup(keys,type);
        if(hit!=null){metrics.counter("repair.read.cache","outcome","hit").increment();return hit;}
        String lock=keys.data+":lock",token=UUID.randomUUID().toString();
        boolean acquired=Boolean.TRUE.equals(redis(()->redis.opsForValue().setIfAbsent(lock,token,Duration.ofSeconds(15))));
        if(!acquired){
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(800);
            do{
                try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw busy();}
                hit=lookup(keys,type);if(hit!=null){metrics.counter("repair.read.cache","outcome","coalesced").increment();return hit;}
            }while(System.nanoTime()<deadline);
            metrics.counter("repair.read.cache","outcome","busy").increment();throw busy();
        }
        try{
            hit=lookup(keys,type);if(hit!=null)return hit;
            T result=bounded(loader);
            String body;
            try{body=json.writeValueAsString(new Stamp(System.currentTimeMillis(),keys.global,keys.actor,json.valueToTree(result)));}
            catch(Exception e){throw new IllegalStateException("Cannot encode engineer read cache",e);}
            long ttl=ThreadLocalRandom.current().nextLong(600_000,720_001);
            redis(()->redis.execute(PUBLISH,List.of(lock,prefix+"generation",prefix+"actor:"+actor,keys.data),token,keys.global,keys.actor,Long.toString(ttl),body));
            return result;
        }finally{try{redis(()->redis.execute(RELEASE,List.of(lock),token));}catch(RedisUnavailable ignored){}}
    }
    private <T>T lookup(Keys keys,JavaType type){
        String key=keys.data;
        String raw=redis(()->redis.opsForValue().get(key));if(raw==null)return null;
        try{
            Stamp entry=json.readValue(raw,Stamp.class);
            if(!keys.global.equals(entry.generation())||!keys.actor.equals(entry.actor()))return null;
            // Heartbeats traverse MySQL binlog + Canal, so an idle but broken parser cannot look healthy.
            String heartbeat=redis(()->redis.opsForValue().get(prefix+"caught-up"));
            long now=System.currentTimeMillis();
            boolean caughtUp=heartbeat!=null&&now-Long.parseLong(heartbeat)>=0&&now-Long.parseLong(heartbeat)<3000;
            if(!caughtUp&&now-entry.writtenAt()>2000)return null;
            return json.convertValue(entry.value(),type);
        }catch(RedisUnavailable e){throw e;}
        catch(Exception e){redis(()->redis.delete(key));return null;}
    }
    public <T>T bounded(Supplier<T> loader){
        if(!rebuilds.tryAcquire())throw busy();
        try{metrics.counter("repair.read.cache","outcome","load").increment();return loader.get();}
        finally{rebuilds.release();}
    }
    public void invalidate(Set<Long> actors){for(long actor:actors)redis(()->{redis.opsForValue().set(prefix+"actor:"+actor,UUID.randomUUID().toString());return true;});}
    public void invalidateAll(){redis(()->{redis.opsForValue().set(prefix+"generation",UUID.randomUUID().toString());redis.delete(prefix+"caught-up");return true;});}
    public void caughtUp(long sentAt){if(System.currentTimeMillis()-sentAt<3000)redis(()->{redis.opsForValue().set(prefix+"caught-up",Long.toString(sentAt),Duration.ofSeconds(3));return true;});}
    public boolean lease(String token){return redis(()->Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(prefix+"leader",token,Duration.ofSeconds(15))));}
    public boolean renew(String token){return redis(()->Long.valueOf(1).equals(redis.execute(new DefaultRedisScript<>("if redis.call('get',KEYS[1])==ARGV[1] then redis.call('pexpire',KEYS[1],15000);return 1 else return 0 end",Long.class),List.of(prefix+"leader"),token)));}
    public void release(String token){redis(()->redis.execute(RELEASE,List.of(prefix+"leader"),token));}
    private static RepairException busy(){return new RepairException(503,"READ_CACHE_BUSY","工单加载繁忙，请稍后重试");}
}
