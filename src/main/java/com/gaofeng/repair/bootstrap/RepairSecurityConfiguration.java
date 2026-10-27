package com.gaofeng.repair.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.common.*;
import com.gaofeng.common.exception.BusinessException;
import com.gaofeng.common.ratelimit.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

@Configuration
public class RepairSecurityConfiguration {
    @Bean SecurityFilterChain repairSecurity(HttpSecurity http,JwtDecoder decoder,RepairAccess access,ObjectMapper json,
                                              RedisRateLimiter limiter,AbuseProtectionProperties protection,com.gaofeng.repair.access.service.LoginSessions sessions,@Value("${repair.cors-origins:http://localhost:5173}") String trustedOrigins) throws Exception {
        http.csrf(csrf->csrf.disable()).cors(cors->{})
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth
                        .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll()
                        .requestMatchers("/actuator/health","/error","/api/v1/auth/login","/api/v1/auth/register",
                                "/api/v1/auth/token/refresh","/api/v1/auth/logout","/api/v1/auth/send-code","/api/v1/auth/password/reset",
                                "/api/v1/repair/invitations/preview","/api/v1/repair/invitations/accept").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource->resource.bearerTokenResolver(request->
                    "/api/v1/auth/logout".equals(request.getRequestURI()) ? null : new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver().resolve(request))
                    .jwt(jwt->jwt.decoder(token->{
                    Jwt decoded=decoder.decode(token);
                    if(!"access".equals(decoded.getClaimAsString("token_type"))) throw new BadJwtException("Access token required");
                    return decoded;
                })).authenticationEntryPoint((request,response,ex)->write(json,response,401,"UNAUTHORIZED","登录已失效，请重新登录")))
                .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,ex)->write(json,response,401,"UNAUTHORIZED","请先登录"))
                        .accessDeniedHandler((request,response,ex)->write(json,response,403,"FORBIDDEN","无权执行此操作")));
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                String id=UUID.randomUUID().toString(); MDC.put("requestId",id); response.setHeader("X-Request-Id",id);
                response.setHeader("Cache-Control","no-store");
                response.setHeader("Referrer-Policy","no-referrer");
                try {
                    boolean cookieWrite=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()) && (request.getRequestURI().startsWith("/api/v1/auth/") || request.getRequestURI().equals("/api/v1/repair/invitations/accept"));
                    if(cookieWrite){
                        String origin=request.getHeader("Origin");
                        boolean trusted=origin==null || Arrays.stream(trustedOrigins.split(",")).map(String::strip).anyMatch(origin::equals);
                        if(!"1".equals(request.getHeader("X-Repair-CSRF")) || !trusted || (origin==null && "cross-site".equals(request.getHeader("Sec-Fetch-Site")))) {
                            write(json,response,403,"CSRF_REJECTED","请求来源校验失败，请刷新页面重试");return;
                        }
                    }
                    if("POST".equals(request.getMethod()) && (request.getRequestURI().startsWith("/api/v1/auth/") || request.getRequestURI().equals("/api/v1/repair/invitations/accept")))
                        limiter.check("repair-auth-ip",request.getRemoteAddr(),protection.getAuthIpPerMinute(),Duration.ofMinutes(1));
                    if(request.getRequestURI().equals("/api/v1/auth/send-code")) limiter.check("repair-code-ip",request.getRemoteAddr(),protection.getCodeIpPerMinute(),Duration.ofMinutes(1));
                    chain.doFilter(request,response);
                } catch(BusinessException ex) {
                    int status=ex.getErrorCode()==com.gaofeng.common.exception.ErrorCode.TOO_MANY_REQUESTS?429:503;
                    if(ex instanceof RateLimitException limited) response.setHeader("Retry-After",Long.toString(limited.getRetryAfterSeconds()));
                    write(json,response,status,ex.getErrorCode().getCode(),ex.getMessage());
                } finally { MDC.remove("requestId"); }
            }
        },BearerTokenAuthenticationFilter.class);
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                String uri=request.getRequestURI();
                boolean business=(uri.startsWith("/api/v1/repair/") && !uri.equals("/api/v1/repair/invitations/accept") && !uri.equals("/api/v1/repair/invitations/preview")) || uri.equals("/api/v1/auth/me");
                try {
                    var authentication=SecurityContextHolder.getContext().getAuthentication();
                    Jwt jwt=authentication!=null && authentication.getPrincipal() instanceof Jwt value?value:null;
                    if(business) access.authenticated(jwt);
                    else if(jwt!=null) sessions.authenticated(jwt);
                    chain.doFilter(request,response);
                } catch(RepairException ex) { write(json,response,ex.getStatus(),ex.getCode(),ex.getMessage()); }
                catch(org.springframework.dao.DataAccessException ex) { write(json,response,503,"SERVICE_UNAVAILABLE","身份服务暂不可用，请稍后重试"); }
            }
        },BearerTokenAuthenticationFilter.class);
        return http.build();
    }
    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${repair.cors-origins:http://localhost:5173}") String origins) {
        var config=new CorsConfiguration(); config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::strip).toList());
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type","Idempotency-Key","X-Repair-Invitation","X-Repair-CSRF"));
        config.setExposedHeaders(List.of("X-Request-Id","Retry-After")); config.setMaxAge(600L);config.setAllowCredentials(true);
        var source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**",config); return source;
    }
    private static void write(ObjectMapper json,HttpServletResponse response,int status,String code,String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(),RepairErrors.body(code,message));
    }
}
