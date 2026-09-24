package com.github.xingjianluo.openbankingapigatewayjava.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把所有异常映射为稳定的 {@link ErrorResponse} 对外语义。
 *
 * <p>映射规则：</p>
 * <ul>
 *   <li>{@link ApiException}：按其携带的 {@link ErrorCode} 返回对应 HTTP 状态；</li>
 *   <li>其它未预期异常：统一映射为 INTERNAL_ERROR / 500，响应中不含任何内部细节，
 *       堆栈仅记录在服务端日志。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        ErrorCode code = ex.errorCode();
        log.warn("请求被拒绝 code={} reason={}", code.name(), ex.reason());
        return ResponseEntity.status(code.httpStatus())
                .body(ErrorResponse.of(code, ex.reason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // 内部细节只进日志，不进入对外响应
        log.error("未预期的内部异常，映射为 INTERNAL_ERROR", ex);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.httpStatus())
                .body(ErrorResponse.of(code, "请稍后重试或联系网关运营方"));
    }
}
