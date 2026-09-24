package com.github.xingjianluo.openbankingapigatewayjava.model.consent;

/**
 * 同意/授权范围。合作方被授予的权限与同意所覆盖的范围均使用本枚举。
 */
public enum ConsentScope {
    ACCOUNTS_READ,
    PAYMENTS_INITIATE,
    PAYMENTS_READ
}
