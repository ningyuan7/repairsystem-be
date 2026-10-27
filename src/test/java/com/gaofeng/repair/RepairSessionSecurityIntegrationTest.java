package com.gaofeng.repair;
import com.gaofeng.auth.verification.VerificationCodeStore;
import com.gaofeng.repair.access.service.BrowserAuthCookies;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.web.MockHttpServletResponse;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.gaofeng.repair.RepairIdentityIntegrationTest.*;

/** Real isolated DB/Redis; captures cookies as an HTTP client, never sends SMTP. */
@EnabledIfSystemProperty(named="repair.integration",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepairSessionSecurityIntegrationTest {
    @BeforeAll static void prepare() throws Exception { RepairIdentityIntegrationTest.prepareWith(Map.of()); }
    @AfterAll static void cleanup() throws Exception { RepairIdentityIntegrationTest.cleanup(); }
    static String cookie(Result result){return result.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];}
    static String token(Result result){return result.body().path("token").path("accessToken").asText();}
    static HttpResponse<String> cookiePost(String path,String cookie) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base+path)).header("X-Repair-CSRF","1").header("Cookie",cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test @Order(1) void cookieAndCsrfContract() throws Exception {
        Result login=login("hq@repair.example");assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().path("token").has("refreshToken")).isFalse();
        assertThat(login.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly","SameSite=Lax","Path=/").doesNotContain("Domain=");
        var missing=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/logout")).header("Cookie",cookie(login)).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(403);assertThat(json.readTree(missing.body()).path("code").asText()).isEqualTo("CSRF_REJECTED");
        var hostile=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/logout")).header("Cookie",cookie(login)).header("X-Repair-CSRF","1").header("Origin","https://untrusted.example").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(hostile.statusCode()).isEqualTo(403);
        assertThat(request("GET","/api/v1/repair/session",null,token(login),null).status()).isEqualTo(200);
        var cors=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/token/refresh")).method("OPTIONS",HttpRequest.BodyPublishers.noBody()).header("Origin","http://127.0.0.1:4877").header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","X-Repair-CSRF").build(),HttpResponse.BodyHandlers.ofString());
        assertThat(cors.statusCode()).isEqualTo(200);assertThat(cors.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
        var secure=new BrowserAuthCookies(true,"Lax");var output=new MockHttpServletResponse();secure.clear(output);
        assertThat(output.getHeader("Set-Cookie")).contains("__Host-repair_refresh=","Secure","HttpOnly","Path=/","Max-Age=0");
        assertThatThrownBy(()->new BrowserAuthCookies(false,"None")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test @Order(2) void logoutRevokesCurrentSessionIncludingAllRotatedAccessTokensButKeepsOtherDevice() throws Exception {
        var first=login("hq@repair.example");var other=login("hq@repair.example");
        var rotated=cookiePost("/api/v1/auth/token/refresh",cookie(first));assertThat(rotated.statusCode()).isEqualTo(200);
        String access=json.readTree(rotated.body()).path("accessToken").asText();
        assertThat(json.readTree(rotated.body()).has("refreshToken")).isFalse();
        String next=rotated.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        assertThat(cookiePost("/api/v1/auth/token/refresh",cookie(first)).statusCode()).isEqualTo(401);
        assertThat(request("GET","/api/v1/repair/session",null,token(first),null).status()).isEqualTo(200);
        var logout=cookiePost("/api/v1/auth/logout",next);assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
        for(String stale:List.of(token(first),access))assertThat(request("GET","/api/v1/repair/session",null,stale,null).status()).isEqualTo(401);
        assertThat(cookiePost("/api/v1/auth/token/refresh",next).statusCode()).isEqualTo(401);
        assertThat(cookiePost("/api/v1/auth/logout",next).statusCode()).isEqualTo(204);
        assertThat(request("GET","/api/v1/repair/session",null,token(other),null).status()).isEqualTo(200);
        assertThat(cookiePost("/api/v1/auth/token/refresh",cookie(other)).statusCode()).isEqualTo(200);
        // Missing/blocked refresh cookies must not leave the visible access session active after logout.
        var fallback=login("hq@repair.example");
        var fallbackLogout=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/logout")).header("X-Repair-CSRF","1").header("Authorization","Bearer "+token(fallback)).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(fallbackLogout.statusCode()).isEqualTo(204);
        assertThat(request("GET","/api/v1/repair/session",null,token(fallback),null).status()).isEqualTo(401);
        assertThat(cookiePost("/api/v1/auth/token/refresh",cookie(fallback)).statusCode()).isEqualTo(401);
    }
    @Test @Order(3) void passwordResetImmediatelyRevokesEveryDeviceAndRequiresNewPassword() throws Exception {
        String email="reset-"+database+"@repair.example";inviteAndActivate(email,"ENGINEER",List.of());
        var first=login(email);var second=login(email);String nextPassword="ResetFixture4680!";
        app.getBean(VerificationCodeStore.class).saveCode("RESET_PASSWORD",email,"164208",Duration.ofMinutes(2),5);
        var reset=request("POST","/api/v1/auth/password/reset",Map.of("identifierType","EMAIL","identifier",email,"code","164208","newPassword",nextPassword),null,null);
        assertThat(reset.status()).isEqualTo(204);
        for(var device:List.of(first,second)){
            assertThat(request("GET","/api/v1/repair/session",null,token(device),null).status()).isEqualTo(401);
            assertThat(cookiePost("/api/v1/auth/token/refresh",cookie(device)).statusCode()).isEqualTo(401);
        }
        assertThat(login(email).status()).isEqualTo(401);
        var fresh=request("POST","/api/v1/auth/login",Map.of("identifierType","EMAIL","identifier",email,"password",nextPassword),null,null);
        assertThat(fresh.status()).isEqualTo(200);assertThat(request("GET","/api/v1/repair/session",null,token(fresh),null).status()).isEqualTo(200);
    }
    @Test @Order(4) void concurrentLogoutAndRefreshCannotResurrectSession() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try{for(int i=0;i<6;i++){
            var login=login("hq@repair.example");String cookie=cookie(login);var go=new CountDownLatch(1);
            var refresh=pool.submit(()->{go.await();return cookiePost("/api/v1/auth/token/refresh",cookie);});
            var logout=pool.submit(()->{go.await();return cookiePost("/api/v1/auth/logout",cookie);});go.countDown();
            var refreshed=refresh.get(15,TimeUnit.SECONDS);assertThat(logout.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(refreshed.statusCode()).isIn(200,401);
            assertThat(request("GET","/api/v1/repair/session",null,token(login),null).status()).isEqualTo(401);
            if(refreshed.statusCode()==200){
                String token=json.readTree(refreshed.body()).path("accessToken").asText();
                assertThat(request("GET","/api/v1/repair/session",null,token,null).status()).isEqualTo(401);
                assertThat(cookiePost("/api/v1/auth/token/refresh",refreshed.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0]).statusCode()).isEqualTo(401);
            }
        }}finally{pool.shutdownNow();}
    }
    @Test @Order(5) void browserCookieRestorationStorageIsolationAndLogout() throws Exception {
        if(Boolean.getBoolean("repair.browser")) RepairBrowserSupport.run("repair-session-browser-test.cjs",Path.of("target/repair-session-browser/run.log"),base);
    }
}
