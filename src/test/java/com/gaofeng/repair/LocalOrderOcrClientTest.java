package com.gaofeng.repair;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaofeng.repair.ocr.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class LocalOrderOcrClientTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void privateImagesCanOnlyBeSentToLiteralLoopbackWithoutRedirectsOrUrlCredentials()throws Exception {
        for(String url:new String[]{"https://127.0.0.1:8601/v1/recognize","http://example.com/v1/recognize","http://192.168.1.10/v1/recognize","http://user@127.0.0.1/v1/recognize","http://127.0.0.1/v1/recognize?target=x","not-a-uri"})
            assertThat(new LocalOrderOcrClient(true,url,JSON).configured()).describedAs(url).isFalse();
        try(var endpoint=new Endpoint(302,"private provider details")) {
            assertThatThrownBy(()->endpoint.client().recognize(new byte[]{1,2}))
                .isInstanceOfSatisfying(OrderOcrClient.OcrFailure.class,error->{assertThat(error.code()).isEqualTo("LOCAL_HTTP_302");assertThat(error.transientFailure()).isFalse();assertThat(error.getMessage()).doesNotContain("private");});
        }
    }
    @Test void localHttpContractPreservesEvidenceCoordinatesAndConfidenceWithoutCloudNormalization()throws Exception {
        try(var endpoint=new Endpoint(200,"""
            {"requestId":"local-test","lines":[{"text":"商品：温控器","confidence":0.97,"polygon":[{"x":10,"y":20},{"x":40,"y":20},{"x":40,"y":50},{"x":10,"y":50}]}]}
            """)) {
            byte[] image={1,2,3,4};var result=endpoint.client().recognize(image);
            assertThat(endpoint.body.get()).containsExactly(image);
            assertThat(result.lines()).hasSize(1);assertThat(result.lines().getFirst().text()).isEqualTo("商品：温控器");
            assertThat(result.lines().getFirst().confidence()).isEqualTo(0.97);
            assertThat(result.lines().getFirst().polygon()).hasSize(4);assertThat(result.requestId()).isEqualTo("local-test");
        }
    }
    @Test void modelOutageIsRetryableButOversizedOrMalformedResponsesAreNot()throws Exception {
        try(var endpoint=new Endpoint(503,"private inference trace")) {
            assertThatThrownBy(()->endpoint.client().recognize(new byte[]{1})).isInstanceOfSatisfying(OrderOcrClient.OcrFailure.class,error->{assertThat(error.transientFailure()).isTrue();assertThat(error.code()).isEqualTo("LOCAL_HTTP_503");});
        }
        try(var endpoint=new Endpoint(200,"x".repeat(2*1024*1024+1))) {
            assertThatThrownBy(()->endpoint.client().recognize(new byte[]{1})).isInstanceOfSatisfying(OrderOcrClient.OcrFailure.class,error->{assertThat(error.code()).isEqualTo("RESPONSE_TOO_LARGE");assertThat(error.transientFailure()).isFalse();});
        }
        assertThatThrownBy(()->LocalOrderOcrClient.parse(JSON.readTree("{\"lines\":[{\"text\":42}]}")))
            .isInstanceOf(OrderOcrClient.OcrFailure.class);
    }
    private static final class Endpoint implements AutoCloseable {
        final HttpServer server;final AtomicReference<byte[]> body=new AtomicReference<>();
        Endpoint(int status,String response)throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/recognize",exchange->{body.set(exchange.getRequestBody().readAllBytes());byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}});
            server.start();
        }
        LocalOrderOcrClient client(){return new LocalOrderOcrClient(true,"http://127.0.0.1:"+server.getAddress().getPort()+"/v1/recognize",JSON);}
        public void close(){server.stop(0);}
    }
}
