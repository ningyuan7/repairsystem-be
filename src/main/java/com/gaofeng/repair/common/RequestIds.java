package com.gaofeng.repair.common;
import org.slf4j.MDC;
import java.util.UUID;
public final class RequestIds {
    private RequestIds() {}
    public static String current() { String id=MDC.get("requestId"); return id==null?UUID.randomUUID().toString():id; }
}
