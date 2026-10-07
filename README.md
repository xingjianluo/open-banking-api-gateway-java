# Open Banking API Gateway（本地集成与授权访问流程演练）

一个基于 Spring Boot 3 / Java 21 的**开放银行风格网关**本地实现。不依赖外部授权服务器、
数据库或银行核心系统，全部用进程内组件模拟「合作方 → 客户同意 → 授权访问 → 额度管控 →
幂等提交 → 多版本兼容 → 配额/下游容错」链路，目标是让**权限、额度、幂等与版本兼容在并发
调用下得到一致且可解释的结论**。

---

## 1. 领域模型：合作方、同意与权限

### 1.1 合作方（Partner / TPP）

- 本地以请求头 `X-Partner-Id` 标识合作方（替代真实的 mTLS / OAuth Token 校验）。
- 合作方拥有平台侧授予的**不可变权限集合** `grantedScopes`。
- 预置：`partner-a`（initiate+read，配额 1000）、`partner-limited`（仅 read，配额 2）。

### 1.2 同意（Consent）

客户授予某个合作方、在有效期内、针对一组 scope 的访问许可。字段：
`consentId / partnerId / scopes / status / createdAt / expiresAt / revokedAt`，
以及可选额度字段 `perPaymentLimit / cumulativeLimit / limitCurrency`（见第 2 节）。

- 状态：`AUTHORISED`（有效）→ 终态 `REVOKED`（显式撤销）；`EXPIRED` 由 `expiresAt` 动态判定。
- **不可变快照**：`Consent` 是 record，撤销、调额、续期都不就地改字段，而是由仓储用
  CAS（`ConcurrentMap.replace(old,new)`）原子替换为新快照。任何线程读到的永远是
  完整的旧快照或完整的新快照，授权/额度判定基于同一组不可变引用，**并发期间不存在
  半更新、中途可见的不一致结果**。

### 1.3 授权判定（双重 scope 满足）

一次操作要求「合作方被授予的 scope」**与**「同意上的 scope」**都**包含所需 scope。
固定判定顺序与**互不相同**的拒绝原因：

| 顺序 | 情形 | 错误码 | HTTP |
|---|---|---|---|
| 1 | 同意不存在 / 不属于该合作方（不泄露存在性） | `CONSENT_NOT_FOUND` | 403 |
| 2 | 同意已被撤销 | `CONSENT_REVOKED` | 403 |
| 3 | 当前时间 ≥ `expiresAt` | `CONSENT_EXPIRED` | 403 |
| 4 | 合作方或同意任一缺所需 scope | `SCOPE_INSUFFICIENT` | 403 |
| 5 | 授权通过但资源不存在 / 不归属该合作方 | `RESOURCE_NOT_FOUND` | 404 |

> 撤销优先于过期判定（撤销是主动行为）；资源存在性在授权通过后才检查，避免未授权探测。

预置同意：`consent-ok`（有效）、`consent-expired`（过期）、`consent-revoked`（已撤销）、
`consent-noinitiate`（有效但只含 read）、`consent-cross`（归属 partner-limited）、
`consent-capped`（带额度：单笔 100 / 累计 250 / GBP）。

---

## 2. 同意额度与累计用量

同意除"能不能调用"（授权）外，还可携带"能用多少"（额度）。额度字段挂在 `Consent`
不可变快照上，创建与调整都走本地管理接口；**三个字段均可选，缺省即不约束**，
未配置额度的同意行为与引入本能力之前完全一致。

### 2.1 额度模型

| 字段 | 含义 | 缺省 |
|---|---|---|
| `perPaymentLimit` | 单笔支付金额上限 | null = 不限制 |
| `cumulativeLimit` | 累计成功支付金额上限 | null = 不限制 |
| `limitCurrency` | 额度约定币种（ISO 4217） | null = 不限制币种 |
| `used`（用量账本） | 该同意已落账的累计金额，按 consentId 独立记账 | 0 |

