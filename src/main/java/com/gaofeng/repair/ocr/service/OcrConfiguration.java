package com.gaofeng.repair.ocr.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration
@EnableScheduling
public class OcrConfiguration {
    @Bean @ConditionalOnMissingBean(OrderOcrClient.class)
    OrderOcrClient orderOcrClient(@Value("${repair.ocr.provider:LOCAL}") String provider,
                                @Value("${repair.ocr.local-url:http://127.0.0.1:8601/v1/recognize}") String localUrl,
                                @Value("${repair.ocr.enabled:false}") boolean enabled,@Value("${repair.ocr.secret-id:}") String id,
                                @Value("${repair.ocr.secret-key:}") String key,@Value("${repair.ocr.region:ap-guangzhou}") String region,ObjectMapper json) {
        return switch(provider.toUpperCase(java.util.Locale.ROOT)) {
            case "LOCAL" -> new LocalOrderOcrClient(enabled,localUrl,json);
            case "TENCENT" -> new TencentOrderOcrClient(enabled,id,key,region,json);
            default -> throw new IllegalArgumentException("Unsupported OCR provider; use LOCAL or TENCENT");
        };
    }
}
