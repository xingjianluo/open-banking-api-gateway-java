package com.github.xingjianluo.openbankingapigatewayjava.consent;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

/**
 * 同意本地仓储。
 *
 * <p>存储的值是不可变 {@link Consent} 快照；撤销走 {@link ConcurrentMap#replace
 * Object Object} 的 CAS 语义，保证"读取-判定-替换"原子完成：并发撤销彼此不会覆盖，
 * 且任何读线程拿到的引用都指向一个完整快照。</p>
 */
@Repository
public class ConsentRepository {

    private final ConcurrentMap<String, Consent> consents = new ConcurrentHashMap<>();

    public void save(Consent consent) {
        consents.put(consent.consentId(), consent);
    }

    public Optional<Consent> findById(String consentId) {
        if (consentId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(consents.get(consentId));
    }

    /**
     * 原子替换：仅当存储中的当前引用与 {@code expected} 指向同一快照时才替换为
     * {@code replacement}。撤销、调额、续期都通过该原语完成，保证并发修改彼此不覆盖，
     * 且任何读线程拿到的引用都指向一个完整快照。
     *
     * @return 替换成功返回 true；引用已被其他线程改变时返回 false
     */
    public boolean compareAndReplace(Consent expected, Consent replacement) {
        return consents.replace(expected.consentId(), expected, replacement);
    }
}