发起支付时在授权通过之后、配额与下游之前做额度判定，**判定依据与授权判定是同一份
同意快照**，固定顺序、三种拒绝原因互不相同：

| 顺序 | 情形 | 错误码 | HTTP |
|---|---|---|---|
| 1 | 约定了币种且请求币种不一致 | `CURRENCY_MISMATCH` | 403 |
| 2 | 约定了单笔上限且金额超过 | `PAYMENT_LIMIT_EXCEEDED` | 403 |
| 3 | 约定了累计上限且"已用+本次"将超出 | `CUMULATIVE_LIMIT_EXCEEDED` | 403 |

额度内的请求继续走原有的授权、幂等、配额、下游链路，不做任何改变。

### 2.2 并发一致性语义

- **不透支**：累计用量是每同意一个 `AtomicReference<BigDecimal>`，"读已用 → 校验
  不超上限 → 写入新已用"用 CAS 循环一次完成。剩余额度只够一笔时，并发请求中只有一个
  CAS 成功，其余重试时读到新余额并以 `CUMULATIVE_LIMIT_EXCEEDED` 拒绝——不会同时读到
  旧余额一起通过。成功请求的累计金额绝不会超过判定时所依据的累计上限。
- **只计真实副作用**：判定通过即**预占**（金额计入用量）；预占之后任何失败
  （配额 429、下游 503 等副作用未落账的情况）都通过幂等的 `release()` 退回预占，
  重复/并发释放只退一次。被拒绝的请求在预占前就已抛出、幂等重放根本走不到额度判定，
  两者都不消耗额度；同键重放拿到的是首次缓存的确定结果，不重复累计。
- **调额/续期无中间态**：调额（`POST /local/consents/{id}/limits`，可上调/下调/取消
  约束）与续期（`POST /local/consents/{id}/extend`）都是把同意快照整体 CAS 替换。
  并发进行中的支付只读到调整前或调整后的**完整快照之一**，结论相对所读快照确定：
  例如下调后新请求按新上限拒绝、上调后新请求放行，而已有用量不受调额影响。

### 2.3 续期语义：只能延长，不能缩短

`POST /local/consents/{id}/extend?ttlSeconds=n` 把有效期改为 `now + n`，但**仅当新截止
时间严格晚于当前截止时间时才生效**。判定在 CAS 循环内针对每次读到的当前快照进行，
杜绝"按旧快照验过、却覆盖了更新的截止时间"的竞态。固定结论：

| 情形 | 结论 | HTTP |
|---|---|---|
| 新截止时间 > 当前截止时间 | 续期生效，其余字段（额度配置、状态）不变 | 200 |
| 新截止时间 ≤ 当前截止时间（更短或持平） | `CONSENT_EXTENSION_INVALID`，同意原样不变 | 409 |
| 同意已撤销（REVOKED 终态） | `CONSENT_REVOKED`，**续期不能让撤销的同意复活** | 403 |
| 同意不存在 | `CONSENT_NOT_FOUND` | 403 |

要点：

- **被拒绝即零副作用**：续期被拒绝或 CAS 竞争未生效时，存储中的快照一个字节都不动，
  有效期、额度配置原样保留；累计用量是独立账本，续期本来就不触碰，绝不会出现
  "有效期改了但额度没改"之类的半更新状态。
- **过期可救、撤销不可救**：已过期但未撤销的同意续到未来后恢复正常使用（授权重新
  放行）；已撤销是终态，续期一律 `CONSENT_REVOKED`，且状态、有效期保持原样。
- **与支付并发的确定结论**：续期进行中的支付只会基于续期前或续期后的完整快照之一
  得出结论——对过期同意的续期场景，每笔支付只有 `201` 或 `CONSENT_EXPIRED` 两种
  可解释结论，没有第三种；调额与支付并发同理，每笔只有 `201` 或
  `CUMULATIVE_LIMIT_EXCEEDED`，且用量账本恰为成功支付金额之和，不会被调额弄乱。

