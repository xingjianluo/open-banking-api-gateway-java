package com.github.xingjianluo.openbankingapigatewayjava.version;

/**
 * 已发布过的 API 版本登记。
 *
 * <p>兼容策略：</p>
 * <ul>
 *   <li>{@link #V1}：基线版本，缺省请求头时默认按 V1 处理；</li>
 *   <li>{@link #V2}：当前版本。相对 V1 仅<b>新增可选字段</b>（如 payment.priority），
 *       缺省时取默认值并保持 V1 语义，因此 V1 请求可被 V2 实现安全处理；</li>
 *   <li>{@link #V0}：曾经存在但已废弃的版本，其语义与当前版本不兼容，
 *       必须明确拒绝（VERSION_DEPRECATED），不做静默降级。</li>
 * </ul>
 *
 * <p>从未发布过的版本号（如 "3"、"99"）在 {@link VersionService} 中解析失败，
 * 映射为 VERSION_UNSUPPORTED。</p>
 */
public enum ApiVersion {

    /** 已废弃且语义不兼容：明确拒绝。 */
    V0("0", false),
    /** 基线版本，支持。 */
    V1("1", true),
    /** 当前版本，向后兼容 V1。 */
    V2("2", true);

    private final String headerValue;
    private final boolean supported;

    ApiVersion(String headerValue, boolean supported) {
        this.headerValue = headerValue;
        this.supported = supported;
    }

    public String headerValue() {
        return headerValue;
    }

    public boolean supported() {
        return supported;
    }
}
