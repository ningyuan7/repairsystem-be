package com.gaofeng.repair.access.api.dto;

import com.gaofeng.repair.access.domain.Role;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class AccessDtos {
    private AccessDtos() {}
    public enum Status { ACTIVE, DISABLED }
    public record Page<T>(List<T> items,int page,int size,long total) {}
    public record StoreView(Long id,String code,String name,String region,String address,String status,int version) {}
    public record MemberView(Long id,String email,String displayName,String contactPhone,Role role,String status,int version,List<StoreView> stores) {}
    public record Session(Long id,String displayName,String email,String contactPhone,Role role,int memberVersion,
                          List<StoreView> allowedStores,boolean repairProfileComplete,List<String> permissions) {}
    public record ProfileRequest(@NotNull @Min(0) Integer expectedVersion,
                                 @NotBlank @Size(max=64) String displayName,@NotNull @Size(max=32) String contactPhone) {}
    public record InvitationRequest(@NotBlank @Email @Size(max=128) String email,@NotNull Role role,
                                    @NotNull @Size(max=100) List<@NotBlank @Pattern(regexp="[1-9][0-9]{0,17}") String> storeIds) {}
    public record InvitationCreated(String id,String activationPath,Instant expiresAt) {}
    public record InvitationPreview(String email,Role role,Instant expiresAt,boolean existingAccount) {}
    public record AcceptInvitation(@NotBlank @Size(max=256) String token,@NotBlank @Size(max=64) String displayName,
                                   @Size(max=128) String password) {}
    public record ActivationResult(Long userId,String message) {}
    public record AccessRequest(@NotNull @Min(0) Integer expectedVersion,@NotNull Role role,
                                @NotNull @Size(max=100) List<@NotBlank @Pattern(regexp="[1-9][0-9]{0,17}") String> storeIds,
                                @NotBlank @Size(max=500) String reason) {}
    public record StatusRequest(@NotNull @Min(0) Integer expectedVersion,@NotBlank @Size(max=500) String reason) {}
    public record StoreRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String code,
                               @NotBlank @Size(max=128) String name,@NotNull @Size(max=64) String region,
                               @NotNull @Size(max=255) String address,@NotNull Status status,
                               @Min(0) Integer expectedVersion) {}
}
