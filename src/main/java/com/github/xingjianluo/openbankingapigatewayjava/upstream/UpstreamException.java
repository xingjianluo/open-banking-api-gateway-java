package com.github.xingjianluo.openbankingapigatewayjava.upstream;

/**
 * 模拟银行核心系统（下游依赖）抛出的内部异常。
 *
 * <p>该异常携带的内部信息（连接串、报文片段等）只能出现在服务端日志中，
 * 绝不能透传给合作方；统一由上层映射为稳定语义 {@code UPSTREAM_UNAVAILABLE}。</p>
 */
public class UpstreamException extends RuntimeException {

    private final String internalDetail;

    public UpstreamException(String internalDetail) {
        super("UPSTREAM INTERNAL FAILURE: " + internalDetail);
        this.internalDetail = internalDetail;
    }

    /** 仅供日志使用，禁止写入对外响应。 */
    public String internalDetail() {
        return internalDetail;
    }
}
