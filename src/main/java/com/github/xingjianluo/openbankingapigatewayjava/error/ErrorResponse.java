package com.github.xingjianluo.openbankingapigatewayjava.error;

import java.time.OffsetDateTime;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(
        String code,
        String message,
        OffsetDateTime timestamp,
        String traceId
) {
    public static ErrorResponse of(ErrorCode errorCode, String traceId) {
        return new ErrorResponse(errorCode.code(), errorCode.message(), OffsetDateTime.now(), traceId);
    }
}
