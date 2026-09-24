package com.github.xingjianluo.openbankingapigatewayjava.version;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;

/**
 * API 版本协商。
 *
 * <p>请求通过 {@code X-Api-Version} 头声明版本（也接受 "v1" 风格前缀）：</p>
 * <ul>
 *   <li>缺省：按基线 {@link ApiVersion#V1} 处理（老调用方零改造）；</li>
 *   <li>命中已登记但废弃的版本（v0）：{@code VERSION_DEPRECATED}；</li>
 *   <li>命中从未发布的版本：{@code VERSION_UNSUPPORTED}；</li>
 *   <li>空字符串：视为非法声明，{@code VERSION_UNSUPPORTED}。</li>
 * </ul>
 */
@Service
public class VersionService {

    private static final Logger log = LoggerFactory.getLogger(VersionService.class);

    /** 解析请求头；缺省返回基线版本。 */
    public ApiVersion resolve(String header) {
        String raw = header == null ? null : header.trim();
        if (raw == null || raw.isEmpty()) {
            log.info("版本协商 请求输入 X-Api-Version=<缺省> 判定依据=缺省回落基线V1（保持既有语义）");
            return ApiVersion.V1;
        }

        String normalized = raw.toLowerCase().startsWith("v") ? raw.substring(1) : raw;
        ApiVersion match = Arrays.stream(ApiVersion.values())
                .filter(v -> v.headerValue().equals(normalized))
                .findFirst()
                .orElse(null);

        if (match == null) {
            log.info("版本拒绝 请求输入 X-Api-Version={} 判定依据=该版本从未发布(UNSUPPORTED)", raw);
            throw new ApiException(ErrorCode.VERSION_UNSUPPORTED,
                    "X-Api-Version=" + raw + " 从未发布，支持的版本: v1、v2");
        }
        if (!match.supported()) {
            log.info("版本拒绝 请求输入 X-Api-Version={} 判定依据=版本已废弃且语义不兼容(DEPRECATED)，拒绝静默降级", raw);
            throw new ApiException(ErrorCode.VERSION_DEPRECATED,
                    "X-Api-Version=" + raw + " 已废弃且不兼容，请升级至 v2");
        }
        log.info("版本通过 请求输入 X-Api-Version={} 判定依据=版本受支持 {}", raw, match);
        return match;
    }
}
