package com.gaofeng.repair.common;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
@Component
@RequiredArgsConstructor
public class JsonSupport {
    private final ObjectMapper mapper;
    public String write(Object object) {
        try { return mapper.writeValueAsString(object); } catch(Exception ex) { throw new IllegalStateException("Cannot serialize repair response",ex); }
    }
    public <T> T read(String json,Class<T> type) {
        try { return mapper.readValue(json,type); } catch(Exception ex) { throw new IllegalStateException("Cannot read repair data",ex); }
    }
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
