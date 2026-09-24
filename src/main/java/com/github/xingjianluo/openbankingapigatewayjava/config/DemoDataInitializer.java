package com.github.xingjianluo.openbankingapigatewayjava.config;

import com.github.xingjianluo.openbankingapigatewayjava.model.consent.ConsentScope;
import com.github.xingjianluo.openbankingapigatewayjava.model.partner.Partner;
import com.github.xingjianluo.openbankingapigatewayjava.partner.PartnerRepository;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 预置本地演示合作方：
 * <ul>
 *   <li>partner-full：拥有全部支付与账户权限；</li>
 *   <li>partner-readonly：仅有账户只读权限（用于范围不足场景）。</li>
 * </ul>
 * 同意对象不预置，由本地管理接口 /local/admin/consents 动态创建，便于覆盖
 * 过期、撤销、范围不足等全部边界场景。
 */
@Configuration
public class DemoDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    @Bean
    CommandLineRunner demoData(PartnerRepository partnerRepository) {
        return args -> {
            partnerRepository.save(new Partner("partner-full", "Full Access TPP",
                    Set.of(ConsentScope.ACCOUNTS_READ, ConsentScope.PAYMENTS_INITIATE,
                            ConsentScope.PAYMENTS_READ)));
            partnerRepository.save(new Partner("partner-readonly", "Read Only TPP",
                    Set.of(ConsentScope.ACCOUNTS_READ)));
            log.info("演示合作方已就绪: partner-full(全权限), partner-readonly(仅 ACCOUNTS_READ)");
        };
    }
}