### 2.4 版本兼容范围

- 支付请求体**没有**新增字段；额度信息完全在服务端同意模型上，老调用方零改造。
- 老方式创建的同意（不带额度字段）= 无约束，行为与之前逐字节一致。
- `v0` 依旧 `VERSION_DEPRECATED`、未知版本依旧 `VERSION_UNSUPPORTED`，
  不因新能力静默降级。

---

## 3. 幂等提交与重放防护

对 `POST /api/payments`：

- 请求头 `Idempotency-Key`（必传）+ 请求体 **SHA-256 指纹**共同标识一次提交。
- 记录状态：`IN_FLIGHT`（首个请求处理中）→ `COMPLETED`（已缓存确定性响应快照）。
- 存储用 256 个分段（stripe）锁：同一键的「检查-插入」临界区串行，不同键并行。

判定结论：

| 情形 | 结论 | HTTP | 行为 |
|---|---|---|---|
| 键首次出现 | `PROCEED` | 201 | 唯一执行权，副作用**仅一次** |
| 同键同内容，已完成 | `REPLAY` | 201 + 响应头 `Idempotent-Replay: true` | 返回**字节一致的缓存结果**，不重复执行、不扣配额 |
| 同键并发（首请求仍在处理） | `IDEMPOTENCY_IN_FLIGHT` | 409 | 明确拒绝，不静默排队/执行 |
| 同键但请求体指纹不同 / 键归属其他合作方 | `IDEMPOTENCY_CONFLICT` | 409 | 拒绝，**不静默覆盖**原结果 |
| 超过保留窗口（默认 24h）复用键 | `IDEMPOTENCY_KEY_EXPIRED` | 410 | 拒绝复用，须更换新键 |

要点：

- **并发同键 N 个请求恰好 1 个拿到执行权**；后到且首请求未完成者得到 409，首请求完成后
  到达者得到同一 201 快照——无论如何资源只创建一次、`ledgerNo`（下游副作用凭证）唯一。
- 首个请求即使在授权/配额/下游环节失败，其稳定错误结论也会作为该键的确定性快照缓存，
  随后重放得到**相同结论**，不会反复触发副作用。
- 保留期由 `obg.idempotency.retention-seconds` 配置。

---

## 4. 多版本兼容

请求头 `X-Api-Version`（接受 `1` / `v1` 等写法）：

| 声明 | 处理 |
|---|---|
| 缺省 / 空白 | 回落基线 **v1**，老调用方零改造 |
| `v1`、`v2` | 支持 |
| `v0` | 已废弃且语义不兼容 → `VERSION_DEPRECATED`（400），**拒绝静默降级** |
| 从未发布（如 `v3`、`v9`、`abc`） | `VERSION_UNSUPPORTED`（400），与 DEPRECATED 原因可区分 |

兼容规则：

- v2 相对 v1 **仅新增可选字段**：支付请求新增 `priority`（`NORMAL`/`HIGH`），缺省时取
  `NORMAL`，语义与 v1 完全一致（新增字段不改变既有行为）。
- 服务端是 tolerant reader：忽略请求体中不认识的字段（`FAIL_ON_UNKNOWN_PROPERTIES=false`）。
- 破坏性变更（语义不兼容的老版本）必须发新错误码拒绝，不允许静默降级到别的语义。

---

## 5. 统一错误语义

所有错误返回稳定结构，**不泄漏内部实现细节**（无堆栈/主机名/SQL/下游报文）：

```json
{
  "code": "CONSENT_EXPIRED",
  "message": "同意已过期",
  "reason": "consentId=consent-expired 有效期截止 2026-09-24T06:27:46Z",
  "timestamp": "2026-09-24T07:28:19Z"
}
```

