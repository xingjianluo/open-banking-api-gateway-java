package com.github.xingjianluo.openbankingapigatewayjava.common;

import java.time.OffsetDateTime;

/**
 * 统一错误响应体。字段保持跨版本稳定，不携带内部异常细节。
 *
 * @param code      稳定错误码（见 {@link ErrorCode}），调用方应据此编程处理
 * @param message   错误码对应的固定说明
 * @param reason    本次判定的人类可读依据（如缺少的 scope、过期时间），便于解释与排障
 * @param timestamp 服务端产生错误的时间（UTC）
 */
public record ErrorResponse(String code, String message, String reason, OffsetDateTime timestamp) {

    public static ErrorResponse of(ErrorCode code, String reason) {
        return new ErrorResponse(code.name(), code.message(), reason, OffsetDateTime.now());
    }
}
