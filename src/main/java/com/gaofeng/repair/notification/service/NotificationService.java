package com.gaofeng.repair.notification.service;
import com.gaofeng.repair.access.service.*;
import com.gaofeng.repair.notification.api.dto.NotificationDtos.*;
import com.gaofeng.repair.notification.domain.Notification;
import com.gaofeng.repair.notification.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor
public class NotificationService {
    private final NotificationMapper mapper; private final RepairAccess access;
    /** The in-app notification commits with the business event; business events never send email. */
    @Transactional public void emit(long recipient,long eventId,long ticketId,String type,String message) {
        Notification notification=new Notification(); notification.setRecipientId(recipient); notification.setEventId(eventId); notification.setTicketId(ticketId); notification.setType(type); notification.setMessage(message);
        mapper.insert(notification);
    }
    @Transactional(readOnly=true) public Feed feed(long actor,int page,int size) {
        String role=access.active(actor).getRole().name(); AccessService.checkPage(page,size);
        return new Feed(mapper.list(actor,role,(page-1)*size,size).stream().map(row->new View(row.getId(),row.isTicketAccessible()?row.getTicketId():null,row.getType(),row.getMessage(),row.isRead(),row.getCreatedAt())).toList(),mapper.count(actor,role,true),page,size,mapper.count(actor,role,false));
    }
    @Transactional public ReadResult read(long actor,Read body) { String role=access.active(actor).getRole().name(); int updated=mapper.read(actor,role,body.ids().stream().map(Long::parseLong).distinct().toList()); return new ReadResult(updated,mapper.count(actor,role,true)); }
}
