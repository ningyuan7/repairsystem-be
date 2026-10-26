package com.gaofeng.repair.ocr.service;

import com.fasterxml.jackson.databind.*;
import com.gaofeng.repair.ocr.api.dto.OcrDtos.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Calls only the loopback OCR model service; images never go to a cloud provider. */
public final class LocalOrderOcrClient implements OrderOcrClient {
    private final boolean enabled;
    private final URI endpoint;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public LocalOrderOcrClient(boolean enabled,String endpoint,ObjectMapper json) {
        this.enabled=enabled;this.json=json;
        URI parsed;
        try { parsed=URI.create(endpoint); } catch(IllegalArgumentException exception) { parsed=null; }
        this.endpoint=parsed;
    }
    public boolean configured() {
        return enabled&&endpoint!=null&&"http".equals(endpoint.getScheme())
            &&Set.of("127.0.0.1","localhost","[::1]").contains(Objects.toString(endpoint.getHost(),""))
            &&endpoint.getUserInfo()==null&&endpoint.getQuery()==null&&endpoint.getFragment()==null
            &&"/v1/recognize".equals(endpoint.getPath());
    }
    public String provider() {return "LOCAL_RAPIDOCR";}
    public Recognition recognize(byte[] image) {
        if(!configured())throw new OcrFailure("OCR_NOT_CONFIGURED",false,null);
        if(image.length==0||image.length>5*1024*1024)throw new OcrFailure("IMAGE_SIZE_INVALID",false,null);
        try {
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(45))
                .header("Content-Type","application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray(image)).build();
            var response=http.send(request,info->new LimitedBody());
            if(response.statusCode()!=200)throw new OcrFailure("LOCAL_HTTP_"+response.statusCode(),response.statusCode()==429||response.statusCode()>=500,null);
            return parse(json.readTree(response.body()));
        } catch(OcrFailure exception){throw exception;}
        catch(InterruptedException exception){Thread.currentThread().interrupt();throw new OcrFailure("INTERRUPTED",true,null);}
        catch(com.fasterxml.jackson.core.JsonProcessingException exception){throw new OcrFailure("INVALID_LOCAL_OCR_RESPONSE",false,null);}
        catch(java.io.IOException exception){
            if(exception.getCause() instanceof OcrFailure failure)throw failure;
            throw new OcrFailure("LOCAL_TRANSPORT_ERROR",true,null);
        }
        catch(Exception exception){throw new OcrFailure("INVALID_LOCAL_OCR_RESPONSE",false,null);}
    }
    public static Recognition parse(JsonNode root) {
        if(root==null||!root.path("lines").isArray()||root.path("lines").size()>2000)
            throw new OcrFailure("INVALID_LOCAL_OCR_RESPONSE",false,null);
        String requestId=root.path("requestId").asText("");
        if(!requestId.matches("[A-Za-z0-9_-]{1,128}"))requestId=null;
        List<Line> lines=new ArrayList<>();
        for(JsonNode row:root.path("lines")) {
            if(!row.path("text").isTextual()||row.path("text").asText().length()>2000)
                throw new OcrFailure("INVALID_LOCAL_OCR_RESPONSE",false,requestId);
            Double confidence=row.path("confidence").isNumber()?row.path("confidence").asDouble():null;
            if(confidence!=null&&(!Double.isFinite(confidence)||confidence<0||confidence>1))confidence=null;
            List<Point> polygon=new ArrayList<>();
            if(row.path("polygon").isArray())for(JsonNode point:row.path("polygon")) {
                if(polygon.size()==4)break;
                if(!point.path("x").canConvertToInt()||!point.path("y").canConvertToInt())
                    throw new OcrFailure("INVALID_LOCAL_OCR_RESPONSE",false,requestId);
                polygon.add(new Point(point.path("x").asInt(),point.path("y").asInt()));
            }
            lines.add(new Line(row.path("text").asText(),confidence,List.copyOf(polygon)));
        }
        return new Recognition(List.copyOf(lines),requestId,"local-model:0..1");
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;private int size;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers)size+=buffer.remaining();
            if(size>2*1024*1024){subscription.cancel();delegate.onError(new OcrFailure("RESPONSE_TOO_LARGE",false,null));}
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error){delegate.onError(error);}
        public void onComplete(){delegate.onComplete();}
    }
}
