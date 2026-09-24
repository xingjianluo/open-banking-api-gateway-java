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
     * 原子撤销：仅当存储中的当前引用与 {@code expected} 指向同一快照时才替换为 revoked。
     *
     * @return 替换成功返回 true；已被其他线程撤销（引用已变化）时返回 false
     */
    public boolean compareAndRevoke(Consent expected, Consent revoked) {
        return consents.replace(revoked.consentId(), expected, revoked);
    }
}
