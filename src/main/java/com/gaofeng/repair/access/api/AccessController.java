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
@RequestMapping("/api/v1/repair")
@RequiredArgsConstructor
public class AccessController {
    private final AccessService service;
    private final InvitationService invitations;
    private final IdempotencyService idempotency;
    @GetMapping("/session") public Session session(@AuthenticationPrincipal Jwt jwt) { return service.session(RepairAccess.userId(jwt)); }
    @PatchMapping("/me/profile") public ResponseEntity<JsonNode> profile(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ProfileRequest body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"profile",key,body,200,()->service.profile(actor,body));
    }
    @GetMapping("/members") public Page<MemberView> members(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return service.members(RepairAccess.userId(jwt),page,size); }
    @PutMapping("/members/{id}/access") public ResponseEntity<JsonNode> update(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody AccessRequest body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"member:"+id+":access",key,body,200,()->service.changeAccess(actor,id,body));
    }
    @PostMapping("/members/{id}/disable") public ResponseEntity<JsonNode> disable(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody StatusRequest body) { return status(jwt,id,key,body,false); }
    @PostMapping("/members/{id}/enable") public ResponseEntity<JsonNode> enable(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody StatusRequest body) { return status(jwt,id,key,body,true); }
    private ResponseEntity<JsonNode> status(Jwt jwt,long id,String key,StatusRequest body,boolean enabled) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"member:"+id+(enabled?":enable":":disable"),key,body,200,()->service.changeStatus(actor,id,body,enabled));
    }
    @PostMapping("/invitations") public ResponseEntity<JsonNode> invite(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody InvitationRequest body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"invitation:create",key,body,201,()->invitations.create(actor,body));
    }
    @PostMapping("/invitations/preview") public InvitationPreview preview(@RequestBody @Valid InvitationToken body) { return invitations.preview(body.token()); }
    public record InvitationToken(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=256) String token) {}
    @PostMapping("/invitations/accept") public ActivationResult activate(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody AcceptInvitation body) { return invitations.accept(body,jwt); }
}