| code | HTTP | 含义 |
|---|---|---|
| `VALIDATION_FAILED` | 400 | 缺必要头/字段、JSON 非法 |
| `VERSION_UNSUPPORTED` | 400 | 版本从未发布 |
| `VERSION_DEPRECATED` | 400 | 版本已废弃且不兼容 |
| `PARTNER_UNKNOWN` | 401 | 合作方缺失/不存在 |
| `CONSENT_NOT_FOUND` / `CONSENT_REVOKED` / `CONSENT_EXPIRED` / `SCOPE_INSUFFICIENT` | 403 | 四种可区分的授权拒绝 |
| `CONSENT_EXTENSION_INVALID` | 409 | 续期新截止时间未晚于当前截止时间（续期只能延长不能缩短） |
| `PAYMENT_LIMIT_EXCEEDED` / `CUMULATIVE_LIMIT_EXCEEDED` / `CURRENCY_MISMATCH` | 403 | 三种可区分的额度拒绝（单笔超限 / 累计将超 / 币种不符） |
| `RESOURCE_NOT_FOUND` | 404 | 资源不存在/跨租户不可见 |
| `IDEMPOTENCY_IN_FLIGHT` / `IDEMPOTENCY_CONFLICT` | 409 | 处理中并发 / 内容或归属冲突 |
| `IDEMPOTENCY_KEY_EXPIRED` | 410 | 幂等键超期复用 |
| `QUOTA_EXCEEDED` | 429 | 合作方配额用尽（仅真实执行计费，重放/被拒不计费） |
| `UPSTREAM_UNAVAILABLE` | 503 | 下游银行核心异常的稳定映射（内部细节仅入服务端日志） |
| `INTERNAL_ERROR` | 500 | 未预期异常兜底 |

---

## 6. HTTP 接口

### 对外（开放银行风格）

- `POST /api/payments` 发起支付
  - 头：`X-Partner-Id`、`X-Consent-Id`、`Idempotency-Key`、`X-Api-Version`（可选）、
    `X-Simulate-Upstream-Failure: true`（仅演练，制造下游 503）
- `GET /api/payments/{paymentId}` 查询支付资源（需 `payments:read`）

### 本地管理（仅演练用，`/local/**`）

- `POST /local/partners` 注册合作方
- `POST /local/consents` 构造同意（`ttlSeconds` 传负数可造过期同意；可选
  `perPaymentLimit / cumulativeLimit / limitCurrency` 带额度）
- `POST /local/consents/{id}/revoke` 运行时撤销
- `POST /local/consents/{id}/limits` 调整额度（可上调/下调，字段传 null 取消该项约束）
- `POST /local/consents/{id}/extend?ttlSeconds=n` 续期（**只能延长**：新截止时间须晚于当前
  截止时间，否则 409 `CONSENT_EXTENSION_INVALID`；已撤销同意 403 `CONSENT_REVOKED`）
- `GET  /local/consents/{id}/limits` 查看额度配置、状态、有效期与当前累计用量
- `POST /local/consents/{id}/usage/reset` 重置累计用量（演示用）
- `POST /local/quotas/{partnerId}/limit?limit=n`、`POST /local/quotas/{partnerId}/reset`
- `GET  /local/quotas`、`GET /local/payments/count`

---

## 7. 本地验证方法

前置：JDK 21+、Maven（仓库已缓存 Spring Boot 3.4.1，支持离线 `-o`）。

### 7.1 运行测试（58 个用例）

```bash
mvn test                 # 全量
mvn test -Dtest=ConsentServiceTest         # 授权边界 + 并发撤销一致性
mvn test -Dtest=IdempotencyServiceTest     # 单次副作用/重放/冲突/并发/超期
mvn test -Dtest=VersionServiceTest         # 版本兼容与拒绝
mvn test -Dtest=PaymentIntegrationTest     # 全链路 HTTP（MockMvc）
mvn test -Dtest=LimitServiceTest           # 额度判定/并发不透支/预占退回/调额续期
mvn test -Dtest=PaymentLimitIntegrationTest # 额度全链路 HTTP（MockMvc）
```

