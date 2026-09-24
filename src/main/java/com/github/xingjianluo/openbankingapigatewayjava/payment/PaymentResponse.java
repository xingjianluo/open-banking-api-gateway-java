package com.github.xingjianluo.openbankingapigatewayjava.payment;

import java.time.Instant;

/**
 * 支付资源状态（发起成功后的响应，同时也是 GET 接口返回与本地持久化对象）。
 *
 * @param paymentId         网关侧支付资源标识
 * @param paymentRef        合作方业务参考号
 * @param status            资源状态：INITIATED（已在核心系统记一笔账）
 * @param ledgerNo          模拟核心系统流水号（副作用凭证，测试据此断言只执行一次）
 * @param priority          实际生效优先级
 * @param apiVersion        接受请求时协商出的 API 版本
 * @param ownerPartnerId    资源归属合作方（跨租户访问按 RESOURCE_NOT_FOUND 处理）
 * @param consentId         创建该资源所用同意
 * @param createdAt         创建时间
 */
public record PaymentResponse(
        String paymentId,
        String paymentRef,
        String status,
        String ledgerNo,
        String priority,
        String apiVersion,
        String ownerPartnerId,
        String consentId,
        Instant createdAt) {
}
