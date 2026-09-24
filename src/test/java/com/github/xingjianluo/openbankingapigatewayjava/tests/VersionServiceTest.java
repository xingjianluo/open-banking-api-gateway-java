package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.version.ApiVersion;
import com.github.xingjianluo.openbankingapigatewayjava.version.VersionService;

/**
 * 版本兼容规则单元测试。请求头输入与判定依据打印到日志。
 */
class VersionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(VersionServiceTest.class);

    private final VersionService service = new VersionService();

    @Test
    @DisplayName("缺省版本头：回落 V1 基线，保持老调用方语义")
    void missingHeaderDefaultsV1() {
        log.info("[用例输入] X-Api-Version=null / '' / '   '");
        assertEquals(ApiVersion.V1, service.resolve(null));
        assertEquals(ApiVersion.V1, service.resolve(""));
        assertEquals(ApiVersion.V1, service.resolve("   "));
        log.info("[判定依据] 三种缺省形态均回落 V1");
    }

    @Test
    @DisplayName("v1 与 v2（含大小写/v前缀）均受支持")
    void supportedVersions() {
        log.info("[用例输入] X-Api-Version=1,v1,V2,2");
        assertEquals(ApiVersion.V1, service.resolve("1"));
        assertEquals(ApiVersion.V1, service.resolve("v1"));
        assertEquals(ApiVersion.V2, service.resolve("V2"));
        assertEquals(ApiVersion.V2, service.resolve("2"));
        log.info("[判定依据] 命中已发布且受支持版本");
    }

    @Test
    @DisplayName("已废弃 v0：VERSION_DEPRECATED，拒绝静默降级")
    void deprecatedVersionRejected() {
        log.info("[用例输入] X-Api-Version=0");
        ApiException ex = assertThrows(ApiException.class, () -> service.resolve("0"));
        assertEquals(ErrorCode.VERSION_DEPRECATED, ex.errorCode());
        log.info("[判定依据] errorCode={} reason={}", ex.errorCode(), ex.reason());
    }

    @Test
    @DisplayName("从未发布的版本：VERSION_UNSUPPORTED，与 DEPRECATED 原因不同")
    void unknownVersionRejected() {
        log.info("[用例输入] X-Api-Version=99 / v3 / abc");
        for (String header : new String[]{"99", "v3", "abc"}) {
            ApiException ex = assertThrows(ApiException.class, () -> service.resolve(header));
            assertEquals(ErrorCode.VERSION_UNSUPPORTED, ex.errorCode());
            log.info("[判定依据] header={} errorCode={}", header, ex.errorCode());
        }

        ErrorCode deprecated = assertThrows(ApiException.class,
                () -> service.resolve("0")).errorCode();
        org.junit.jupiter.api.Assertions.assertNotEquals(
                ErrorCode.VERSION_UNSUPPORTED, deprecated,
                "废弃与未发布必须是可区分的拒绝原因");
    }
}
