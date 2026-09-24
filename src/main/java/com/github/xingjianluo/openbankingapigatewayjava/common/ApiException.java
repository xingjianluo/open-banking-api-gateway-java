package com.github.xingjianluo.openbankingapigatewayjava.common;

/**
 * 全链路业务异常：只携带稳定的 {@link ErrorCode} 与一段"可解释但不泄密"的判定依据。
 *
 * <p>{@code reason} 用于向调用方解释为何被拒绝（例如缺少哪个 scope、
 * 过期时间是多少），不包含堆栈、SQL、下游报文等内部细节。</p>
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String reason;

    public ApiException(ErrorCode errorCode, String reason) {
        super(errorCode.name() + ": " + reason);
        this.errorCode = errorCode;
        this.reason = reason;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String reason() {
        return reason;
    }
}
