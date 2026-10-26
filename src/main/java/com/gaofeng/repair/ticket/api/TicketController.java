package com.gaofeng.repair.ticket.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.gaofeng.repair.access.api.dto.AccessDtos.Page;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.ticket.api.dto.TicketDtos.*;
import com.gaofeng.repair.ticket.domain.TicketStatus;
import com.gaofeng.repair.ticket.service.TicketService;
import com.gaofeng.repair.ticket.service.TicketAccess;
import com.gaofeng.repair.common.IdempotencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
@RestController
@RequestMapping("/api/v1/repair/tickets")
@RequiredArgsConstructor
public class TicketController {
    private final TicketService service;
    private final IdempotencyService idempotency;
    private final TicketAccess visibility;
    @PostMapping public ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateTicket body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"ticket:create",key,body,201,()->service.create(actor,body));
    }
    @GetMapping public Page<TicketView> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) Long storeId,
            @RequestParam(required=false) TicketStatus status,@RequestParam(required=false) Priority priority,@RequestParam(required=false) Long assigneeId,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return service.list(RepairAccess.userId(jwt),storeId,status,priority,assigneeId,keyword,from,to,page,size); }
    @GetMapping("/{id}") public TicketView detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) { return service.detail(RepairAccess.userId(jwt),id); }
    @GetMapping("/{id}/events") public Events events(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam(required=false) Long beforeId,@RequestParam(defaultValue="30") int size) { return service.events(RepairAccess.userId(jwt),id,beforeId,size); }
    @PostMapping("/{id}/{action}") public ResponseEntity<JsonNode> command(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@PathVariable String action,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Command body) {
        long actor=RepairAccess.userId(jwt);
        visibility.visible(actor,id); // Current assignment/store scope also applies to replayed command results.
        return idempotency.execute(actor,"ticket:"+id+":"+action,key,body,200,()->service.command(actor,id,action,body));
    }
}
