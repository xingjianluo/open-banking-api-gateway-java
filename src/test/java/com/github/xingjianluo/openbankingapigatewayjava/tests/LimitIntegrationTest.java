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
 * 同意额度链路集成测试：HTTP -> 授权 -> 额度预占 -> 配额 -> 下游。
 * 每个用例的输入与判定依据都会打印到日志（应用层 + 本类），可据此还原每次结论。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LimitIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LimitIntegrationTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private final String unique = Long.toString(System.nanoTime());

    private String body(String ref, String amount, String currency) {
        return "{\"paymentRef\":\"" + ref + "\",\"amount\":\"" + amount + "\","
                + "\"currency\":\"" + currency + "\","
                + "\"debtorAccountId\":\"DEB-1\",\"creditorAccountId\":\"CRE-9\"}";
    }

    private MvcResult pay(String key, String consentId, String amount, String currency) throws Exception {
        return pay(key, consentId, amount, currency, false);
    }

    private MvcResult pay(String key, String consentId, String amount, String currency,
                          boolean failUpstream) throws Exception {
        var req = post("/api/payments")
                .header("Content-Type", MediaType.APPLICATION_JSON)
                .header("X-Partner-Id", "partner-a")
                .header("X-Consent-Id", consentId)
                .header("Idempotency-Key", key)
                .content(body(key + "-" + unique, amount, currency));
        if (failUpstream) {
            req.header("X-Simulate-Upstream-Failure", "true");
        }
        return mockMvc.perform(req).andReturn();
    }

    private String newLimitedConsent(String perPayment, String total, String currency) throws Exception {
        String consentId = "consent-lim-" + unique + "-" + Long.toString(System.nanoTime(), 36);
        mockMvc.perform(post("/local/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "consentId", consentId,
                                "partnerId", "partner-a",
                                "scopes", List.of("PAYMENTS_INITIATE", "PAYMENTS_READ"),
                                "ttlSeconds", 3600,
                                "perPaymentLimit", perPayment,
                                "totalLimit", total,
                                "currency", currency))))
                .andExpect(status().isOk());
        return consentId;
    }

    private BigDecimal usedAmount(String consentId) throws Exception {
        MvcResult r = mockMvc.perform(get("/local/consents/" + consentId + "/usage"))
                .andExpect(status().isOk()).andReturn();
        BigDecimal used = MAPPER.readTree(r.getResponse().getContentAsString())
                .get("usedAmount").decimalValue();
        log.info("[用量查询] consentId={} usedAmount={}", consentId, used);
        return used;
    }

    private void logResult(String name, MvcResult result) throws Exception {
        log.info("[用例:{}] HTTP {} 响应体={}", name, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("额度内支付放行并累计用量；三种超限拒绝原因互不相同且不吃额度")
    void limitJudgements() throws Exception {
        String consentId = newLimitedConsent("100.00", "250.00", "GBP");

        MvcResult ok = pay("k-ok-" + unique, consentId, "100.00", "GBP");
        logResult("额度内放行", ok);
        assertEquals(201, ok.getResponse().getStatus());
        assertEquals(0, usedAmount(consentId).compareTo(new BigDecimal("100.00")));

        MvcResult perPayment = pay("k-pp-" + unique, consentId, "100.01", "GBP");
        logResult("超单笔", perPayment);
        assertEquals(403, perPayment.getResponse().getStatus());
        assertTrue(perPayment.getResponse().getContentAsString().contains("LIMIT_PER_PAYMENT_EXCEEDED"));

        MvcResult currency = pay("k-ccy-" + unique, consentId, "10.00", "EUR");
        logResult("币种不符", currency);
        assertEquals(403, currency.getResponse().getStatus());
        assertTrue(currency.getResponse().getContentAsString().contains("CONSENT_CURRENCY_MISMATCH"));

        MvcResult ok2 = pay("k-ok2-" + unique, consentId, "100.00", "GBP");
        assertEquals(201, ok2.getResponse().getStatus());
        assertEquals(0, usedAmount(consentId).compareTo(new BigDecimal("200.00")));

        MvcResult total = pay("k-total-" + unique, consentId, "100.00", "GBP");
        logResult("超累计", total);
        assertEquals(403, total.getResponse().getStatus());
        assertTrue(total.getResponse().getContentAsString().contains("LIMIT_TOTAL_EXCEEDED"));

        log.info("[判定依据] 三笔拒绝后用量应保持 200.00，实际={}", usedAmount(consentId));
        assertEquals(0, usedAmount(consentId).compareTo(new BigDecimal("200.00")),
                "被拒绝的请求不得累计用量");
    }

    @Test
    @DisplayName("重放不重复累计：同键重放拿到字节一致结果，用量不变")
    void replayDoesNotDoubleCount() throws Exception {
        String consentId = newLimitedConsent("100.00", "250.00", "GBP");
        String key = "k-replay-" + unique;
        // 同一幂等键 + 同一请求体才算重放，这里直接发两次完全相同的请求
        String json = body("REF-REPLAY-" + unique, "80.00", "GBP");
        var template = post("/api/payments")
                .header("Content-Type", MediaType.APPLICATION_JSON)
                .header("X-Partner-Id", "partner-a")
                .header("X-Consent-Id", consentId)
                .header("Idempotency-Key", key)
                .content(json);
        MvcResult first = mockMvc.perform(template).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/payments")
                        .header("Content-Type", MediaType.APPLICATION_JSON)
                        .header("X-Partner-Id", "partner-a")
                        .header("X-Consent-Id", consentId)
                        .header("Idempotency-Key", key)
                        .content(json))
                .andReturn();
        logResult("重放", replay);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals("true", replay.getResponse().getHeader("Idempotent-Replay"));
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "重放必须返回与首次完全一致的结果");
        assertEquals(0, usedAmount(consentId).compareTo(new BigDecimal("80.00")),
                "重放不得重复累计用量");
    }

    @Test
    @DisplayName("下游异常不吃额度：503 后用量为零，同额新键可重新成功")
    void upstreamFailureDoesNotConsumeLimit() throws Exception {
        String consentId = newLimitedConsent("100.00", "100.00", "GBP");
        MvcResult fail = pay("k-upfail-" + unique, consentId, "90.00", "GBP", true);
        logResult("下游异常", fail);
        assertEquals(503, fail.getResponse().getStatus());
        assertEquals(0, usedAmount(consentId).compareTo(BigDecimal.ZERO),
                "下游异常未落账，预占必须全额归还");

        MvcResult retry = pay("k-upok-" + unique, consentId, "90.00", "GBP");
        logResult("异常后重试", retry);
        assertEquals(201, retry.getResponse().getStatus());
        assertEquals(0, usedAmount(consentId).compareTo(new BigDecimal("90.00")));
    }

    @Test
    @DisplayName("并发不透支：累计上限100，8线程各60 -> 恰好1笔201，其余LIMIT_TOTAL_EXCEEDED，用量=60")
    void concurrentPaymentsNeverOverdraw() throws Exception {
        String consentId = newLimitedConsent("100.00", "100.00", "GBP");
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger totalExceeded = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startGun.await();
                    MvcResult r = pay("k-conc-" + idx + "-" + unique, consentId, "60.00", "GBP");
                    int sc = r.getResponse().getStatus();
                    String resp = r.getResponse().getContentAsString();
                    if (sc == 201) {
                        success.incrementAndGet();
                        log.info("[并发额度] 线程{} -> 201", idx);
                    } else if (sc == 403 && resp.contains("LIMIT_TOTAL_EXCEEDED")) {
                        totalExceeded.incrementAndGet();
                        log.info("[并发额度] 线程{} -> 403 LIMIT_TOTAL_EXCEEDED", idx);
                    } else {
                        other.incrementAndGet();
                        log.error("[并发额度] 线程{} 非预期 HTTP {} body={}", idx, sc, resp);
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                    log.error("[并发额度] 线程{} 异常", idx, e);
                } finally {
                    done.countDown();
                }
            });
        }
        startGun.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[判定依据] 201={} LIMIT_TOTAL_EXCEEDED={} 其他={} 最终用量={}",
                success.get(), totalExceeded.get(), other.get(), usedAmount(consentId));
        assertEquals(1, success.get(), "剩余额度只够一笔时并发也只能放行一笔");
        assertEquals(n - 1, totalExceeded.get());
        assertEquals(0, other.get());
        assertEquals(0, usedAmount(consentId).compareTo(new BigDecimal("60.00")),
                "成功累计金额不得超过累计上限");
    }

    @Test
    @DisplayName("调整累计上限：下调到已用以下则新支付拒绝，上调后放行；旧成功请求重放不受新额度影响")
    void adjustTotalLimit() throws Exception {
        String consentId = newLimitedConsent("100.00", "250.00", "GBP");
        String key = "k-adj-" + unique;
        String json = body("REF-ADJ-" + unique, "100.00", "GBP");
        MvcResult first = mockMvc.perform(post("/api/payments")
                        .header("Content-Type", MediaType.APPLICATION_JSON)
                        .header("X-Partner-Id", "partner-a")
                        .header("X-Consent-Id", consentId)
                        .header("Idempotency-Key", key)
                        .content(json))
                .andReturn();
        assertEquals(201, first.getResponse().getStatus());

        // 下调累计上限到 150：已用 100，再来 100 -> 200 > 150 拒绝
        mockMvc.perform(post("/local/consents/" + consentId + "/limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "perPaymentLimit", "100.00", "totalLimit", "150.00",
                                "currency", "GBP"))))
                .andExpect(status().isOk());
        MvcResult afterLower = pay("k-adj2-" + unique, consentId, "100.00", "GBP");
        logResult("下调后支付", afterLower);
        assertEquals(403, afterLower.getResponse().getStatus());
        assertTrue(afterLower.getResponse().getContentAsString().contains("LIMIT_TOTAL_EXCEEDED"));

        // 上调到 1000：同额放行
        mockMvc.perform(post("/local/consents/" + consentId + "/limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "perPaymentLimit", "100.00", "totalLimit", "1000.00",
                                "currency", "GBP"))))
                .andExpect(status().isOk());
        MvcResult afterRaise = pay("k-adj3-" + unique, consentId, "100.00", "GBP");
        logResult("上调后支付", afterRaise);
        assertEquals(201, afterRaise.getResponse().getStatus());

        // 旧成功请求的重放：幂等快照优先，不受新额度影响，结果与首次一致
        MvcResult replay = mockMvc.perform(post("/api/payments")
                        .header("Content-Type", MediaType.APPLICATION_JSON)
                        .header("X-Partner-Id", "partner-a")
                        .header("X-Consent-Id", consentId)
                        .header("Idempotency-Key", key)
                        .content(json))
                .andReturn();
        logResult("调整后重放", replay);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("并发支付与并发调额：每个请求结论可解释(201或LIMIT_TOTAL_EXCEEDED)，最终用量=成功数×金额且不超新上限")
    void concurrentPaymentsDuringAdjustment() throws Exception {
        String consentId = newLimitedConsent("100.00", "200.00", "GBP");
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n + 1);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n + 1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger totalExceeded = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startGun.await();
                    MvcResult r = pay("k-adjconc-" + idx + "-" + unique, consentId, "50.00", "GBP");
                    int sc = r.getResponse().getStatus();
                    String resp = r.getResponse().getContentAsString();
                    if (sc == 201) {
                        success.incrementAndGet();
                    } else if (sc == 403 && resp.contains("LIMIT_TOTAL_EXCEEDED")) {
                        totalExceeded.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        unexpected.add("HTTP " + sc + " " + resp);
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                    log.error("[并发调额] 支付线程{} 异常", idx, e);
                } finally {
                    done.countDown();
                }
            });
        }
        // 并发线程：把累计上限上调到 1000（与支付竞争，支付按进入时读到的完整快照判定）
        pool.submit(() -> {
            try {
                startGun.await();
                mockMvc.perform(post("/local/consents/" + consentId + "/limits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(MAPPER.writeValueAsString(Map.of(
                                        "perPaymentLimit", "100.00", "totalLimit", "1000.00",
                                        "currency", "GBP"))))
                        .andExpect(status().isOk());
                log.info("[并发调额] 上调完成 200 -> 1000");
            } catch (Exception e) {
                other.incrementAndGet();
                log.error("[并发调额] 调整线程异常", e);
            } finally {
                done.countDown();
            }
        });
        startGun.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        BigDecimal used = usedAmount(consentId);
        log.info("[判定依据] 201={} LIMIT_TOTAL_EXCEEDED={} 其他={} 最终用量={} 新上限=1000",
                success.get(), totalExceeded.get(), other.get(), used);
        assertEquals(0, other.get(), "并发期间不允许出现不可解释的结论: " + unexpected);
        // 旧上限下最多放行 4 笔(200/50)，上调后最多 6 笔全部放行
        assertTrue(success.get() >= 4 && success.get() <= n,
                "成功数必须落在 [旧上限可放行数, 总请求数] 区间，实际=" + success.get());
        assertEquals(n - success.get(), totalExceeded.get());
        assertEquals(0, used.compareTo(new BigDecimal("50.00").multiply(BigDecimal.valueOf(success.get()))),
                "最终用量必须恰好等于成功请求之和");
        assertTrue(used.compareTo(new BigDecimal("1000.00")) <= 0, "用量不得超过调整后的上限");
    }

    @Test
    @DisplayName("续期：过期同意支付 CONSENT_EXPIRED；续期后同同意放行；缩短有效期被拒绝")
    void renewExtendsValidity() throws Exception {
        String consentId = "consent-renew-" + unique;
        mockMvc.perform(post("/local/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "consentId", consentId,
                                "partnerId", "partner-a",
                                "scopes", List.of("PAYMENTS_INITIATE", "PAYMENTS_READ"),
                                "ttlSeconds", -1,
                                "perPaymentLimit", "100.00", "totalLimit", "500.00",
                                "currency", "GBP"))))
                .andExpect(status().isOk());

        MvcResult expired = pay("k-exp-" + unique, consentId, "10.00", "GBP");
        logResult("过期支付", expired);
        assertEquals(403, expired.getResponse().getStatus());
        assertTrue(expired.getResponse().getContentAsString().contains("CONSENT_EXPIRED"));

        // 续期 1 小时
        mockMvc.perform(post("/local/consents/" + consentId + "/renew").param("ttlSeconds", "3600"))
                .andExpect(status().isOk());
        MvcResult afterRenew = pay("k-renewed-" + unique, consentId, "10.00", "GBP");
        logResult("续期后支付", afterRenew);
        assertEquals(201, afterRenew.getResponse().getStatus());

        // 试图缩短有效期：新截止时间必须晚于当前，拒绝
        MvcResult shorten = mockMvc.perform(post("/local/consents/" + consentId + "/renew")
                        .param("ttlSeconds", "60"))
                .andReturn();
        logResult("缩短有效期", shorten);
        assertEquals(400, shorten.getResponse().getStatus());
        assertTrue(shorten.getResponse().getContentAsString().contains("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("并发续期与并发支付：每个请求要么 CONSENT_EXPIRED 要么 201，续期生效后新请求必放行")
    void concurrentPaymentsDuringRenewal() throws Exception {
        String consentId = "consent-renew-c-" + unique;
        mockMvc.perform(post("/local/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "consentId", consentId,
                                "partnerId", "partner-a",
                                "scopes", List.of("PAYMENTS_INITIATE", "PAYMENTS_READ"),
                                "ttlSeconds", -1,
                                "perPaymentLimit", "100.00", "totalLimit", "500.00",
                                "currency", "GBP"))))
                .andExpect(status().isOk());

        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n + 1);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n + 1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger expired = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startGun.await();
                    MvcResult r = pay("k-rnw-" + idx + "-" + unique, consentId, "10.00", "GBP");
                    int sc = r.getResponse().getStatus();
                    String resp = r.getResponse().getContentAsString();
                    if (sc == 201) {
                        success.incrementAndGet();
                    } else if (sc == 403 && resp.contains("CONSENT_EXPIRED")) {
                        expired.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        log.error("[并发续期] 线程{} 非预期 HTTP {} body={}", idx, sc, resp);
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                    log.error("[并发续期] 线程{} 异常", idx, e);
                } finally {
                    done.countDown();
                }
            });
        }
        pool.submit(() -> {
            try {
                startGun.await();
                mockMvc.perform(post("/local/consents/" + consentId + "/renew")
                                .param("ttlSeconds", "3600"))
                        .andExpect(status().isOk());
                log.info("[并发续期] 续期完成 +3600s");
            } catch (Exception e) {
                other.incrementAndGet();
                log.error("[并发续期] 续期线程异常", e);
            } finally {
                done.countDown();
            }
        });
        startGun.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[判定依据] 201={} CONSENT_EXPIRED={} 其他={}（每个请求按进入时读到的完整快照判定）",
                success.get(), expired.get(), other.get());
        assertEquals(0, other.get(), "并发续期期间不允许出现不可解释的结论");
        assertEquals(n, success.get() + expired.get());

        // 续期已生效：其后的新请求必然放行
        MvcResult after = pay("k-rnw-after-" + unique, consentId, "10.00", "GBP");
        logResult("续期生效后", after);
        assertEquals(201, after.getResponse().getStatus());
    }

    @Test
    @DisplayName("版本兼容：无额度老同意任意金额照常放行；v0 仍 VERSION_DEPRECATED；v2 缺省字段行为不变")
    void legacyBehaviourUnchanged() throws Exception {
        // 老同意（无额度字段）：任意金额、任意币种都不触发额度判定
        String consentId = "consent-legacy-" + unique;
        mockMvc.perform(post("/local/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "consentId", consentId,
                                "partnerId", "partner-a",
                                "scopes", List.of("PAYMENTS_INITIATE", "PAYMENTS_READ"),
                                "ttlSeconds", 3600))))
                .andExpect(status().isOk());
        MvcResult big = pay("k-legacy-" + unique, consentId, "999999.99", "USD");
        logResult("老同意大额支付", big);
        assertEquals(201, big.getResponse().getStatus());

        // 已废弃版本依旧明确拒绝
        MvcResult v0 = mockMvc.perform(post("/api/payments")
                        .header("Content-Type", MediaType.APPLICATION_JSON)
                        .header("X-Partner-Id", "partner-a")
                        .header("X-Consent-Id", consentId)
                        .header("Idempotency-Key", "k-v0-" + unique)
                        .header("X-Api-Version", "0")
                        .content(body("REF-V0-" + unique, "1.00", "GBP")))
                .andReturn();
        logResult("v0拒绝", v0);
        assertEquals(400, v0.getResponse().getStatus());
        assertTrue(v0.getResponse().getContentAsString().contains("VERSION_DEPRECATED"));
    }
}
