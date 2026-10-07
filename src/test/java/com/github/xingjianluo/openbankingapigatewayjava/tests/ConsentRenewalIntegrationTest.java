package com.github.xingjianluo.openbankingapigatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
 * 续期语义全链路集成测试（HTTP -> 续期判定 -> 支付链路）。
 * 每个用例使用独立合作方/同意，互不污染；判定输入与依据由应用层日志打印。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentRenewalIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConsentRenewalIntegrationTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private final String unique = Long.toString(System.nanoTime());
    private final String partnerId = "partner-renew-" + unique;

    private void newPartner() throws Exception {
        mockMvc.perform(post("/local/partners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "partnerId", partnerId,
                                "name", "续期测试合作方",
                                "scopes", List.of("PAYMENTS_INITIATE", "PAYMENTS_READ")))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    private String newConsent(String suffix, long ttlSeconds, String cumulative) throws Exception {
        String consentId = "consent-renew-" + suffix + "-" + unique;
        mockMvc.perform(post("/local/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "consentId", consentId,
                                "partnerId", partnerId,
                                "scopes", List.of("PAYMENTS_INITIATE", "PAYMENTS_READ"),
                                "ttlSeconds", ttlSeconds,
                                "perPaymentLimit", "100.00",
                                "cumulativeLimit", cumulative,
                                "limitCurrency", "GBP"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        return consentId;
    }

    private MvcResult pay(String key, String consentId, String amount) throws Exception {
        return mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Partner-Id", partnerId)
                        .header("X-Consent-Id", consentId)
                        .header("Idempotency-Key", key)
                        .content("{\"paymentRef\":\"REF-" + key + "\",\"amount\":\"" + amount + "\","
                                + "\"currency\":\"GBP\","
                                + "\"debtorAccountId\":\"DEB-1\",\"creditorAccountId\":\"CRE-9\"}"))
                .andReturn();
    }

    private MvcResult extend(String consentId, long ttlSeconds) throws Exception {
        return mockMvc.perform(post("/local/consents/" + consentId + "/extend")
                        .param("ttlSeconds", Long.toString(ttlSeconds)))
                .andReturn();
    }

    private BigDecimal used(String consentId) throws Exception {
        MvcResult r = mockMvc.perform(get("/local/consents/" + consentId + "/limits"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        return new BigDecimal(MAPPER.readTree(r.getResponse().getContentAsString()).get("used").asText());
    }

    @Test
    @DisplayName("HTTP：续期更短时长返回 409 CONSENT_EXTENSION_INVALID，同意仍按原有效期可用，用量不变")
    void shorterExtensionRejectedOverHttp() throws Exception {
        newPartner();
        String consentId = newConsent("shorter", 3600, "250.00");
        assertEquals(201, pay("k-s1-" + unique, consentId, "40.00").getResponse().getStatus());
        assertEquals(0, new BigDecimal("40.00").compareTo(used(consentId)));

        // 续期 10 秒：远短于剩余约 1 小时 -> 409，且错误码与其它失败可区分
        MvcResult rejected = extend(consentId, 10);
        log.info("[续期用例:缩短] HTTP {} 响应体={}", rejected.getResponse().getStatus(),
                rejected.getResponse().getContentAsString());
        assertEquals(409, rejected.getResponse().getStatus());
        assertTrue(rejected.getResponse().getContentAsString().contains("CONSENT_EXTENSION_INVALID"));

        // 有效期未被改短：支付照常放行；用量未被触碰
        MvcResult after = pay("k-s2-" + unique, consentId, "10.00");
        log.info("[续期用例:缩短后支付] HTTP {} 响应体={}", after.getResponse().getStatus(),
                after.getResponse().getContentAsString());
        assertEquals(201, after.getResponse().getStatus());
        assertEquals(0, new BigDecimal("50.00").compareTo(used(consentId)),
                "被拒绝的续期不得改变已累计用量");

        // 合法延长（2 小时 > 剩余约 1 小时）仍然成功
        MvcResult extended = extend(consentId, 7200);
        log.info("[续期用例:合法延长] HTTP {} 响应体={}", extended.getResponse().getStatus(),
                extended.getResponse().getContentAsString());
        assertEquals(200, extended.getResponse().getStatus());
        log.info("[判定依据] 缩短被拒(409)、状态不变、合法延长成功");
    }

    @Test
    @DisplayName("HTTP：已过期同意续期到未来后支付恢复正常，已撤销同意续期返回 403 CONSENT_REVOKED 且不可用")
    void expiredRecoversAndRevokedStaysRevoked() throws Exception {
        newPartner();
        // 已过期同意
        String expiredId = newConsent("expired", -1, "250.00");
        MvcResult before = pay("k-e1-" + unique, expiredId, "10.00");
        assertEquals(403, before.getResponse().getStatus());
        assertTrue(before.getResponse().getContentAsString().contains("CONSENT_EXPIRED"));

        MvcResult renewed = extend(expiredId, 3600);
        log.info("[续期用例:过期续期] HTTP {} 响应体={}", renewed.getResponse().getStatus(),
                renewed.getResponse().getContentAsString());
        assertEquals(200, renewed.getResponse().getStatus());
        MvcResult after = pay("k-e2-" + unique, expiredId, "10.00");
        log.info("[续期用例:过期续期后支付] HTTP {} 响应体={}", after.getResponse().getStatus(),
                after.getResponse().getContentAsString());
        assertEquals(201, after.getResponse().getStatus(), "已过期同意续期到未来后应恢复正常");
        assertEquals(0, new BigDecimal("10.00").compareTo(used(expiredId)));

        // 已撤销同意
        String revokedId = newConsent("revoked", 3600, "250.00");
        mockMvc.perform(post("/local/consents/" + revokedId + "/revoke"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        MvcResult extendRevoked = extend(revokedId, 86400);
        log.info("[续期用例:撤销后续期] HTTP {} 响应体={}", extendRevoked.getResponse().getStatus(),
                extendRevoked.getResponse().getContentAsString());
        assertEquals(403, extendRevoked.getResponse().getStatus());
        assertTrue(extendRevoked.getResponse().getContentAsString().contains("CONSENT_REVOKED"));
        // 续期尝试后依然不可用，且拒绝原因仍是撤销而非其它
        MvcResult payRevoked = pay("k-r1-" + unique, revokedId, "10.00");
        assertEquals(403, payRevoked.getResponse().getStatus());
        assertTrue(payRevoked.getResponse().getContentAsString().contains("CONSENT_REVOKED"));
        assertEquals(0, BigDecimal.ZERO.compareTo(used(revokedId)), "撤销同意不得产生用量");
        log.info("[判定依据] 过期续期恢复 201；撤销续期 403 CONSENT_REVOKED 且支付仍拒");
    }
}
