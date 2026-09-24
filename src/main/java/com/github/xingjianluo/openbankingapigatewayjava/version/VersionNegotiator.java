package com.github.xingjianluo.openbankingapigatewayjava.version;

import com.github.xingjianluo.openbankingapigatewayjava.error.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.model.api.ApiVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 版本协商：缺省回退当前 V2；V1 拒绝为 DEPRECATED；未知版本拒绝为 UNSUPPORTED。 */
@Component
public class VersionNegotiator {

    private static final Logger log = LoggerFactory.getLogger(VersionNegotiator.class);

    /**
     * 解析 X-Api-Version 请求头。
     * 未携带请求头时按当前版本 V2 处理（保持既有语义）；
     * V1 已废弃、语义不兼容，明确拒绝 VERSION_DEPRECATED；
     * 其他无法识别的版本号拒绝 VERSION_UNSUPPORTED。
     */
    public ApiVersion negotiate(String versionHeader) {
        if (versionHeader == null || versionHeader.isBlank()) {
            log.info("版本判定: versionHeader 缺省 -> 回退当前版本 V2（保持既有语义）");
            return ApiVersion.V2;
        }
        ApiVersion version = ApiVersion.fromHeader(versionHeader)
                .orElseThrow(() -> {
                    log.warn("版本判定: versionHeader='{}' 无法识别 -> VERSION_UNSUPPORTED", versionHeader);
                    return new ApiException(ErrorCode.VERSION_UNSUPPORTED, "版本号: " + versionHeader);
                });
        if (version.isDeprecated()) {
            log.warn("版本判定: versionHeader='{}' 命中已废弃版本 {} -> VERSION_DEPRECATED（不静默降级）",
                    versionHeader, version);
            throw new ApiException(ErrorCode.VERSION_DEPRECATED, "版本号: " + versionHeader);
        }
        log.info("版本判定: versionHeader='{}' -> {}", versionHeader, version);
        return version;
    }
}
