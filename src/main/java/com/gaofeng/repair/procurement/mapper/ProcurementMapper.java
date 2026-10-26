package com.gaofeng.repair.procurement.mapper;
import com.gaofeng.repair.procurement.domain.*;
import com.gaofeng.repair.procurement.api.dto.ProcurementDtos.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface ProcurementMapper {
    PurchaseOrder order(long id);
    PurchaseOrder lockOrder(long id);
    PurchaseOrder active(long ticketId);
    List<PurchaseOrder> ordersForTicket(long ticketId);
    void insertOrder(PurchaseOrder order);
    int updateOrder(PurchaseOrder order);
    List<PurchaseItem> items(long orderId);
    List<PurchaseItem> lockItems(long orderId);
    List<MaterialLine> materialItems(long orderId);
    void insertItem(PurchaseItem item);
    int updateItem(PurchaseItem item);
    void deleteItem(long id);
    int receive(@Param("id") long id,@Param("orderId") long orderId,@Param("quantity") int quantity);
    void insertReceipt(Receipt receipt);
    void linkReceiptEvent(@Param("id") long id,@Param("eventId") long eventId);
    void receiptLine(@Param("receiptId") long receiptId,@Param("itemId") long itemId,@Param("quantity") int quantity);
    List<Receipt> receipts(long orderId);
    List<ReceiptLine> receiptLines(long receiptId);
    Cost cost(long ticketId);
    Cost lockCost(long ticketId);
    void insertCost(Cost cost);
    int updateCost(Cost cost);
}