测试覆盖：过期 / 已撤销 / 范围不足 / 不存在的同意拒绝且原因可区分；同键并发重复提交
只产生一次副作用；幂等内容冲突与超期复用被拒；v0/v9 版本拒绝；配额 429；下游异常 503
不透出内部细节；运行时撤销后新请求拒绝而历史重放仍确定。
本轮新增：单笔超限 / 累计超限 / 币种不符三种拒绝可区分；并发 20 笔抢 3 笔额度恰好
放行 3 笔且用量等于上限（不透支）；同键重放不重复累计；下游异常与配额拒绝退回预占；
调额下调即拒、上调即放且不动已有用量；续期期间并发支付只有 201 / CONSENT_EXPIRED
两种确定结论；老请求方式与 v0 拒绝行为无回归。
本轮新增：续期更短/持平被 409 `CONSENT_EXTENSION_INVALID` 拒绝且有效期、额度、用量
原样不变；已过期同意续期到未来后恢复可用；已撤销同意续期被 403 `CONSENT_REVOKED`
拒绝且不复活；续期与授权直接并发只有 PASS / EXPIRED 两种结论；调额与支付**直接并发**
（同一发令枪起跑）只有 201 / CUMULATIVE_LIMIT_EXCEEDED 两种结论且用量恰为成功金额
之和。并发用例以发令枪对齐起跑、以确定性计数断言收口（调度相关的具体计数不断言，
两种结论本身由并发区前后的顺序调用确定性验证），反复运行结论一致。

> 单测与应用层日志都会打印**请求输入与判定依据**（例如 `授权拒绝 ... 判定依据=同意已过期
> now=... expiresAt=...`、`幂等判定 ... 判定依据=...`），便于解释每一次结论。

### 7.2 启动后用 curl 演练

```bash
mvn spring-boot:run        # 或 mvn package && java -jar target/*.jar
```

```bash
B='{"paymentRef":"SMOKE-1","amount":"12.50","currency":"GBP","debtorAccountId":"DEB-1","creditorAccountId":"CRE-9"}'

# 正常创建
curl -i -X POST localhost:8080/api/payments -H 'Content-Type: application/json' \
  -H 'X-Partner-Id: partner-a' -H 'X-Consent-Id: consent-ok' \
  -H 'Idempotency-Key: k1' -H 'X-Api-Version: 2' -d "$B"

# 同键同体重放 -> 201 + Idempotent-Replay: true，同一 paymentId
curl -i -X POST localhost:8080/api/payments -H 'Content-Type: application/json' \
  -H 'X-Partner-Id: partner-a' -H 'X-Consent-Id: consent-ok' \
  -H 'Idempotency-Key: k1' -H 'X-Api-Version: 2' -d "$B"

# 同键不同体 -> 409 IDEMPOTENCY_CONFLICT（自行修改 paymentRef）
# 过期/撤销/范围不足：把 X-Consent-Id 换成 consent-expired / consent-revoked / consent-noinitiate
# 版本：X-Api-Version: 0 -> VERSION_DEPRECATED；: 9 -> VERSION_UNSUPPORTED
# 下游故障：加头 X-Simulate-Upstream-Failure: true -> 503 UPSTREAM_UNAVAILABLE

# 20 并发同键（应只有一个 paymentId，/local/payments/count 只 +1）
seq 1 20 | xargs -P20 -I{} curl -s -X POST localhost:8080/api/payments \
  -H 'Content-Type: application/json' -H 'X-Partner-Id: partner-a' \
  -H 'X-Consent-Id: consent-ok' -H 'Idempotency-Key: hot' -H 'X-Api-Version: 2' -d "$B"
```

额度演练（预置 `consent-capped`：单笔上限 100、累计上限 250、约定币种 GBP）：

