package com.github.xingjianluo.openbankingapigatewayjava.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;
import com.github.xingjianluo.openbankingapigatewayjava.version.ApiVersion;
import com.github.xingjianluo.openbankingapigatewayjava.version.VersionService;

/**
 * 开放银行风格支付接口。
 *
 * <p>判定性请求头：</p>
 * <ul>
 *   <li>{@code X-Partner-Id}：合作方身份（本地模拟，替代 mTLS/Token）；</li>
 *   <li>{@code X-Consent-Id}：客户同意标识；</li>
 *   <li>{@code Idempotency-Key}：提交幂等键，必传；</li>
 *   <li>{@code X-Api-Version}：版本声明，缺省 v1；</li>
 *   <li>{@code X-Simulate-Upstream-Failure}：仅本地演练用，置 {@code true} 制造下游异常。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final VersionService versionService;

    public PaymentController(PaymentService paymentService, VersionService versionService) {
        this.paymentService = paymentService;
        this.versionService = versionService;
    }

    @PostMapping
    public ResponseEntity<Object> create(
            @RequestHeader(name = "X-Partner-Id", required = false) String partnerId,
            @RequestHeader(name = "X-Consent-Id", required = false) String consentId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
            @RequestHeader(name = "X-Api-Version", required = false) String versionHeader,
            @RequestHeader(name = "X-Simulate-Upstream-Failure", required = false,
                    defaultValue = "false") boolean failUpstream,
            @RequestBody String rawBody,
            @RequestHeader(name = "Content-Type", required = false) String contentType) {

        requirePartnerHeader(partnerId);
        ApiVersion version = versionService.resolve(versionHeader);
        PaymentRequest request = PaymentBodyParser.parse(rawBody, contentType);
        String fingerprint = sha256(rawBody == null ? "" : rawBody);

        PaymentService.CreateResult result = paymentService.create(
                version, partnerId, consentId, idemKey, fingerprint, request, failUpstream);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus());
        if (result.outcome() == com.github.xingjianluo.openbankingapigatewayjava.idempotency.IdempotencyOutcome.REPLAY) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(result.body());
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(
            @RequestHeader(name = "X-Partner-Id", required = false) String partnerId,
            @RequestHeader(name = "X-Consent-Id", required = false) String consentId,
            @RequestHeader(name = "X-Api-Version", required = false) String versionHeader,
            @PathVariable String paymentId) {
        requirePartnerHeader(partnerId);
        versionService.resolve(versionHeader);
        return paymentService.get(partnerId, consentId, paymentId);
    }

    private static void requirePartnerHeader(String partnerId) {
        if (partnerId == null || partnerId.isBlank()) {
            throw new ApiException(ErrorCode.PARTNER_UNKNOWN, "缺少请求头 X-Partner-Id");
        }
    }

    /** 对原始请求字节做指纹：任何内容差异都会改变幂等判定。 */
    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
