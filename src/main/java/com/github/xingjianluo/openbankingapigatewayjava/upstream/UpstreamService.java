package com.github.xingjianluo.openbankingapigatewayjava.upstream;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 银行核心依赖的本地模拟。
 *
 * <p>{@code simulateFailure=true} 时抛出包含"内部细节"的 {@link UpstreamException}，
 * 用于验证网关是否只对外返回 {@code UPSTREAM_UNAVAILABLE} 稳定语义而不透出内部信息。
 * 正常路径返回模拟的核心系统记账流水号。</p>
 */
@Service
public class UpstreamService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamService.class);

    public String postPaymentInstruction(String instructionRef, boolean simulateFailure) {
        log.info("调用下游核心 请求输入 instructionRef={} simulateFailure={}", instructionRef, simulateFailure);
        if (simulateFailure) {
            // 注意：这里的信息只允许留在服务端日志
            log.error("下游核心异常 instructionRef={} 内部细节=CoreLedgerTimeout host=core-bank.internal:9443 socketReset",
                    instructionRef);
            throw new UpstreamException(
                    "CoreLedgerTimeout host=core-bank.internal:9443 ref=" + instructionRef);
        }
        String ledgerNo = "LEDGER-" + instructionRef + "-" + Instant.now().toEpochMilli();
        log.info("下游核心成功 instructionRef={} ledgerNo={}", instructionRef, ledgerNo);
        return ledgerNo;
    }
}
