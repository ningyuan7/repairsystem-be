package com.gaofeng.repair.access.api;
import com.gaofeng.repair.access.api.dto.AccessDtos.*;
import com.gaofeng.repair.access.service.*;
import com.gaofeng.repair.common.IdempotencyService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/repair/stores")
@RequiredArgsConstructor
public class StoreController {
    private final StoreService service;
    private final IdempotencyService idempotency;
    @GetMapping public Page<StoreView> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return service.list(RepairAccess.userId(jwt),page,size); }
    @PostMapping public ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody StoreRequest body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"store:create",key,body,201,()->service.save(actor,null,body));
    }
    @PutMapping("/{id}") public ResponseEntity<JsonNode> update(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody StoreRequest body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"store:"+id+":update",key,body,200,()->service.save(actor,id,body));
    }
}
