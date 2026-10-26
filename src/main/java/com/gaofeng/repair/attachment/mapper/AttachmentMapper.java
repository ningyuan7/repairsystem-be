package com.gaofeng.repair.attachment.mapper;
import com.gaofeng.repair.attachment.domain.Attachment;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface AttachmentMapper {
    Attachment attachment(String id);
    Attachment lock(String id);
    List<Attachment> expired();
    int expire(String id);
    void deleted(String id);
    boolean objectReferenced(String key);
    void insert(Attachment attachment);
    int bind(@Param("id") String id,@Param("actor") long actor,@Param("storeId") long storeId,@Param("ticketId") long ticketId,
             @Param("purpose") String purpose,@Param("eventId") long eventId);
    List<Attachment> ticketImages(@Param("ticketId") long ticketId,@Param("eventId") Long eventId);
    List<Attachment> purchaseImages(@Param("orderId") long orderId,@Param("purpose") String purpose,@Param("eventId") Long eventId);
}
