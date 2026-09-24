package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.github.xingjianluo.openbankingapigatewayjava.model.api.PaymentRequest;
import com.github.xingjianluo.openbankingapigatewayjava.version.RequestHasher;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 请求哈希兼容性测试：新增字段缺省归一；实质内容不同则哈希不同。 */
class RequestHasherTest {

    private static final Logger log = LoggerFactory.getLogger(RequestHasherTest.class);
    private final RequestHasher hasher = new RequestHasher();

    private PaymentRequest req(String consent, String debtorName, String amount) {
        return new PaymentRequest(consent, "DEBT-1", "CRED-1",
                new BigDecimal(amount), "EUR", debtorName);
    }

    @Test
    @DisplayName("新增可选字段缺省(null)与显式空白得到相同哈希：保持既有语义")
    void nullAndBlankOptionalField_sameHash() {
        log.info("[哈希兼容] 输入: debtorName=null vs debtorName=' '");
        String h1 = hasher.hash(req("c1", null, "10.00"));
        String h2 = hasher.hash(req("c1", "  ", "10.00"));
        assertEquals(h1, h2);
        log.info("[哈希兼容] 判定依据: 可选字段归一为空串 -> 哈希一致 {}", h1.substring(0, 8));
    }

    @Test
    @DisplayName("金额尾随零差异不影响哈希（10.00 与 10.0）")
    void amountTrailingZeros_sameHash() {
        log.info("[哈希兼容] 输入: amount=10.00 vs 10.0");
        assertEquals(hasher.hash(req("c1", null, "10.00")), hasher.hash(req("c1", null, "10.0")));
    }

    @Test
    @DisplayName("金额不同 / 新增字段非空 -> 哈希不同，构成幂等冲突")
    void differentPayload_differentHash() {
        log.info("[哈希兼容] 输入: 金额 10 vs 20；debtorName 非空");
        String base = hasher.hash(req("c1", null, "10.00"));
        assertNotEquals(base, hasher.hash(req("c1", null, "20.00")));
        assertNotEquals(base, hasher.hash(req("c1", "Alice", "10.00")));
        log.info("[哈希兼容] 判定依据: 实质字段变化 -> 哈希不同 -> 同键提交将触发 IDEMPOTENCY_CONFLICT");
    }
}
