package com.github.xingjianluo.openbankingapigatewayjava.error;

import org.springframework.http.HttpStatus;

/**
 * 稳定对外错误码。每个错误码对应唯一的、可解释的拒绝原因。
 */
public enum ErrorCode {
    CONSENT_NOT_FOUND("CONSENT_NOT_FOUND", "同意不存在或未找到", HttpStatus.NOT_FOUND),
    CONSENT_EXPIRED("CONSENT_EXPIRED", "同意已过期", HttpStatus.FORBIDDEN),
    CONSENT_REVOKED("CONSENT_REVOKED", "同意已被合作方撤销", HttpStatus.FORBIDDEN),
    SCOPE_INSUFFICIENT("SCOPE_INSUFFICIENT", "合作方权限或同意范围不足", HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "目标资源不存在", HttpStatus.NOT_FOUND),
    PARTNER_UNKNOWN("PARTNER_UNKNOWN", "合作方身份未知", HttpStatus.UNAUTHORIZED),
    VERSION_UNSUPPORTED("VERSION_UNSUPPORTED", "API 版本不受支持", HttpStatus.UPGRADE_REQUIRED),
    VERSION_DEPRECATED("VERSION_DEPRECATED", "API 版本已废弃且语义不兼容", HttpStatus.UPGRADE_REQUIRED),
    FIELD_INCOMPATIBLE("FIELD_INCOMPATIBLE", "字段语义与请求版本不兼容", HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "同幂等键请求内容不一致", HttpStatus.CONFLICT),
    IDEMPOTENCY_EXPIRED("IDEMPOTENCY_EXPIRED", "幂等键已超期，禁止复用", HttpStatus.GONE),
    IDEMPOTENCY_IN_PROGRESS("IDEMPOTENCY_IN_PROGRESS", "相同幂等键请求正在处理中", HttpStatus.CONFLICT),
    QUOTA_EXCEEDED("QUOTA_EXCEEDED", "合作方调用配额已用尽", HttpStatus.TOO_MANY_REQUESTS),
    DOWNSTREAM_FAILURE("DOWNSTREAM_FAILURE", "下游银行服务处理失败", HttpStatus.BAD_GATEWAY),
    VALIDATION_FAILED("VALIDATION_FAILED", "请求参数校验失败", HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
