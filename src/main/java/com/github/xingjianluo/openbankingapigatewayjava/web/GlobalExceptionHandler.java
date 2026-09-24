package com.github.xingjianluo.openbankingapigatewayjava.web;

import com.github.xingjianluo.openbankingapigatewayjava.error.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：所有对外错误使用稳定错误码，不泄露内部异常细节。
 * 内部堆栈仅记录在服务端日志中，并以 traceId 关联，便于排查。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        String traceId = UUID.randomUUID().toString();
        ErrorCode errorCode = ex.getErrorCode();
        // 服务端保留判定细节，对外只给稳定 code/message
        log.warn("对外错误 traceId={} code={} 判定细节={}", traceId, errorCode.code(), ex.getMessage());
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ErrorResponse.of(errorCode, traceId));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        String traceId = UUID.randomUUID().toString();
        log.warn("对外错误 traceId={} code=VALIDATION_FAILED 请求体无法解析", traceId);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        // 完整堆栈仅留在服务端日志，对外统一 INTERNAL_ERROR，不透出任何内部信息
        log.error("未预期内部异常 traceId=" + traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, traceId));
    }
}
