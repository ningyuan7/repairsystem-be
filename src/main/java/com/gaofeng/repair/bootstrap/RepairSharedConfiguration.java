package com.gaofeng.repair.bootstrap;

import com.gaofeng.auth.config.*;
import com.gaofeng.auth.service.AuthService;
import com.gaofeng.auth.audit.LoginLogService;
import com.gaofeng.auth.token.*;
import com.gaofeng.auth.verification.*;
import com.gaofeng.user.service.impl.UserServiceImpl;
import com.gaofeng.common.ratelimit.*;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;

@Configuration
@EnableConfigurationProperties(AbuseProtectionProperties.class)
@Import({AuthConfiguration.class,AuthService.class,UserServiceImpl.class,LoginLogService.class,
        RedisRefreshTokenStore.class,RedisVerificationCodeStore.class,VerificationService.class,RedisRateLimiter.class})
public class RepairSharedConfiguration {
    @Bean public org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy isolatedRepairMigration() {
        return flyway->{
            try(var connection=flyway.getConfiguration().getDataSource().getConnection()) {
                String database=connection.getCatalog();
                if(database==null || !database.matches("repair_[A-Za-z0-9_]+"))
                    throw new IllegalStateException("Repair migrations require a separate database named repair_*; legacy databases are prohibited");
            } catch(java.sql.SQLException ex) { throw new IllegalStateException("Cannot verify repair database isolation",ex); }
            flyway.migrate();
        };
    }
    @Bean JwtService jwtService(JwtEncoder encoder,JwtDecoder decoder,AuthProperties properties,AccessMapper mapper) {
        return new RepairJwtService(encoder,decoder,properties,mapper);
    }
    @Bean Jackson2ObjectMapperBuilderCustomizer stringIds() {
        return builder->builder.serializerByType(Long.class,ToStringSerializer.instance);
    }
}
