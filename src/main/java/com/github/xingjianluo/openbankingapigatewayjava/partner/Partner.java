package com.github.xingjianluo.openbankingapigatewayjava.partner;

import java.util.Set;

import com.github.xingjianluo.openbankingapigatewayjava.consent.PermissionScope;

/**
 * 合作方（TPP，第三方提供商）。
 *
 * <p>本地模拟：不做真实 OAuth/mTLS，合作方身份由请求头 {@code X-Partner-Id} 声明。
 * 合作方拥有平台侧授予的不可变权限集合 {@code grantedScopes}。</p>
 *
 * @param partnerId     合作方标识
 * @param name          名称（仅展示/日志）
 * @param grantedScopes 平台授予该合作方的权限范围（不可变）
 */
public record Partner(String partnerId, String name, Set<PermissionScope> grantedScopes) {

    public Partner {
        grantedScopes = grantedScopes == null ? Set.of() : Set.copyOf(grantedScopes);
    }
}
