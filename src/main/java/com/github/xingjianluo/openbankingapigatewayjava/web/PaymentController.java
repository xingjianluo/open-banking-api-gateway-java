package com.github.xingjianluo.openbankingapigatewayjava.web;

import com.github.xingjianluo.openbankingapigatewayjava.model.api.PaymentRequest;
import com.github.xingjianluo.openbankingapigatewayjava.model.api.PaymentResponse;
import com.github.xingjianluo.openbankingapigatewayjava.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> initiate(
            @RequestHeader("X-Partner-Id") String partnerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Api-Version", required = false) String versionHeader,
            @RequestBody PaymentRequest request) {
        PaymentResponse response = paymentService.initiate(
                partnerId, idempotencyKey, versionHeader, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> get(
            @RequestHeader("X-Partner-Id") String partnerId,
            @PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.getPayment(partnerId, paymentId));
    }
}
