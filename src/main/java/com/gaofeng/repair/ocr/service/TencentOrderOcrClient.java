package com.gaofeng.repair.ocr.service;
import com.fasterxml.jackson.databind.*;
import com.gaofeng.repair.ocr.api.dto.OcrDtos.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** GeneralAccurateOCR / 2018-11-19. Only explicit enabled configuration allows external calls. */
public final class TencentOrderOcrClient implements OrderOcrClient {
    static final String HOST="ocr.tencentcloudapi.com",ACTION="GeneralAccurateOCR",CONTENT_TYPE="application/json; charset=utf-8";
    private final boolean enabled; private final String secretId,secretKey,region; private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public TencentOrderOcrClient(boolean enabled,String secretId,String secretKey,String region,ObjectMapper json) { this.enabled=enabled; this.secretId=secretId; this.secretKey=secretKey; this.region=region; this.json=json; }
    public boolean configured() { return enabled && !secretId.isBlank() && !secretKey.isBlank() && region.matches("[a-z]{2}-[a-z]+(?:-[0-9]+)?"); }
    public String provider() { return "TENCENT_GENERAL_ACCURATE"; }
    public Recognition recognize(byte[] image) {
        if(!configured()) throw new OcrFailure("OCR_NOT_CONFIGURED",false,null);
        if(image.length==0 || image.length>5*1024*1024) throw new OcrFailure("IMAGE_SIZE_INVALID",false,null);
        try {
            String body=json.writeValueAsString(Map.of("ImageBase64",Base64.getEncoder().encodeToString(image))), timestamp=Long.toString(Instant.now().getEpochSecond());
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://"+HOST+"/")).timeout(Duration.ofSeconds(25))
                    .header("Content-Type",CONTENT_TYPE).header("X-TC-Action",ACTION).header("X-TC-Version","2018-11-19").header("X-TC-Region",region).header("X-TC-Timestamp",timestamp)
                    .header("Authorization",authorization(secretId,secretKey,"ocr",HOST,ACTION,Long.parseLong(timestamp),body))
                    .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
            HttpResponse<byte[]> response=http.send(request,info->new LimitedBody());
            if(response.statusCode()!=200) throw new OcrFailure("HTTP_"+response.statusCode(),response.statusCode()==429 || response.statusCode()>=500,null);
            return parse(json.readTree(response.body()));
        } catch(OcrFailure ex) { throw ex; }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new OcrFailure("INTERRUPTED",true,null); }
        catch(java.io.IOException ex) { throw new OcrFailure("TRANSPORT_ERROR",true,null); }
        catch(Exception ex) { throw new OcrFailure("INVALID_PROVIDER_RESPONSE",false,null); }
    }
    public static Recognition parse(JsonNode root) {
        JsonNode response=root.path("Response"); String requestId=response.path("RequestId").isTextual()?response.path("RequestId").asText():null;
        if(response.has("Error")) { String code=response.path("Error").path("Code").asText("PROVIDER_ERROR"); if(!code.matches("[A-Za-z0-9_.]{1,128}")) code="PROVIDER_ERROR";
            throw new OcrFailure(code,code.startsWith("RequestLimitExceeded") || code.equals("InternalError") || code.startsWith("InternalError."),requestId); }
        if(!response.path("TextDetections").isArray() || response.path("TextDetections").size()>2000) throw new OcrFailure("INVALID_PROVIDER_RESPONSE",false,requestId);
        List<Line> lines=new ArrayList<>(); for(JsonNode row:response.path("TextDetections")) {
            String text=row.path("DetectedText").asText(""); if(text.length()>2000) throw new OcrFailure("TEXT_LIMIT_EXCEEDED",false,requestId);
            Double confidence=row.path("Confidence").isNumber()?row.path("Confidence").asDouble():null; if(confidence!=null) confidence=confidence>=0 && confidence<=100?confidence/100d:null;
            List<Point> points=new ArrayList<>(); for(JsonNode point:row.path("Polygon")) { if(points.size()==4) break; points.add(new Point(point.path("X").asInt(),point.path("Y").asInt())); }
            lines.add(new Line(text,confidence,List.copyOf(points)));
        }
        return new Recognition(List.copyOf(lines),requestId,"provider:0..100; normalized:0..1");
    }
    public static String authorization(String id,String key,String service,String host,String action,long timestamp,String payload) {
        String date=Instant.ofEpochSecond(timestamp).atOffset(ZoneOffset.UTC).toLocalDate().toString(), scope=date+"/"+service+"/tc3_request";
        String canonical="POST\n/\n\ncontent-type:"+CONTENT_TYPE+"\nhost:"+host+"\nx-tc-action:"+action.toLowerCase(Locale.ROOT)+"\n\ncontent-type;host;x-tc-action\n"+sha256(payload);
        String toSign="TC3-HMAC-SHA256\n"+timestamp+"\n"+scope+"\n"+sha256(canonical);
        byte[] signing=hmac(hmac(hmac(("TC3"+key).getBytes(StandardCharsets.UTF_8),date),service),"tc3_request");
        return "TC3-HMAC-SHA256 Credential="+id+"/"+scope+", SignedHeaders=content-type;host;x-tc-action, Signature="+HexFormat.of().formatHex(hmac(signing,toSign));
    }
    private static byte[] hmac(byte[] key,String text) { try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(text.getBytes(StandardCharsets.UTF_8)); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    private static String sha256(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray(); private Flow.Subscription subscription; private int size;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) { for(ByteBuffer buffer:buffers) size+=buffer.remaining(); if(size>2*1024*1024) { subscription.cancel(); delegate.onError(new OcrFailure("RESPONSE_TOO_LARGE",false,null)); } else delegate.onNext(buffers); }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
