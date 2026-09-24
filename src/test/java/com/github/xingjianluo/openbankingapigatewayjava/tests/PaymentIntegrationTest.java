package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.github.xingjianluo.openbankingapigatewayjava.payment.PaymentRepository;

/**
 * 支付链路集成测试：HTTP -> 版本/幂等/授权/配额/下游 -> 稳定错误语义。
 * 用例输入与判定依据由应用层日志打印；这里额外在日志中打印响应码与错误码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PaymentRepository paymentRepository;

    private String body(String ref, String priorityJson) {
        String prio = priorityJson == null ? "" : ",\"priority\":\"" + priorityJson + "\"";
        return "{\"paymentRef\":\"" + ref + "\",\"amount\":\"12.50\",\"currency\":\"GBP\","
                + "\"debtorAccountId\":\"DEB-1\",\"creditorAccountId\":\"CRE-9\"" + prio + "}";
    }

    private MvcResult createPayment(String key, String partner, String consent,
                                    String version, String json) throws Exception {
        var req = post("/api/payments")
                .header("Content-Type", MediaType.APPLICATION_JSON)
                .header("X-Partner-Id", partner)
                .header("X-Consent-Id", consent)
                .header("Idempotency-Key", key)
                .content(json);
        if (version != null) {
            req.header("X-Api-Version", version);
        }
        return mockMvc.perform(req).andReturn();
    }

    private void logResult(String name, MvcResult result) throws Exception {
        log.info("[集成用例:{}] HTTP {} 响应体={}", name, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("正常创建：201；同键串行重放：返回同一确定结果(201)+Idempotent-Replay，资源只创建一次")
    void createThenReplay() throws Exception {
        long before = paymentRepository.count();
        String json = body("REF-HAPPY", "HIGH");
        MvcResult first = createPayment("key-happy", "partner-a", "consent-ok", "2", json);
        logResult("create", first);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());
        String firstBody = first.getResponse().getContentAsString();
        String paymentId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(firstBody).get("paymentId").asText();
        log.info("[判定依据] 首次创建 paymentId={} priority=HIGH", paymentId);

        long afterFirst = paymentRepository.count();
        org.junit.jupiter.api.Assertions.assertEquals(before + 1, afterFirst);

        MvcResult replay = createPayment("key-happy", "partner-a", "consent-ok", "2", json);
        logResult("replay", replay);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("true",
                replay.getResponse().getHeader("Idempotent-Replay"));
        org.junit.jupiter.api.Assertions.assertEquals(firstBody, replay.getResponse().getContentAsString(),
                "重放必须返回字节级一致的确定结果");
        org.junit.jupiter.api.Assertions.assertEquals(afterFirst, paymentRepository.count(),
                "重放不得产生第二次副作用");
    }

    @Test
    @DisplayName("权限边界：过期/撤销/范围不足/不存在的同意返回不同错误码")
    void consentRejectionBoundaries() throws Exception {
        assertErrorCode("expired", "key-exp", "partner-a", "consent-expired", "1",
                body("REF-EXP", null), 403, "CONSENT_EXPIRED");
        assertErrorCode("revoked", "key-rev", "partner-a", "consent-revoked", "1",
                body("REF-REV", null), 403, "CONSENT_REVOKED");
        assertErrorCode("consent-scope", "key-scope1", "partner-a", "consent-noinitiate", "1",
                body("REF-SCOPE", null), 403, "SCOPE_INSUFFICIENT");
        assertErrorCode("partner-scope", "key-scope2", "partner-limited", "consent-cross", "1",
                body("REF-PSCOPE", null), 403, "SCOPE_INSUFFICIENT");
        assertErrorCode("missing-consent", "key-missing", "partner-a", "consent-nope", "1",
                body("REF-MISS", null), 403, "CONSENT_NOT_FOUND");
    }

    private void assertErrorCode(String name, String key, String partner, String consent,
                                 String version, String json, int httpStatus, String code) throws Exception {
        MvcResult result = createPayment(key, partner, consent, version, json);
        logResult(name, result);
        org.junit.jupiter.api.Assertions.assertEquals(httpStatus, result.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(result.getResponse().getContentAsString().contains(code),
                "响应应包含错误码 " + code);
    }

    @Test
    @DisplayName("幂等冲突：同键不同请求体 -> 409 IDEMPOTENCY_CONFLICT")
    void idempotencyConflict() throws Exception {
        MvcResult first = createPayment("key-conflict", "partner-a", "consent-ok", null,
                body("REF-C1", null));
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());
        MvcResult second = createPayment("key-conflict", "partner-a", "consent-ok", null,
                body("REF-C2-DIFFERENT", null));
        logResult("idempotency-conflict", second);
        org.junit.jupiter.api.Assertions.assertEquals(409, second.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                second.getResponse().getContentAsString().contains("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("版本不兼容：v0 -> 400 VERSION_DEPRECATED；v9 -> 400 VERSION_UNSUPPORTED")
    void versionRejections() throws Exception {
        assertErrorCode("v0-deprecated", "key-v0", "partner-a", "consent-ok", "0",
                body("REF-V0", null), 400, "VERSION_DEPRECATED");
        assertErrorCode("v9-unsupported", "key-v9", "partner-a", "consent-ok", "9",
                body("REF-V9", null), 400, "VERSION_UNSUPPORTED");
    }

    @Test
    @DisplayName("v2 新字段缺省保持 v1 语义：priority 落库为 NORMAL")
    void v2NewFieldDefaultKeepsV1Semantics() throws Exception {
        MvcResult result = createPayment("key-v1compat", "partner-a", "consent-ok", "1",
                body("REF-V1COMPAT", null));
        logResult("v1-compat", result);
        org.junit.jupiter.api.Assertions.assertEquals(201, result.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getResponse().getContentAsString().contains("\"priority\":\"NORMAL\""));
    }

    @Test
    @DisplayName("下游异常映射：503 UPSTREAM_UNAVAILABLE，响应不包含内部主机/流水细节")
    void upstreamFailureMapped() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/payments")
                        .header("Content-Type", MediaType.APPLICATION_JSON)
                        .header("X-Partner-Id", "partner-a")
                        .header("X-Consent-Id", "consent-ok")
                        .header("Idempotency-Key", "key-upstream-fail")
                        .header("X-Api-Version", "2")
                        .header("X-Simulate-Upstream-Failure", "true")
                        .content(body("REF-UP", null)))
                .andReturn();
        logResult("upstream-fail", result);
        org.junit.jupiter.api.Assertions.assertEquals(503, result.getResponse().getStatus());
        String resp = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(resp.contains("UPSTREAM_UNAVAILABLE"));
        org.junit.jupiter.api.Assertions.assertFalse(resp.contains("core-bank.internal"));
        org.junit.jupiter.api.Assertions.assertFalse(resp.contains("CoreLedgerTimeout"));
    }

    @Test
    @DisplayName("资源不存在：404 RESOURCE_NOT_FOUND（授权通过但资源不可见）")
    void resourceNotFound() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/payments/PAY-DOES-NOT-EXIST")
                        .header("X-Partner-Id", "partner-a")
                        .header("X-Consent-Id", "consent-ok"))
                .andReturn();
        logResult("resource-not-found", result);
        org.junit.jupiter.api.Assertions.assertEquals(404, result.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getResponse().getContentAsString().contains("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("并发重复提交：资源副作用恰好一次；重叠请求409 IN_FLIGHT，完成后重放得到同一确定结果")
    void concurrentDuplicateSubmission() throws Exception {
        int n = 24;
        String json = body("REF-CONCURRENT", null);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger firstCreate = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger replay = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<String> successBodies = new java.util.concurrent.ConcurrentLinkedQueue<>();

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    MvcResult r = createPayment("key-concurrent-hot-2", "partner-a", "consent-ok",
                            "2", json);
                    int sc = r.getResponse().getStatus();
                    boolean isReplay = "true".equals(r.getResponse().getHeader("Idempotent-Replay"));
                    String resp = r.getResponse().getContentAsString();
                    if (sc == 201 && !isReplay) {
                        firstCreate.incrementAndGet();
                        successBodies.add(resp);
                    } else if (sc == 201) {
                        replay.incrementAndGet();
                        successBodies.add(resp);
                    } else if (sc == 409 && resp.contains("IDEMPOTENCY_IN_FLIGHT")) {
                        inFlight.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        log.error("[并发重复] 非预期响应 HTTP {} body={}", sc, resp);
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                    log.error("[并发重复] 请求异常", e);
                } finally {
                    done.countDown();
                }
            });
        }
        long before = paymentRepository.count();
        startGun.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        long after = paymentRepository.count();
        log.info("[判定依据] 首次201={} 重叠409IN_FLIGHT={} 完成后重放201={} 其他={} 资源增量={}",
                firstCreate.get(), inFlight.get(), replay.get(), other.get(), after - before);
        org.junit.jupiter.api.Assertions.assertEquals(1, firstCreate.get(),
                "唯一执行权只允许一个请求获得");
        org.junit.jupiter.api.Assertions.assertEquals(1, after - before, "支付资源（下游副作用）只允许创建一次");
        org.junit.jupiter.api.Assertions.assertEquals(0, other.get());
        org.junit.jupiter.api.Assertions.assertEquals(n - 1, inFlight.get() + replay.get());
        long distinctBodies = successBodies.stream().distinct().count();
        org.junit.jupiter.api.Assertions.assertEquals(1, distinctBodies,
                "所有 201（含重放）必须是同一确定结果");
    }
    @Test
    @DisplayName("配额耗尽：第三次真实提交 -> 429 QUOTA_EXCEEDED；被拒不计费，重放不扣费，重置后可继续")
    void quotaExceededEndToEnd() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String unique = Long.toString(System.nanoTime());
        String partnerId = "partner-quota-" + unique;
        String consentId = "consent-quota-" + unique;

        // 本地接口动态注册一个独立低配额合作方及其同意，避免污染共享演示数据
        mockMvc.perform(post("/local/partners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of(
                                "partnerId", partnerId,
                                "name", "配额测试合作方",
                                "scopes", java.util.List.of("PAYMENTS_INITIATE", "PAYMENTS_READ")))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/local/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of(
                                "consentId", consentId,
                                "partnerId", partnerId,
                                "scopes", java.util.List.of("PAYMENTS_INITIATE", "PAYMENTS_READ"),
                                "ttlSeconds", 3600))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/local/quotas/" + partnerId + "/limit").param("limit", "1"))
                .andExpect(status().isOk());

        MvcResult first = createPayment("key-quota-1-" + unique, partnerId, consentId, "1",
                body("REF-Q1", null));
        logResult("quota-first", first);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());

        MvcResult second = createPayment("key-quota-2-" + unique, partnerId, consentId, "1",
                body("REF-Q2", null));
        logResult("quota-second(429)", second);
        org.junit.jupiter.api.Assertions.assertEquals(429, second.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                second.getResponse().getContentAsString().contains("QUOTA_EXCEEDED"));

        // 同键重放首个成功请求：命中缓存，不经过配额，仍成功
        MvcResult replay = createPayment("key-quota-1-" + unique, partnerId, consentId, "1",
                body("REF-Q1", null));
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());

        // 重置计数后新请求可继续
        mockMvc.perform(post("/local/quotas/" + partnerId + "/reset")).andExpect(status().isOk());
        MvcResult third = createPayment("key-quota-3-" + unique, partnerId, consentId, "1",
                body("REF-Q3", null));
        org.junit.jupiter.api.Assertions.assertEquals(201, third.getResponse().getStatus());
        log.info("[判定依据] 独立合作方配额语义验证完成：首单成功、次单429、重放不扣费、重置恢复");
    }

    @Test
    @DisplayName("运行中撤销同意：撤销后的新支付被 CONSENT_REVOKED；已完成请求的重放仍返回确定快照")
    void revokeConsentAtRuntime() throws Exception {
        // 用专用同意隔离对共享演示数据的影响
        ObjectMapper mapper = new ObjectMapper();
        String unique = Long.toString(System.nanoTime());
        String consentId = "consent-revoke-" + unique;
        mockMvc.perform(post("/local/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of(
                                "consentId", consentId,
                                "partnerId", "partner-a",
                                "scopes", java.util.List.of("PAYMENTS_INITIATE", "PAYMENTS_READ"),
                                "ttlSeconds", 3600))))
                .andExpect(status().isOk());

        MvcResult before = createPayment("key-revoke-" + unique, "partner-a", consentId, "1",
                body("REF-REVOKE-1", null));
        org.junit.jupiter.api.Assertions.assertEquals(201, before.getResponse().getStatus());

        mockMvc.perform(post("/local/consents/" + consentId + "/revoke"))
                .andExpect(status().isOk());

        MvcResult after = createPayment("key-revoke-new-" + unique, "partner-a", consentId, "1",
                body("REF-REVOKE-2", null));
        logResult("after-revoke-new-request", after);
        org.junit.jupiter.api.Assertions.assertEquals(403, after.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                after.getResponse().getContentAsString().contains("CONSENT_REVOKED"));

        // 已完成请求的重放：幂等快照优先，仍成功返回同一结果
        MvcResult replay = createPayment("key-revoke-" + unique, "partner-a", consentId, "1",
                body("REF-REVOKE-1", null));
        logResult("after-revoke-replay", replay);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("true",
                replay.getResponse().getHeader("Idempotent-Replay"));
    }

}
