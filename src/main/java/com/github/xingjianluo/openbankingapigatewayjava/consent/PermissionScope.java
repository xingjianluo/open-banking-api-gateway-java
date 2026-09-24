package com.github.xingjianluo.openbankingapigatewayjava.consent;

/**
 * 开放银行风格的权限范围（scope）。
 *
 * <p>一次操作的放行要求"双重满足"：合作方被授予的 scope 集合 <b>与</b>
 * 客户同意（consent）上的 scope 集合都必须包含本次操作所需 scope，
 * 任一缺失都以 {@code SCOPE_INSUFFICIENT} 拒绝。</p>
 */
public enum PermissionScope {

    /** 发起支付（POST /api/payments）。 */
    PAYMENTS_INITIATE("payments:initiate"),

    /** 读取支付/资源状态（GET /api/payments/{id}）。 */
    PAYMENTS_READ("payments:read"),

    /** 读取账户信息（演示用，当前无接口，便于构造范围不足场景）。 */
    ACCOUNTS_READ("accounts:read");

    private final String claim;

    PermissionScope(String claim) {
        this.claim = claim;
    }

    /** scope 在授权字符串中使用的规范写法。 */
    public String claim() {
        return claim;
    }
}
