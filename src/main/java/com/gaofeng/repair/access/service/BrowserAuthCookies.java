package com.gaofeng.repair.access.service;
import com.gaofeng.auth.api.dto.TokenResponse;
import com.gaofeng.repair.common.RepairException;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
@Component
public class BrowserAuthCookies {
    private final boolean secure;
    private final String sameSite;
    public BrowserAuthCookies(@Value("${repair.auth.cookie-secure:true}") boolean secure,@Value("${repair.auth.cookie-same-site:Lax}") String sameSite) {
        if(!Set.of("Lax","Strict","None").contains(sameSite) || (!secure && "None".equals(sameSite))) throw new IllegalArgumentException("SameSite None requires Secure cookies");
        this.secure=secure;this.sameSite=sameSite;
    }
    private String name(){return secure?"__Host-repair_refresh":"repair_refresh_local";}
    public String read(HttpServletRequest request){
        List<Cookie> matches=request.getCookies()==null?List.of():Arrays.stream(request.getCookies()).filter(c->name().equals(c.getName())).toList();
        if(matches.size()>1) throw new RepairException(401,"REFRESH_TOKEN_INVALID","登录凭据异常，请重新登录");
        return matches.isEmpty()?null:matches.get(0).getValue();
    }
    public void write(HttpServletResponse response,TokenResponse token){
        response.addHeader(HttpHeaders.SET_COOKIE,cookie(token.refreshToken(),Duration.between(Instant.now(),token.refreshTokenExpiresAt()).isNegative()?Duration.ZERO:Duration.between(Instant.now(),token.refreshTokenExpiresAt())).toString());
    }
    public void clear(HttpServletResponse response){response.addHeader(HttpHeaders.SET_COOKIE,cookie("",Duration.ZERO).toString());}
    private ResponseCookie cookie(String value,Duration ttl){return ResponseCookie.from(name(),value).httpOnly(true).secure(secure).sameSite(sameSite).path("/").maxAge(ttl).build();}
}
