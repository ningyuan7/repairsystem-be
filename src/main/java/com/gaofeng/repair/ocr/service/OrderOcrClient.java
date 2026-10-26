package com.gaofeng.repair.ocr.service;
import com.gaofeng.repair.ocr.api.dto.OcrDtos.Recognition;
public interface OrderOcrClient {
    boolean configured();
    String provider();
    Recognition recognize(byte[] image) throws OcrFailure;
    final class OcrFailure extends RuntimeException {
        private final String code,requestId; private final boolean transientFailure;
        public OcrFailure(String code,boolean transientFailure,String requestId) { super(code); this.code=code; this.transientFailure=transientFailure; this.requestId=requestId; }
        public String code() { return code; }
        public String requestId() { return requestId; }
        public boolean transientFailure() { return transientFailure; }
    }
}
