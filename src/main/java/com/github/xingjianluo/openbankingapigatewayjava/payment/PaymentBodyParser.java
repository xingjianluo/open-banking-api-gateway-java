package com.github.xingjianluo.openbankingapigatewayjava.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.openbankingapigatewayjava.common.ApiException;
import com.github.xingjianluo.openbankingapigatewayjava.common.ErrorCode;

/**
 * 支付请求体解析。
 *
 * <p>容错读取（tolerant reader）：请求体出现服务端不认识的新增字段时忽略，
 * 这是"新版本新增字段不破坏老服务/老请求"的本地配合手段；请求体不是合法 JSON 时
 * 映射为 VALIDATION_FAILED 稳定语义。</p>
 */
@Component
public final class PaymentBodyParser {

    private static final Logger log = LoggerFactory.getLogger(PaymentBodyParser.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PaymentBodyParser() {
    }

    public static PaymentRequest parse(String rawBody, String contentType) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "请求体为空");
        }
        try {
            PaymentRequest request = MAPPER.readValue(rawBody, PaymentRequest.class);
            log.info("请求体解析 通过 rawLength={} contentType={} priority={}",
                    rawBody.length(), contentType, request.effectivePriority());
            return request;
        } catch (Exception ex) {
            log.info("请求体解析 失败 rawLength={} 判定依据=JSON不合法或字段类型错误: {}",
                    rawBody.length(), ex.getMessage());
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "请求体不是合法的支付 JSON");
        }
    }
}
