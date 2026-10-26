package com.gaofeng.repair.notification.mapper;
import com.gaofeng.repair.notification.domain.*;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
public interface NotificationMapper {
    void insert(Notification notification);
    List<Notification> list(@Param("actor") long actor,@Param("role") String role,@Param("offset") int offset,@Param("size") int size);
    long count(@Param("actor") long actor,@Param("role") String role,@Param("unread") boolean unread);
    int read(@Param("actor") long actor,@Param("role") String role,@Param("ids") List<Long> ids);
    List<Long> slaCandidates(long after);
    boolean marked(@Param("ticketId") long ticketId,@Param("kind") String kind,@Param("cycle") String cycle);
    void mark(@Param("ticketId") long ticketId,@Param("kind") String kind,@Param("cycle") String cycle,@Param("due") Instant due);
    Long latestEvent(long ticketId);
}
