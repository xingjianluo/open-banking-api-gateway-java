package com.github.xingjianluo.openbankingapigatewayjava.model.api;

import java.util.Optional;

/**
 * 受支持的 API 版本。
 * <ul>
 *   <li>V2：当前版本，新增字段缺省时保持 V2 既有语义；</li>
 *   <li>V1：已废弃、语义不兼容，必须明确拒绝（VERSION_DEPRECATED），不静默降级；</li>
 *   <li>其他未知版本：VERSION_UNSUPPORTED。</li>
 * </ul>
 */
public enum ApiVersion {
    V2("2", true, false),
    V1("1", false, true);

    private final String headerValue;
    private final boolean supported;
    private final boolean deprecated;

    ApiVersion(String headerValue, boolean supported, boolean deprecated) {
        this.headerValue = headerValue;
        this.supported = supported;
        this.deprecated = deprecated;
    }

    public String headerValue() {
        return headerValue;
    }

    public boolean isSupported() {
        return supported;
    }

    public boolean isDeprecated() {
        return deprecated;
    }

    public static Optional<ApiVersion> fromHeader(String headerValue) {
        if (headerValue == null) {
            return Optional.empty();
        }
        String normalized = headerValue.trim();
        for (ApiVersion version : values()) {
            if (version.headerValue.equals(normalized)) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}
