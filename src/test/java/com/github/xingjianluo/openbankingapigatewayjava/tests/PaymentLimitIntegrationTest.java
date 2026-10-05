package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 额度链路集成测试：HTTP -> 授权 -> 额度判定/预占 -> 配额 -> 下游。
 * 每个用例使用独立合作方/同意，互不污染；判定输入与依据由应用层日志打印。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentLimitIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentLimitIntegrationTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private final String unique = Long.toString(System.nanoTime());
    private final String partnerId = "partner-limit-" + unique;

    private String body(String ref, String amount, String currency) {
        return "{\"paymentRef\":\"" + ref + "\",\"amount\":\"" + amount + "\","
                + "\"currency\":\"" + currency + "\","
                + "\"debtorAccountId\":\"DEB-1\",\"creditorAccountId\":\"CRE-9\"}";
    }

    private void newPartner() throws Exception {
        mockMvc.perform(post("/local/partners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "partnerId", partnerId,
                                "name", "额度测试合作方",
                                "scopes", List.of("PAYMENTS_INITIATE", "PAYMENTS_READ")))))
                .andExpect(status().isOk());
    }

    private String newCappedConsent(String suffix, String perPayment, String cumulative,
                                    String currency, long ttlSeconds) throws Exception {
        String consentId = "consent-limit-" + suffix + "-" + unique;
        mockMvc.perform(post("/local/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "consentId", consentId,
                                "partnerId", partnerId,
                                "scopes", List.of("PAYMENTS_INITIATE", "PAYMENTS_READ"),
                                "ttlSeconds", ttlSeconds,
                                "perPaymentLimit", perPayment,
                                "cumulativeLimit", cumulative,
                                "limitCurrency", currency))))
                .andExpect(status().isOk());
        return consentId;
    }

    private MvcResult pay(String key, String consentId, String amount, String currency)
            throws Exception {
        return mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Partner-Id", partnerId)
                        .header("X-Consent-Id", consentId)
                        .header("Idempotency-Key", key)
                        .content(body("REF-" + key, amount, currency)))
                .andReturn();
    }

    private BigDecimal used(String consentId) throws Exception {
        MvcResult r = mockMvc.perform(get("/local/consents/" + consentId + "/limits"))
                .andExpect(status().isOk()).andReturn();
        String used = MAPPER.readTree(r.getResponse().getContentAsString()).get("used").asText();
        return new BigDecimal(used);
    }

    private void logResult(String name, MvcResult result) throws Exception {
        log.info("[额度用例:{}] HTTP {} 响应体={}", name, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("三种额度拒绝互相区分：单笔超限/累计超限/币种不符，且都不消耗用量")
    void threeDistinctLimitRejections() throws Exception {
        newPartner();
        String consentId = newCappedConsent("distinct", "100.00", "250.00", "GBP", 3600);

        MvcResult perPayment = pay("k-pp-" + unique, consentId, "100.01", "GBP");
        logResult("单笔超限", perPayment);
        assertEquals(403, perPayment.getResponse().getStatus());
        assertTrue(perPayment.getResponse().getContentAsString().contains("PAYMENT_LIMIT_EXCEEDED"));

        MvcResult currency = pay("k-cur-" + unique, consentId, "10.00", "EUR");
        logResult("币种不符", currency);
        assertEquals(403, currency.getResponse().getStatus());
        assertTrue(currency.getResponse().getContentAsString().contains("CURRENCY_MISMATCH"));

        // 两笔 100 成功，累计 200；再来 60 将超 250 -> CUMULATIVE_LIMIT_EXCEEDED
        assertEquals(201, pay("k-ok1-" + unique, consentId, "100.00", "GBP").getResponse().getStatus());
        assertEquals(201, pay("k-ok2-" + unique, consentId, "100.00", "GBP").getResponse().getStatus());
        MvcResult cumulative = pay("k-cum-" + unique, consentId, "60.00", "GBP");
        logResult("累计超限", cumulative);
        assertEquals(403, cumulative.getResponse().getStatus());
        assertTrue(cumulative.getResponse().getContentAsString().contains("CUMULATIVE_LIMIT_EXCEEDED"));

        // 被拒绝的三笔不消耗额度：已用恰为两笔成功之和
        assertEquals(0, new BigDecimal("200.00").compareTo(used(consentId)),
                "被拒绝的请求不得消耗额度");
        log.info("[判定依据] 三种拒绝码区分，拒绝后已用={}", used(consentId));
    }

    @Test
    @DisplayName("重放不重复累计：同键重放返回同一结果，用量只计一次")
    void replayDoesNotDoubleCount() throws Exception {
        newPartner();
        String consentId = newCappedConsent("replay", "100.00", "250.00", "GBP", 3600);
        String key = "k-replay-" + unique;

        MvcResult first = pay(key, consentId, "40.00", "GBP");
        assertEquals(201, first.getResponse().getStatus());
        MvcResult replay = pay(key, consentId, "40.00", "GBP");
        logResult("重放", replay);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals("true", replay.getResponse().getHeader("Idempotent-Replay"));
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "重放必须返回字节级一致的结果");

        assertEquals(0, new BigDecimal("40.00").compareTo(used(consentId)),
                "重放不得重复累计用量");
        log.info("[判定依据] 重放后已用={}（仅首次计入）", used(consentId));
    }

    @Test
    @DisplayName("下游异常不消耗额度：503 后用量为零，同键重放得到同一失败结论")
    void upstreamFailureDoesNotConsume() throws Exception {
        newPartner();
        String consentId = newCappedConsent("upstream", "100.00", "250.00", "GBP", 3600);
        String key = "k-upstream-" + unique;
        String json = body("REF-UP-" + unique, "70.00", "GBP");

        MvcResult fail = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Partner-Id", partnerId)
                        .header("X-Consent-Id", consentId)
                        .header("Idempotency-Key", key)
                        .header("X-Simulate-Upstream-Failure", "true")
                        .content(json))
                .andReturn();
        logResult("下游异常", fail);
        assertEquals(503, fail.getResponse().getStatus());
        assertTrue(fail.getResponse().getContentAsString().contains("UPSTREAM_UNAVAILABLE"));
        assertEquals(0, BigDecimal.ZERO.compareTo(used(consentId)),
                "下游异常未落账，预占必须退回");

        // 同键同内容重放：得到与首次完全一致的失败结论，且不产生用量
        MvcResult replay = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Partner-Id", partnerId)
                        .header("X-Consent-Id", consentId)
                        .header("Idempotency-Key", key)
                        .content(json))
                .andReturn();
        logResult("失败后重放", replay);
        assertEquals(503, replay.getResponse().getStatus());
        // 失败结论的稳定语义必须与首次完全一致（timestamp 为每次渲染的元数据，不参与比较）
        var firstNode = MAPPER.readTree(fail.getResponse().getContentAsString());
        var replayNode = MAPPER.readTree(replay.getResponse().getContentAsString());
        assertEquals(firstNode.get("code"), replayNode.get("code"));
        assertEquals(firstNode.get("message"), replayNode.get("message"));
        assertEquals(firstNode.get("reason"), replayNode.get("reason"));
        assertEquals(0, BigDecimal.ZERO.compareTo(used(consentId)));

        // 换新键正常支付：额度仍完整可用
        assertEquals(201, pay("k-after-" + unique, consentId, "70.00", "GBP").getResponse().getStatus());
        assertEquals(0, new BigDecimal("70.00").compareTo(used(consentId)));
        log.info("[判定依据] 下游异常未消耗额度，恢复后已用={}", used(consentId));
    }

    @Test
    @DisplayName("并发不透支：累计上限只够 3 笔时 20 并发恰好 3 个 201，其余 403，用量等于上限")
    void concurrentPaymentsNeverOverdraw() throws Exception {
        newPartner();
        String consentId = newCappedConsent("race", "100.00", "90.00", "GBP", 3600);

        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger limitRejected = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        ConcurrentLinkedQueue<String> otherBodies = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    gun.await();
                    MvcResult r = pay("k-race-" + idx + "-" + unique, consentId, "30.00", "GBP");
                    int sc = r.getResponse().getStatus();
                    String resp = r.getResponse().getContentAsString();
                    if (sc == 201) {
                        created.incrementAndGet();
                    } else if (sc == 403 && resp.contains("CUMULATIVE_LIMIT_EXCEEDED")) {
                        limitRejected.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        otherBodies.add(sc + ":" + resp);
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                    otherBodies.add("EX:" + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        gun.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[并发额度] 201={} 累计超限403={} 其他={} 最终已用={}",
                created.get(), limitRejected.get(), other.get(), used(consentId));
        otherBodies.forEach(b -> log.error("[并发额度] 非预期响应 {}", b));
        assertEquals(3, created.get(), "上限 90 只应放行 3 笔 30");
        assertEquals(17, limitRejected.get());
        assertEquals(0, other.get(), "不允许出现第三种结论");
        assertEquals(0, new BigDecimal("90.00").compareTo(used(consentId)),
                "成功累计金额绝不能超过累计上限");
    }

    @Test
    @DisplayName("调额：下调后新支付按新上限拒绝，上调后放行；结论相对各自快照确定")
    void adjustLimitTakesEffectAtomically() throws Exception {
        newPartner();
        String consentId = newCappedConsent("adjust", "100.00", "100.00", "GBP", 3600);

        assertEquals(201, pay("k-adj1-" + unique, consentId, "100.00", "GBP").getResponse().getStatus());
        assertEquals(0, new BigDecimal("100.00").compareTo(used(consentId)));

        // 下调累计上限到 50（低于已用 100）：任何新支付都被拒
        mockMvc.perform(post("/local/consents/" + consentId + "/limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "perPaymentLimit", "100.00",
                                "cumulativeLimit", "50.00",
                                "limitCurrency", "GBP"))))
                .andExpect(status().isOk());
        MvcResult afterLower = pay("k-adj2-" + unique, consentId, "1.00", "GBP");
        logResult("下调后", afterLower);
        assertEquals(403, afterLower.getResponse().getStatus());
        assertTrue(afterLower.getResponse().getContentAsString().contains("CUMULATIVE_LIMIT_EXCEEDED"));

        // 上调到 200：新支付放行，已用 100 保留不变
        mockMvc.perform(post("/local/consents/" + consentId + "/limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "perPaymentLimit", "100.00",
                                "cumulativeLimit", "200.00",
                                "limitCurrency", "GBP"))))
                .andExpect(status().isOk());
        MvcResult afterRaise = pay("k-adj3-" + unique, consentId, "50.00", "GBP");
        logResult("上调后", afterRaise);
        assertEquals(201, afterRaise.getResponse().getStatus());
        assertEquals(0, new BigDecimal("150.00").compareTo(used(consentId)),
                "调额不得重置或篡改既有用量");
        log.info("[判定依据] 下调拒绝、上调放行，已用={}", used(consentId));
    }

    @Test
    @DisplayName("续期期间并发支付：每个请求要么 CONSENT_EXPIRED 要么 201，绝不出现中间态")
    void extendExpiryConcurrentWithPayments() throws Exception {
        newPartner();
        // 已过期同意
        String consentId = newCappedConsent("expiry", "100.00", "1000.00", "GBP", -1);

        MvcResult expired = pay("k-exp0-" + unique, consentId, "10.00", "GBP");
        assertEquals(403, expired.getResponse().getStatus());
        assertTrue(expired.getResponse().getContentAsString().contains("CONSENT_EXPIRED"));

        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n + 1);
        CountDownLatch gun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger expiredRejected = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        ConcurrentLinkedQueue<String> otherBodies = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    gun.await();
                    MvcResult r = pay("k-exp-" + idx + "-" + unique, consentId, "10.00", "GBP");
                    int sc = r.getResponse().getStatus();
                    String resp = r.getResponse().getContentAsString();
                    if (sc == 201) {
                        created.incrementAndGet();
                    } else if (sc == 403 && resp.contains("CONSENT_EXPIRED")) {
                        expiredRejected.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        otherBodies.add(sc + ":" + resp);
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                    otherBodies.add("EX:" + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        // 与支付并发执行续期
        pool.submit(() -> {
            try {
                gun.await();
                mockMvc.perform(post("/local/consents/" + consentId + "/extend")
                                .param("ttlSeconds", "3600"))
                        .andExpect(status().isOk());
            } catch (Exception e) {
                log.error("续期失败", e);
            }
        });
        gun.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[并发续期] 201={} CONSENT_EXPIRED={} 其他={}",
                created.get(), expiredRejected.get(), other.get());
        otherBodies.forEach(b -> log.error("[并发续期] 非预期响应 {}", b));
        assertEquals(0, other.get(), "续期期间只允许 201 或 CONSENT_EXPIRED 两种确定结论");
        assertEquals(n, created.get() + expiredRejected.get());

        // 续期完成后：新支付确定放行
        MvcResult after = pay("k-exp-after-" + unique, consentId, "10.00", "GBP");
        logResult("续期后", after);
        assertEquals(201, after.getResponse().getStatus());
        // 只有真实成功的支付计入用量
        BigDecimal expectedUsed = new BigDecimal("10.00")
                .multiply(BigDecimal.valueOf(created.get() + 1L));
        assertEquals(0, expectedUsed.compareTo(used(consentId)),
                "用量必须恰好等于成功支付金额之和");
        log.info("[判定依据] 成功 {} 笔，已用={}", created.get() + 1, used(consentId));
    }

    @Test
    @DisplayName("版本兼容回归：老请求方式行为不变；v0 仍拒绝；无额度同意不受新能力影响")
    void versionAndLegacyRegression() throws Exception {
        newPartner();
        // 老方式创建同意（不带额度字段）-> 无额度约束
        String legacyConsent = "consent-legacy-" + unique;
        mockMvc.perform(post("/local/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "consentId", legacyConsent,
                                "partnerId", partnerId,
                                "scopes", List.of("PAYMENTS_INITIATE", "PAYMENTS_READ"),
                                "ttlSeconds", 3600))))
                .andExpect(status().isOk());

        // v1 缺省请求头 + 老请求体（无新增字段）：大额也放行，与引入额度前一致
        MvcResult legacy = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Partner-Id", partnerId)
                        .header("X-Consent-Id", legacyConsent)
                        .header("Idempotency-Key", "k-legacy-" + unique)
                        .content(body("REF-LEGACY-" + unique, "999999.99", "USD")))
                .andReturn();
        logResult("老请求方式", legacy);
        assertEquals(201, legacy.getResponse().getStatus());
        assertTrue(legacy.getResponse().getContentAsString().contains("\"priority\":\"NORMAL\""));

        // v0 仍旧明确拒绝，不因新能力静默降级
        MvcResult v0 = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Partner-Id", partnerId)
                        .header("X-Consent-Id", legacyConsent)
                        .header("Idempotency-Key", "k-v0-" + unique)
                        .header("X-Api-Version", "0")
                        .content(body("REF-V0-" + unique, "1.00", "GBP")))
                .andReturn();
        logResult("v0拒绝", v0);
        assertEquals(400, v0.getResponse().getStatus());
        assertTrue(v0.getResponse().getContentAsString().contains("VERSION_DEPRECATED"));
        log.info("[判定依据] 老请求行为不变、v0 照旧拒绝");
    }
}