```bash
H='-H Content-Type:application/json -H X-Partner-Id:partner-a -H X-Consent-Id:consent-capped'
P() { curl -s -X POST localhost:8080/api/payments $H -H "Idempotency-Key: $1" \
  -d "{\"paymentRef\":\"$1\",\"amount\":\"$2\",\"currency\":\"$3\",\"debtorAccountId\":\"DEB-1\",\"creditorAccountId\":\"CRE-9\"}"; echo; }

P lim1 100.00 GBP     # 201，已用 100
P lim2 100.00 GBP     # 201，已用 200
P lim3 100.01 GBP     # 403 PAYMENT_LIMIT_EXCEEDED（超单笔上限）
P lim4 10.00 EUR      # 403 CURRENCY_MISMATCH（币种不符）
P lim5 60.00 GBP      # 403 CUMULATIVE_LIMIT_EXCEEDED（200+60 超 250）
P lim2 100.00 GBP     # 同键重放 -> 201 + Idempotent-Replay，不重复累计
curl -s localhost:8080/local/consents/consent-capped/limits   # 查看额度与已用（=200）

# 调额与续期
curl -s -X POST localhost:8080/local/consents/consent-capped/limits \
  -H 'Content-Type: application/json' \
  -d '{"perPaymentLimit":"100.00","cumulativeLimit":"500.00","limitCurrency":"GBP"}'
P lim6 60.00 GBP      # 上调后 -> 201
curl -s -X POST 'localhost:8080/local/consents/consent-capped/extend?ttlSeconds=86400'   # 延长 -> 200
curl -s -X POST 'localhost:8080/local/consents/consent-capped/extend?ttlSeconds=10'
# ^ 新截止时间比当前短 -> 409 CONSENT_EXTENSION_INVALID，有效期/额度/用量均不变
curl -s localhost:8080/local/consents/consent-capped/limits   # 可核对 expiresAt 与 used 未变

# 并发不透支：重新造一个累计上限 90 的同意，20 并发各 30 -> 恰好 3 个 201
curl -s -X POST localhost:8080/local/consents -H 'Content-Type: application/json' -d \
  '{"consentId":"consent-race","partnerId":"partner-a","scopes":["PAYMENTS_INITIATE","PAYMENTS_READ"],"ttlSeconds":3600,"perPaymentLimit":"100.00","cumulativeLimit":"90.00","limitCurrency":"GBP"}'
seq 1 20 | xargs -P20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/payments \
  -H 'Content-Type: application/json' -H 'X-Partner-Id: partner-a' -H 'X-Consent-Id: consent-race' \
  -H "Idempotency-Key: race-{}" \
  -d '{"paymentRef":"race-{}","amount":"30.00","currency":"GBP","debtorAccountId":"DEB-1","creditorAccountId":"CRE-9"}' | sort | uniq -c
# 期望：3 个 201、17 个 403；GET /local/consents/consent-race/limits 的 used 恰为 90.00
```

---

## 8. 代码结构

```
common/        ErrorCode、ApiException、ErrorResponse、GlobalExceptionHandler（稳定错误语义）
partner/       Partner、PartnerRepository
consent/       PermissionScope、Consent(不可变快照,含可选额度字段)、ConsentRepository(CAS)、ConsentService(访问控制/调额/续期-只延不缩)
limit/         LimitService(额度判定+原子预占/退回)、LimitReservation(幂等释放句柄)
idempotency/   分段锁存储、IdempotencyRecord、IdempotencyService（PROCEED/REPLAY/拒绝）
version/       ApiVersion、VersionService（缺省 v1 / v0 废弃 / 未知拒绝）
quota/         QuotaService（合作方配额，真实执行才计费）
upstream/      UpstreamService、UpstreamException（下游故障注入与隔离）
payment/       PaymentRequest/Response、Repository、PaymentService 编排、Controller、BodyParser
local/         LocalAdminController（本地造数/撤销/配额）
config/        DataSeeder（预置合作方与各状态同意）、JacksonConfig
```

> 说明：当前为进程内内存实现（重启即重置），生产可将 Consent/Idempotency/Quota 仓储
> 替换为数据库或 Redis（幂等对应 `SET key value NX PX ttl`），领域判定与错误语义保持不变。
