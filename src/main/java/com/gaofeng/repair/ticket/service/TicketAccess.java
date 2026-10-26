package com.gaofeng.repair.ticket.service;
import com.gaofeng.repair.access.domain.*;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.asset.service.AssetService;
import com.gaofeng.repair.ticket.domain.Ticket;
import com.gaofeng.repair.ticket.mapper.TicketMapper;
import com.gaofeng.repair.common.RepairException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class TicketAccess {
    private final RepairAccess access;
    private final AssetService assets;
    private final TicketMapper mapper;
    public Ticket visible(long actor,long id) {
        Member member=access.active(actor); Ticket ticket=mapper.ticket(id);
        if(ticket==null) throw RepairException.missing();
        if(member.getRole()==Role.ENGINEER) { if(!Long.valueOf(actor).equals(ticket.getAssigneeId())) throw RepairException.missing(); }
        else assets.visibleStore(member,ticket.getStoreId());
        return ticket;
    }
}
