package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * 端到端 HTTP 集成测试：通过本地管理接口创建同意，再走完整支付流程，
 * 验证对外错误码与 HTTP 状态码的稳定映射、幂等重放标记及资源不存在语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    private String createConsent(String partnerId, String scopes, String status, Long ttlSeconds)
            throws Exception {
        String ttl = ttlSeconds == null ? "null" : ttlSeconds.toString();
        String body = """
                {"partnerId":"%s","scopes":["%s"],"status":"%s","ttlSeconds":%s}
                """.formatted(partnerId, scopes, status, ttl);
        log.info("[HTTP] 输入 POST /local/admin/consents body={}", body);
        MvcResult result = mockMvc.perform(post("/local/admin/consents")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String response = result.getResponse().getContentAsString();
        String consentId = response.replaceAll(".*\"consentId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        log.info("[HTTP] 判定: 同意已创建 consentId={}", consentId);
        return consentId;
    }

    private String createConsentWithTwoScopes(String partnerId, String scope1, String scope2,
                                              String status, Long ttlSeconds) throws Exception {
        String ttl = ttlSeconds == null ? "null" : ttlSeconds.toString();
        String body = """
                {"partnerId":"%s","scopes":["%s","%s"],"status":"%s","ttlSeconds":%s}
                """.formatted(partnerId, scope1, scope2, status, ttl);
        log.info("[HTTP] 输入 POST /local/admin/consents body={}", body);
        MvcResult result = mockMvc.perform(post("/local/admin/consents")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String response = result.getResponse().getContentAsString();
        String consentId = response.replaceAll(".*\"consentId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        log.info("[HTTP] 判定: 同意已创建 consentId={}", consentId);
        return consentId;
    }

    private String paymentBody(String consentId, String amount, String currency) {
        return """
                {"consentId":"%s","debtorAccount":"DEBT-1","creditorAccount":"CRED-1",
                 "amount":%s,"currency":"%s"}
                """.formatted(consentId, amount, currency);
    }

    @Test
    @DisplayName("完整成功流程 + 同键重放 replayed=true")
    void happyPath_andReplay() throws Exception {
        String consentId = createConsentWithTwoScopes("partner-full",
                "PAYMENTS_INITIATE", "PAYMENTS_READ", "AUTHORISED", 3600L);
        String body = paymentBody(consentId, "12.50", "EUR");

        log.info("[HTTP] 输入 POST /api/v2/payments key=int-key-1（首次）");
        MvcResult first = mockMvc.perform(post("/api/v2/payments")
                        .header("X-Partner-Id", "partner-full")
                        .header("Idempotency-Key", "int-key-1")
                        .header("X-Api-Version", "2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String paymentId = first.getResponse().getContentAsString()
                .replaceAll(".*\"paymentId\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        log.info("[HTTP] 输入同键重放 int-key-1，预期 replayed=true 且 paymentId 相同");
        mockMvc.perform(post("/api/v2/payments")
                        .header("X-Partner-Id", "partner-full")
                        .header("Idempotency-Key", "int-key-1")
                        .header("X-Api-Version", "2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.paymentId").value(paymentId));

        log.info("[HTTP] 查询支付 GET /api/v2/payments/{}", paymentId);
        mockMvc.perform(get("/api/v2/payments/" + paymentId)
                        .header("X-Partner-Id", "partner-full"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId));
    }

    @Test
    @DisplayName("HTTP 错误语义：未知合作方 401 / 撤销 403 / 资源不存在 404 / 冲突 409 / 废弃版本 426")
    void errorSemantics_httpStatusAndCodes() throws Exception {
        String consentId = createConsent("partner-full", "PAYMENTS_INITIATE", "AUTHORISED", 3600L);

        log.info("[HTTP] 未知合作方 -> 401 PARTNER_UNKNOWN");
        mockMvc.perform(post("/api/v2/payments")
                        .header("X-Partner-Id", "ghost")
                        .header("Idempotency-Key", "k-unknown-partner")
                        .contentType(MediaType.APPLICATION_JSON).content(paymentBody(consentId, "1", "EUR")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PARTNER_UNKNOWN"));

        log.info("[HTTP] 撤销同意后访问 -> 403 CONSENT_REVOKED");
        mockMvc.perform(post("/local/admin/consents/" + consentId + "/revoke"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v2/payments")
                        .header("X-Partner-Id", "partner-full")
                        .header("Idempotency-Key", "k-revoked")
                        .contentType(MediaType.APPLICATION_JSON).content(paymentBody(consentId, "1", "EUR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));

        log.info("[HTTP] 查询不存在支付 -> 404 RESOURCE_NOT_FOUND");
        mockMvc.perform(get("/api/v2/payments/PAY-NONE")
                        .header("X-Partner-Id", "partner-full"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        log.info("[HTTP] V1 -> 426 VERSION_DEPRECATED");
        mockMvc.perform(post("/api/v2/payments")
                        .header("X-Partner-Id", "partner-full")
                        .header("Idempotency-Key", "k-v1")
                        .header("X-Api-Version", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(paymentBody(consentId, "1", "EUR")))
                .andExpect(status().isUpgradeRequired())
                .andExpect(jsonPath("$.code").value("VERSION_DEPRECATED"));

        log.info("[HTTP] 同键不同内容 -> 409 IDEMPOTENCY_CONFLICT");
        String fresh = createConsent("partner-full", "PAYMENTS_INITIATE", "AUTHORISED", 3600L);
        mockMvc.perform(post("/api/v2/payments")
                        .header("X-Partner-Id", "partner-full")
                        .header("Idempotency-Key", "k-conf")
                        .contentType(MediaType.APPLICATION_JSON).content(paymentBody(fresh, "1", "EUR")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v2/payments")
                        .header("X-Partner-Id", "partner-full")
                        .header("Idempotency-Key", "k-conf")
                        .contentType(MediaType.APPLICATION_JSON).content(paymentBody(fresh, "2", "EUR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("范围不足(只读合作方) -> 403 SCOPE_INSUFFICIENT")
    void insufficientScope_http() throws Exception {
        String consentId = createConsent("partner-readonly", "PAYMENTS_INITIATE", "AUTHORISED", 3600L);
        log.info("[HTTP] 输入 partner-readonly 发起支付 -> 预期 403 SCOPE_INSUFFICIENT");
        mockMvc.perform(post("/api/v2/payments")
                        .header("X-Partner-Id", "partner-readonly")
                        .header("Idempotency-Key", "k-scope")
                        .contentType(MediaType.APPLICATION_JSON).content(paymentBody(consentId, "1", "EUR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SCOPE_INSUFFICIENT"));
    }

    @Test
    @DisplayName("下游故障 -> 502 DOWNSTREAM_FAILURE，响应不含内部细节")
    void downstreamFailure_http() throws Exception {
        String consentId = createConsent("partner-full", "PAYMENTS_INITIATE", "AUTHORISED", 3600L);
        log.info("[HTTP] 输入 currency=ERR -> 预期 502 DOWNSTREAM_FAILURE 且无内部引用码");
        MvcResult result = mockMvc.perform(post("/api/v2/payments")
                        .header("X-Partner-Id", "partner-full")
                        .header("Idempotency-Key", "k-err-http")
                        .contentType(MediaType.APPLICATION_JSON).content(paymentBody(consentId, "1", "ERR")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_FAILURE"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(
                result.getResponse().getContentAsString().contains("INTERNAL-7F3"));
    }
}
