package com.gaofeng.repair.cache.mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface EngineerCacheMapper {
    List<Long> engineers(@Param("after") long after,@Param("size") int size);
    long owned(@Param("actor") long actor,@Param("ids") List<Long> ids);
    List<Long> ownersByTickets(@Param("ids") List<Long> ids);
    List<Long> ownersByOrders(@Param("ids") List<Long> ids);
    void heartbeat(@Param("sentAt") long sentAt);
}
