package com.gaofeng.common.ratelimit;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("app.protection")
public class AbuseProtectionProperties {
    @Min(1) private int authIpPerMinute = 30;
    @Min(1) private int codeIpPerMinute = 5;
    @Min(1) private int loginAccountPerTenMinutes = 10;
    @Min(1) private int uploadPerMinute = 60;
    @Min(1) private int aiPerMinute = 5;
}
