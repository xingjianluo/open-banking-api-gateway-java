package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.xingjianluo.openbankingapigatewayjava.error.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.model.api.ApiVersion;
import com.github.xingjianluo.openbankingapigatewayjava.version.VersionNegotiator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 版本兼容规则测试：缺省回退 V2、V1 废弃拒绝、未知版本拒绝，且两种拒绝原因可区分。 */
class VersionNegotiatorTest {

    private static final Logger log = LoggerFactory.getLogger(VersionNegotiatorTest.class);

    private final VersionNegotiator negotiator = new VersionNegotiator();

    @Test
    @DisplayName("缺省版本头 -> 回退当前 V2（保持既有语义）")
    void missingHeader_defaultsToV2() {
        log.info("[版本] 输入: X-Api-Version=null / 空串");
        assertEquals(ApiVersion.V2, negotiator.negotiate(null));
        assertEquals(ApiVersion.V2, negotiator.negotiate("  "));
        log.info("[版本] 判定依据: 缺省不报错，按当前版本 V2 处理");
    }

    @Test
    @DisplayName("V2 -> 放行")
    void v2Accepted() {
        log.info("[版本] 输入: X-Api-Version=2");
        assertEquals(ApiVersion.V2, negotiator.negotiate("2"));
    }

    @Test
    @DisplayName("V1 已废弃、语义不兼容 -> VERSION_DEPRECATED（不静默降级）")
    void v1RejectedAsDeprecated() {
        log.info("[版本] 输入: X-Api-Version=1（已废弃）");
        ApiException ex = assertThrows(ApiException.class, () -> negotiator.negotiate("1"));
        assertEquals(ErrorCode.VERSION_DEPRECATED, ex.getErrorCode());
        log.info("[版本] 判定依据: 命中枚举 V1(deprecated=true) -> {}", ex.getErrorCode().code());
    }

    @Test
    @DisplayName("未知版本 -> VERSION_UNSUPPORTED，与废弃原因可区分")
    void unknownVersionRejected() {
        log.info("[版本] 输入: X-Api-Version=99（未知）");
        ApiException ex = assertThrows(ApiException.class, () -> negotiator.negotiate("99"));
        assertEquals(ErrorCode.VERSION_UNSUPPORTED, ex.getErrorCode());
        log.info("[版本] 判定依据: 无法解析为已知版本 -> {}", ex.getErrorCode().code());
    }
}
