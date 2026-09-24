package com.github.xingjianluo.openbankingapigatewayjava.common;

import org.springframework.http.HttpStatus;

/**
 * 对外稳定的错误语义集合。
 *
 * <p>每一个枚举值都代表一类对调用方稳定的拒绝/失败原因，{@link #httpStatus} 给出对应的
 * HTTP 状态码。内部异常细节（如堆栈、下游报文）不会透出给调用方，只会映射为这里的稳定语义。</p>
 *
 * <ul>
 *   <li>同意与访问控制：CONSENT_NOT_FOUND / CONSENT_EXPIRED / CONSENT_REVOKED /
 *       SCOPE_INSUFFICIENT / RESOURCE_NOT_FOUND 五种拒绝原因互不相同；</li>
 *   <li>幂等：IDEMPOTENCY_IN_FLIGHT / IDEMPOTENCY_CONFLICT / IDEMPOTENCY_KEY_EXPIRED；</li>
 *   <li>版本：VERSION_UNSUPPORTED / VERSION_DEPRECATED；</li>
 *   <li>配额与依赖：QUOTA_EXCEEDED / UPSTREAM_UNAVAILABLE / INTERNAL_ERROR。</li>
 * </ul>
 */
public enum ErrorCode {

    /** 请求缺少必要头/字段或字段格式不合法。 */
    VALIDATION_FAILED("请求校验未通过", HttpStatus.BAD_REQUEST),

    /** 合作方标识缺失或合作方不存在（本地模拟的访问入口校验）。 */
    PARTNER_UNKNOWN("合作方不存在或未提供 X-Partner-Id", HttpStatus.UNAUTHORIZED),

    /** 同意记录不存在：与"资源不存在"是不同的拒绝原因。 */
    CONSENT_NOT_FOUND("同意不存在或未提供 X-Consent-Id", HttpStatus.FORBIDDEN),

    /** 同意已过有效期（expiresAt &lt; 当前时间）。 */
    CONSENT_EXPIRED("同意已过期", HttpStatus.FORBIDDEN),

    /** 同意已被合作方/客户撤销。 */
    CONSENT_REVOKED("同意已撤销", HttpStatus.FORBIDDEN),

    /** 同意或合作方被授予的权限范围不覆盖本次操作所需 scope。 */
    SCOPE_INSUFFICIENT("权限范围不足", HttpStatus.FORBIDDEN),

    /** 同意合法但所访问的业务资源不存在（或不属于该合作方）。 */
    RESOURCE_NOT_FOUND("资源不存在", HttpStatus.NOT_FOUND),

    /** 同一幂等键的并发请求：首个请求仍在处理中，本次请求未产生副作用。 */
    IDEMPOTENCY_IN_FLIGHT("相同幂等键的请求正在处理中", HttpStatus.CONFLICT),

    /** 同键重放但请求体指纹与首次不一致：拒绝，不覆盖、不重复执行。 */
    IDEMPOTENCY_CONFLICT("幂等键对应的请求内容不一致", HttpStatus.CONFLICT),

    /** 幂等键超过保留窗口后被复用：必须更换新键，拒绝复用。 */
    IDEMPOTENCY_KEY_EXPIRED("幂等键已超过保留期，不允许复用", HttpStatus.GONE),

    /** 请求声明了系统从未发布过的 API 版本。 */
    VERSION_UNSUPPORTED("不支持的 API 版本", HttpStatus.BAD_REQUEST),

    /** 请求声明的版本曾发布但已废弃且语义不兼容，拒绝静默降级。 */
    VERSION_DEPRECATED("该 API 版本已废弃且不兼容", HttpStatus.BAD_REQUEST),

    /** 合作方调用配额已用尽。 */
    QUOTA_EXCEEDED("调用配额已用尽", HttpStatus.TOO_MANY_REQUESTS),

    /** 下游银行核心依赖异常：对外只暴露稳定语义，不透出内部细节。 */
    UPSTREAM_UNAVAILABLE("下游服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE),

    /** 兜底内部错误。 */
    INTERNAL_ERROR("系统内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String message, HttpStatus httpStatus) {
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
