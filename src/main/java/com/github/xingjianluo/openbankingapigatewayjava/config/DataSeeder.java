package com.github.xingjianluo.openbankingapigatewayjava.config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.xingjianluo.openbankingapigatewayjava.consent.Consent;
import com.github.xingjianluo.openbankingapigatewayjava.consent.ConsentRepository;
import com.github.xingjianluo.openbankingapigatewayjava.consent.PermissionScope;
import com.github.xingjianluo.openbankingapigatewayjava.partner.Partner;
import com.github.xingjianluo.openbankingapigatewayjava.partner.PartnerRepository;
import com.github.xingjianluo.openbankingapigatewayjava.quota.QuotaService;

/**
 * 本地演示数据：无需真实合作方注册/客户授权即可演练全部判定分支。
 *
 * <p>预置合作方：</p>
 * <ul>
 *   <li>{@code partner-a}：拥有 initiate+read；配额 1000；</li>
 *   <li>{@code partner-limited}：仅有 read（可演示合作方侧 scope 不足）；配额 2。</li>
 * </ul>
 *
 * <p>预置同意（均归属 partner-a，除 cross 外）：</p>
 * <ul>
 *   <li>{@code consent-ok}：有效，含 initiate+read；</li>
 *   <li>{@code consent-expired}：有效期已过；</li>
 *   <li>{@code consent-revoked}：已撤销终态；</li>
 *   <li>{@code consent-noinitiate}：有效但只含 read（同意侧 scope 不足）；</li>
 *   <li>{@code consent-cross}：归属 partner-limited（跨租户访问按不存在处理）。</li>
 * </ul>
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    public CommandLineRunner seedData(PartnerRepository partnerRepository,
                                      ConsentRepository consentRepository,
                                      QuotaService quotaService) {
        return args -> {
            Instant now = Instant.now();

            Partner partnerA = new Partner("partner-a", "演示合作方A",
                    Set.of(PermissionScope.PAYMENTS_INITIATE, PermissionScope.PAYMENTS_READ));
            Partner partnerLimited = new Partner("partner-limited", "演示合作方(只读低配额)",
                    Set.of(PermissionScope.PAYMENTS_READ));
            partnerRepository.save(partnerA);
            partnerRepository.save(partnerLimited);

            consentRepository.save(Consent.authorised("consent-ok", "partner-a",
                    Set.of(PermissionScope.PAYMENTS_INITIATE, PermissionScope.PAYMENTS_READ),
                    now.minus(1, ChronoUnit.HOURS), now.plus(7, ChronoUnit.DAYS)));
            consentRepository.save(Consent.authorised("consent-expired", "partner-a",
                    Set.of(PermissionScope.PAYMENTS_INITIATE, PermissionScope.PAYMENTS_READ),
                    now.minus(10, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS)));
            Consent revoked = Consent.authorised("consent-revoked", "partner-a",
                    Set.of(PermissionScope.PAYMENTS_INITIATE, PermissionScope.PAYMENTS_READ),
                    now.minus(2, ChronoUnit.HOURS), now.plus(7, ChronoUnit.DAYS))
                    .asRevoked(now.minus(30, ChronoUnit.MINUTES));
            consentRepository.save(revoked);
            consentRepository.save(Consent.authorised("consent-noinitiate", "partner-a",
                    Set.of(PermissionScope.PAYMENTS_READ),
                    now.minus(1, ChronoUnit.HOURS), now.plus(7, ChronoUnit.DAYS)));
            consentRepository.save(Consent.authorised("consent-cross", "partner-limited",
                    Set.of(PermissionScope.PAYMENTS_INITIATE, PermissionScope.PAYMENTS_READ),
                    now.minus(1, ChronoUnit.HOURS), now.plus(7, ChronoUnit.DAYS)));
            // 带额度约束的同意：单笔上限 100、累计上限 250、约定币种 GBP
            consentRepository.save(Consent.authorisedWithLimits("consent-capped", "partner-a",
                    Set.of(PermissionScope.PAYMENTS_INITIATE, PermissionScope.PAYMENTS_READ),
                    now.minus(1, ChronoUnit.HOURS), now.plus(7, ChronoUnit.DAYS),
                    new java.math.BigDecimal("100.00"), new java.math.BigDecimal("250.00"), "GBP"));

            quotaService.configureLimit("partner-a", 1000);
            quotaService.configureLimit("partner-limited", 2);

            log.info("演示数据初始化完成：合作方[partner-a, partner-limited]，同意[consent-ok, consent-expired, consent-revoked, consent-noinitiate, consent-cross, consent-capped(单笔100/累计250/GBP)]");
        };
    }
}
