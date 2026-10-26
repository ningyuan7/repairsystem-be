package com.gaofeng.repair.ticket.mapper;
import com.gaofeng.repair.ticket.domain.*;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
public interface TicketMapper {
    Ticket ticket(long id);
    Ticket lockTicket(long id);
    List<Ticket> list(@Param("actor") long actor,@Param("role") String role,@Param("storeId") Long storeId,
                      @Param("status") String status,@Param("priority") String priority,@Param("assignee") Long assignee,
                      @Param("keyword") String keyword,@Param("from") Instant from,@Param("to") Instant to,@Param("offset") int offset,@Param("size") int size);
    long count(@Param("actor") long actor,@Param("role") String role,@Param("storeId") Long storeId,
                @Param("status") String status,@Param("priority") String priority,@Param("assignee") Long assignee,
                @Param("keyword") String keyword,@Param("from") Instant from,@Param("to") Instant to);
    void insert(Ticket ticket);
    void setTicketNo(@Param("id") long id,@Param("number") String number);
    int update(Ticket ticket);
    void occupyAsset(@Param("assetId") long assetId,@Param("ticketId") long ticketId);
    void releaseAsset(long ticketId);
    void insertEvent(TicketEvent event);
    List<TicketEvent> events(@Param("ticketId") long ticketId,@Param("hq") boolean hq,@Param("before") Long before,@Param("size") int size);
    TicketEvent latestResult(long ticketId);
    List<Long> recipients(@Param("storeId") long storeId,@Param("assignee") Long assignee,@Param("hqOnly") boolean hqOnly);
    void notify(@Param("recipient") long recipient,@Param("eventId") long eventId,@Param("ticketId") long ticketId,@Param("type") String type,@Param("message") String message);
}
