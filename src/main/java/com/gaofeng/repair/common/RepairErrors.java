package com.gaofeng.repair.common;

import com.gaofeng.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import jakarta.validation.ConstraintViolationException;

@Slf4j
@RestControllerAdvice
public class RepairErrors {
    public record Error(String code,String message,String requestId) {}
    public static Error body(String code,String message) { return new Error(code,message,RequestIds.current()); }
    @ExceptionHandler(RepairException.class) ResponseEntity<Error> repair(RepairException ex) {
        var response=ResponseEntity.status(ex.getStatus());
        if("READ_CACHE_BUSY".equals(ex.getCode()))response.header("Retry-After","1");
        return response.body(body(ex.getCode(),ex.getMessage()));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class) ResponseEntity<Error> tooLarge(Exception ex) {
        return ResponseEntity.status(413).body(body("FILE_TOO_LARGE","图片不能超过 5 MiB"));
    }
    @ExceptionHandler(BusinessException.class) ResponseEntity<Error> auth(BusinessException ex) {
        int status=switch(ex.getErrorCode()) {
            case INVALID_CREDENTIALS,UNAUTHORIZED,REFRESH_TOKEN_INVALID -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT,IDENTIFIER_EXISTS -> 409;
            case TOO_MANY_REQUESTS,VERIFICATION_RATE_LIMIT,VERIFICATION_DAILY_LIMIT -> 429;
            case SERVICE_UNAVAILABLE -> 503;
            default -> 400;
        };
        return ResponseEntity.status(status).body(body(ex.getErrorCode().getCode(),ex.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<Error> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(body("BAD_REQUEST",ex.getBindingResult().getFieldErrors().stream().findFirst().map(e->e.getDefaultMessage()).orElse("请求参数错误")));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,ConstraintViolationException.class,MissingRequestHeaderException.class,MissingServletRequestParameterException.class})
    ResponseEntity<Error> bad(Exception ex) { return ResponseEntity.badRequest().body(body("BAD_REQUEST","请求参数格式错误或缺少必要字段")); }
    @ExceptionHandler({NoHandlerFoundException.class,NoResourceFoundException.class}) ResponseEntity<Error> missing(Exception ex) { return ResponseEntity.status(404).body(body("NOT_FOUND","接口不存在")); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class) ResponseEntity<Error> method(Exception ex) { return ResponseEntity.status(405).body(body("METHOD_NOT_ALLOWED","请求方法不支持")); }
    @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<Error> duplicate(Exception ex) { return ResponseEntity.status(409).body(body("CONFLICT","编号或账号已存在，请检查后重试")); }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<Error> database(Exception ex) {
        log.error("Repair database failure requestId={} category={}",RequestIds.current(),ex.getClass().getSimpleName());
        return ResponseEntity.status(503).body(body("SERVICE_UNAVAILABLE","数据服务暂不可用，请稍后重试"));
    }
    @ExceptionHandler(Exception.class) ResponseEntity<Error> unexpected(Exception ex) {
        log.error("Repair request failed requestId={} category={}",RequestIds.current(),ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(body("INTERNAL_ERROR","服务暂时无法处理请求"));
    }
}
