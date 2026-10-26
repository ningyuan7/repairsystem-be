package com.gaofeng.repair.asset.api.dto;
import com.gaofeng.repair.access.api.dto.AccessDtos.Status;
import jakarta.validation.constraints.*;
public final class AssetDtos {
    private AssetDtos() {}
    public enum Condition { NORMAL, FAULTY, REPAIRING }
    public enum Lifecycle { ACTIVE, RETIRED }
    public record AssetView(Long id,Long storeId,String assetCode,String name,String zone,String brand,String model,
                            String serialNumber,String lifecycle,String condition,Long replacedByAssetId,int version) {}
    public record CategoryView(Long id,String code,String name,String status,int version) {}
    public record CategoryRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String code,
                                  @NotBlank @Size(max=128) String name,@NotNull Status status,@Min(0) Integer expectedVersion) {}
    public record AssetRequest(@NotBlank @Pattern(regexp="[1-9][0-9]{0,17}") String storeId,@NotBlank @Size(max=64) String assetCode,
                               @NotBlank @Size(max=128) String name,@NotBlank @Size(max=128) String zone,
                               @Size(max=128) String brand,@Size(max=128) String model,@Size(max=128) String serialNumber,
                               @Min(0) Integer expectedVersion) {}
    public record ConditionRequest(@NotNull @Min(0) Integer expectedVersion,@NotNull Condition condition,
                                   @NotBlank @Size(max=500) String reason) {}
    public record RetireRequest(@NotNull @Min(0) Integer expectedVersion,@NotBlank @Size(max=500) String reason) {}
}
