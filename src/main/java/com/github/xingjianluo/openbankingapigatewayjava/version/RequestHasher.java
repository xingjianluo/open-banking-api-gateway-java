package com.github.xingjianluo.openbankingapigatewayjava.version;

import com.github.xingjianluo.openbankingapigatewayjava.model.api.PaymentRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 请求内容哈希：用于幂等键冲突检测。
 * 新增可选字段缺省（null / 空白）归一为 ""，金额去除尾随零，
 * 使“新增字段缺省”与“显式空值”得到相同哈希，保持向后兼容语义。
 */
@Component
public class RequestHasher {

    public String hash(PaymentRequest request) {
        String canonical = String.join("|",
                normalize(request.consentId()),
                normalize(request.debtorAccount()),
                normalize(request.creditorAccount()),
                request.amount() == null ? "" : request.amount().stripTrailingZeros().toPlainString(),
                normalize(request.currency()),
                normalize(request.debtorName()));
        return sha256(canonical);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
