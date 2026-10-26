package com.gaofeng.repair.notification.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.common.IdempotencyService;
import com.gaofeng.repair.notification.api.dto.NotificationDtos.*;
import com.gaofeng.repair.notification.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/repair") @RequiredArgsConstructor
public class NotificationController {
    private final NotificationService service;private final IdempotencyService idempotency;
    @GetMapping("/notifications") public Feed list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return service.feed(RepairAccess.userId(jwt),page,size); }
    @PostMapping("/notifications/read") public ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Read body) {long actor=RepairAccess.userId(jwt);return idempotency.execute(actor,"notifications:read",key,body,200,()->service.read(actor,body));}
}
