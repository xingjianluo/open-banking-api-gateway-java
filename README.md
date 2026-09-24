# Open Banking API Gateway（本地集成与授权访问流程演练）

一个基于 Spring Boot 3 / Java 21 的**开放银行风格网关**本地实现。不依赖外部授权服务器、
数据库或银行核心系统，全部用进程内组件模拟「合作方 → 客户同意 → 授权访问 → 幂等提交 →
多版本兼容 → 配额/下游容错」链路，目标是让**权限、幂等与版本兼容在并发调用下得到一致且
可解释的结论**。

---

## 1. 领域模型：合作方、同意与权限

### 1.1 合作方（Partner / TPP）

- 本地以请求头 `X-Partner-Id` 标识合作方（替代真实的 mTLS / OAuth Token 校验）。
- 合作方拥有平台侧授予的**不可变权限集合** `grantedScopes`。
- 预置：`partner-a`（initiate+read，配额 1000）、`partner-limited`（仅 read，配额 2）。

### 1.2 同意（Consent）

客户授予某个合作方、在有效期内、针对一组 scope 的访问许可。字段：
`consentId / partnerId / scopes / status / createdAt / expiresAt / revokedAt`。

- 状态：`AUTHORISED`（有效）→ 终态 `REVOKED`（显式撤销）；`EXPIRED` 由 `expiresAt` 动态判定。
- **不可变快照**：`Consent` 是 record，撤销不就地改字段，而是由仓储用 CAS
  （`ConcurrentMap.replace(old,new)`）原子替换为新的 REVOKED 快照。任何线程读到的永远是
  完整的旧快照或完整的新快照，授权判定基于同一组不可变引用，**并发期间不存在半更新、
  中途可见的不一致结果**。

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
`consent-noinitiate`（有效但只含 read）、`consent-cross`（归属 partner-limited）。

---

## 2. 幂等提交与重放防护

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

## 3. 多版本兼容

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

## 4. 统一错误语义

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
| `RESOURCE_NOT_FOUND` | 404 | 资源不存在/跨租户不可见 |
| `IDEMPOTENCY_IN_FLIGHT` / `IDEMPOTENCY_CONFLICT` | 409 | 处理中并发 / 内容或归属冲突 |
| `IDEMPOTENCY_KEY_EXPIRED` | 410 | 幂等键超期复用 |
| `QUOTA_EXCEEDED` | 429 | 合作方配额用尽（仅真实执行计费，重放/被拒不计费） |
| `UPSTREAM_UNAVAILABLE` | 503 | 下游银行核心异常的稳定映射（内部细节仅入服务端日志） |
| `INTERNAL_ERROR` | 500 | 未预期异常兜底 |

---

## 5. HTTP 接口

### 对外（开放银行风格）

- `POST /api/payments` 发起支付
  - 头：`X-Partner-Id`、`X-Consent-Id`、`Idempotency-Key`、`X-Api-Version`（可选）、
    `X-Simulate-Upstream-Failure: true`（仅演练，制造下游 503）
- `GET /api/payments/{paymentId}` 查询支付资源（需 `payments:read`）

### 本地管理（仅演练用，`/local/**`）

- `POST /local/partners` 注册合作方
- `POST /local/consents` 构造同意（`ttlSeconds` 传负数可造过期同意）
- `POST /local/consents/{id}/revoke` 运行时撤销
- `POST /local/quotas/{partnerId}/limit?limit=n`、`POST /local/quotas/{partnerId}/reset`
- `GET  /local/quotas`、`GET /local/payments/count`

---

## 6. 本地验证方法

前置：JDK 21+、Maven（仓库已缓存 Spring Boot 3.4.1，支持离线 `-o`）。

### 6.1 运行测试（34 个用例）

```bash
mvn test                 # 全量
mvn test -Dtest=ConsentServiceTest         # 授权边界 + 并发撤销一致性
mvn test -Dtest=IdempotencyServiceTest     # 单次副作用/重放/冲突/并发/超期
mvn test -Dtest=VersionServiceTest         # 版本兼容与拒绝
mvn test -Dtest=PaymentIntegrationTest     # 全链路 HTTP（MockMvc）
```

测试覆盖：过期 / 已撤销 / 范围不足 / 不存在的同意拒绝且原因可区分；同键并发重复提交
只产生一次副作用；幂等内容冲突与超期复用被拒；v0/v9 版本拒绝；配额 429；下游异常 503
不透出内部细节；运行时撤销后新请求拒绝而历史重放仍确定。

> 单测与应用层日志都会打印**请求输入与判定依据**（例如 `授权拒绝 ... 判定依据=同意已过期
> now=... expiresAt=...`、`幂等判定 ... 判定依据=...`），便于解释每一次结论。

### 6.2 启动后用 curl 演练

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

---

## 7. 代码结构

```
common/        ErrorCode、ApiException、ErrorResponse、GlobalExceptionHandler（稳定错误语义）
partner/       Partner、PartnerRepository
consent/       PermissionScope、Consent(不可变快照)、ConsentRepository(CAS)、ConsentService(访问控制)
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
