package com.gaofeng.repair.procurement.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.common.IdempotencyService;
import com.gaofeng.repair.procurement.api.dto.ProcurementDtos.*;
import com.gaofeng.repair.procurement.service.*;
import com.gaofeng.repair.ticket.service.TicketAccess;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/repair")
@RequiredArgsConstructor
public class ProcurementController {
    private final ProcurementService service;
    private final PurchaseAccess visibility;
    private final TicketAccess tickets;
    private final RepairAccess access;
    private final IdempotencyService idempotency;
    @PostMapping("/tickets/{id}/purchase-orders") public ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateOrder body) {
        long actor=RepairAccess.userId(jwt); tickets.visible(actor,id); access.hq(actor);
        return idempotency.execute(actor,"ticket:"+id+":purchase-create",key,body,201,()->service.create(actor,id,body));
    }
    @GetMapping("/tickets/{id}/purchase-orders") public List<OrderView> orders(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) { return service.forTicket(RepairAccess.userId(jwt),id); }
    @GetMapping("/purchase-orders/{id}") public OrderView get(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) { return service.get(RepairAccess.userId(jwt),id); }
    @PutMapping("/purchase-orders/{id}") public ResponseEntity<JsonNode> update(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DraftInput body) {
        long actor=RepairAccess.userId(jwt); visibility.hq(actor,id);
        return idempotency.execute(actor,"purchase:"+id+":draft",key,body,200,()->service.draft(actor,id,body));
    }
    @PostMapping("/purchase-orders/{id}/place") public ResponseEntity<JsonNode> place(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody VersionInput body) {
        long actor=RepairAccess.userId(jwt); visibility.hq(actor,id);
        return idempotency.execute(actor,"purchase:"+id+":place",key,body,200,()->service.place(actor,id,body));
    }
    @PostMapping("/purchase-orders/{id}/cancel") public ResponseEntity<JsonNode> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody VersionInput body) {
        long actor=RepairAccess.userId(jwt); visibility.hq(actor,id);
        return idempotency.execute(actor,"purchase:"+id+":cancel",key,body,200,()->service.cancel(actor,id,body));
    }
    @GetMapping("/tickets/{id}/materials") public Materials materials(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) { return service.materials(RepairAccess.userId(jwt),id); }
    @PostMapping("/purchase-orders/{id}/receipts") public ResponseEntity<JsonNode> receive(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ReceiptInput body) {
        long actor=RepairAccess.userId(jwt); visibility.visible(actor,id);
        return idempotency.execute(actor,"purchase:"+id+":receive",key,body,200,()->service.receive(actor,id,key,body));
    }
    @GetMapping("/purchase-orders/{id}/receipts") public List<ReceiptView> receipts(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) { return service.receipts(RepairAccess.userId(jwt),id); }
    @GetMapping("/tickets/{id}/costs") public CostView costs(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) { return service.costs(RepairAccess.userId(jwt),id); }
    @PutMapping("/tickets/{id}/costs") public ResponseEntity<JsonNode> confirmCosts(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CostInput body) {
        long actor=RepairAccess.userId(jwt); tickets.visible(actor,id); access.hq(actor);
        return idempotency.execute(actor,"ticket:"+id+":costs",key,body,200,()->service.confirmCosts(actor,id,body));
    }
}
