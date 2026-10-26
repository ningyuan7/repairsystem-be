package com.gaofeng.repair.procurement.service;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.procurement.domain.PurchaseOrder;
import com.gaofeng.repair.procurement.mapper.ProcurementMapper;
import com.gaofeng.repair.ticket.service.TicketAccess;
import com.gaofeng.repair.common.RepairException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class PurchaseAccess {
    private final ProcurementMapper mapper;
    private final TicketAccess tickets;
    private final RepairAccess access;
    public PurchaseOrder visible(long actor,long id) {
        PurchaseOrder order=mapper.order(id); if(order==null) throw RepairException.missing();
        tickets.visible(actor,order.getTicketId()); return order;
    }
    public PurchaseOrder hq(long actor,long id) { PurchaseOrder order=visible(actor,id); access.hq(actor); return order; }
}
