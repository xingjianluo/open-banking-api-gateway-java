# 开放银行风格本地 API 集成与授权访问流程（Java / Spring Boot）

在本地用内存存储模拟合作方（TPP）、同意授权（Consent）、幂等状态与下游银行核心，
实现一套“可并发、可解释、版本可兼容”的开放银行风格支付 API。

- 同意授权与访问控制：权限范围 + 同意状态双重判定，四种拒绝原因互不相同；
- 幂等提交与重放防护：同键并发/重复只产生一次副作用，冲突与超期明确拒绝；
- 多版本兼容：新增字段缺省保持既有语义，废弃/未知版本明确拒绝，不静默降级；
- 统一错误语义：稳定错误码 + HTTP 状态码，内部异常细节不外泄。

技术栈：Java 21、Spring Boot 3.4、JUnit 5、Spring MVC `ConcurrentHashMap` 并发原语。

---

## 1. 同意与权限模型

### 1.1 角色与对象

| 对象 | 说明 |
| --- | --- |
| `Partner` | 合作方（第三方服务商 TPP），持有**被授予的权限集合** `grantedScopes` |
| `Consent` | 资源所有者对某合作方的**同意**，含 `scopes`、`status`、`expiresAt`，归属于唯一 `partnerId` |
| `ConsentScope` | 权限范围：`ACCOUNTS_READ`、`PAYMENTS_INITIATE`、`PAYMENTS_READ` |
| `ConsentStatus` | 同意状态：`AUTHORISED` / `EXPIRED` / `REVOKED` |

### 1.2 判定规则（`ConsentAuthorizationService.authorize`）

一次受保护资源访问按如下顺序判定，每个分支给出**唯一且稳定**的拒绝原因：

1. 合作方不存在 → `PARTNER_UNKNOWN`（401）
2. 同意不存在，或同意不属于该合作方 → `CONSENT_NOT_FOUND`（404）
   - 跨合作方访问也按“不存在”处理，**不泄露资源存在性**
3. 同意已过期（状态为 `EXPIRED` 或 `expiresAt <= now`）→ `CONSENT_EXPIRED`（403）
4. 同意已撤销（状态为 `REVOKED`）→ `CONSENT_REVOKED`（403）
5. 合作方被授予权限不含所需范围，或同意范围不含所需范围 → `SCOPE_INSUFFICIENT`（403）
6. 全部满足 → 放行

> “过期 / 已撤销 / 范围不足 / 资源不存在”是四个**互不相同**的错误码，调用方可据此区分。

### 1.3 并发一致性

- 判定所需状态在方法内**只读取一次并构成快照**：`status` 为 `volatile`，`scopes` / `expiresAt` 不可变；
- 状态撤销/置过期由另一个线程执行时，进行中的每一次访问要么按 `AUTHORISED` 完整放行，
  要么按终态完整拒绝，不会出现“中途可见”的混合结论；
- 幂等认领使用 `ConcurrentHashMap.compute` 原子完成（见下节），
  保证并发同键只有一个线程执行业务副作用。

预置合作方：

| partnerId | 被授予权限 |
| --- | --- |
| `partner-full` | `ACCOUNTS_READ` + `PAYMENTS_INITIATE` + `PAYMENTS_READ` |
| `partner-readonly` | 仅 `ACCOUNTS_READ`（用于范围不足场景） |

---

## 2. 幂等与重放防护

请求通过 `Idempotency-Key` 请求头声明幂等键，服务端对 `(键, 请求内容哈希)` 做判定
（`IdempotencyService`）：

| 场景 | 行为 | 错误码 / 标记 |
| --- | --- | --- |
| 首次提交 | 原子认领并执行一次动作（配额扣减 + 下游支付） | 响应 `replayed=false` |
| 同键 + **相同**请求哈希（重复或并发） | 不重复执行，等待/返回首次的**同一确定结果** | 响应 `replayed=true` |
| 同键 + **不同**请求哈希 | 明确拒绝，不静默覆盖、不执行 | `IDEMPOTENCY_CONFLICT`（409） |
| 幂等键超过保留期（默认 24h）后复用 | 明确拒绝，禁止在旧键上再执行 | `IDEMPOTENCY_EXPIRED`（410） |
| 相同键正在处理中又无法在等待窗口内完成 | 告知处理中 | `IDEMPOTENCY_IN_PROGRESS`（409） |
| 缺少幂等键 | 参数错误 | `VALIDATION_FAILED`（400） |

实现要点：

- `ConcurrentHashMap.compute` 是原子操作，配合“仅新建者为 owner”标记，
  保证并发同键**恰好一个线程**执行动作，其余线程在同一个 `CompletableFuture` 上等待；
- 业务动作在 `compute` 之外执行，避免在 Map 分段锁内做 I/O；
- **成功与失败结果都缓存**：首次失败后同键重放返回相同的稳定错误，不会重复触发副作用；
- 请求内容哈希（`RequestHasher`，SHA-256）对“新增可选字段缺省/空白”归一为空串、
  金额去除尾随零（`10.00` 与 `10.0` 等价），保证向后兼容时的哈希稳定；
- 配额扣减位于幂等动作**内部**，因此重放请求不会重复消耗配额。

---

## 3. 版本兼容规则

版本由 `X-Api-Version` 请求头声明（`VersionNegotiator`）：

| 输入 | 结论 |
| --- | --- |
| 缺省 / 空白 | 回退到当前版本 **V2**，保持既有语义 |
| `2` | V2，放行 |
| `1` | V1 已废弃、语义不兼容 → `VERSION_DEPRECATED`（426），**不静默降级** |
| 其他未知值 | `VERSION_UNSUPPORTED`（426），原因与废弃可区分 |

字段兼容：

- V2 相对旧版新增的可选字段（如 `debtorName`）缺省（`null`）或空白时，按既有语义处理，
  不影响老调用方；
- 请求体中携带当前版本无法识别的额外字段时直接忽略
  （`spring.jackson.deserialization.fail-on-unknown-properties=false`），为未来新增字段留兼容空间；
- 违反字段语义约束（如 `debtorName` 超过 V2 长度上限）→ `FIELD_INCOMPATIBLE`（400）；
- 参数缺失 / 金额非正等 → `VALIDATION_FAILED`（400）。

下游与配额异常的对外映射：

| 内部情况 | 对外错误 |
| --- | --- |
| 合作方固定窗口配额用尽（默认每 60s 5 次） | `QUOTA_EXCEEDED`（429） |
| 下游银行核心异常（演示：币种 `ERR`） | `DOWNSTREAM_FAILURE`（502），仅稳定语义，**不透出**下游堆栈/内部引用码 |
| 其他未预期异常 | `INTERNAL_ERROR`（500），堆栈仅写入服务端日志，并用 `traceId` 关联 |

---

## 4. HTTP 接口

### 4.1 支付（业务接口）

| 方法 & 路径 | 说明 |
| --- | --- |
| `POST /api/v2/payments` | 发起支付。请求头：`X-Partner-Id`、`Idempotency-Key`、`X-Api-Version` |
| `GET  /api/v2/payments/{paymentId}` | 查询支付，需 `PAYMENTS_READ` |

支付请求体（V2）：

```json
{
  "consentId": "CONS-abcd1234",
  "debtorAccount": "DEBT-1",
  "creditorAccount": "CRED-1",
  "amount": 12.50,
  "currency": "EUR",
  "debtorName": null
}
```

成功响应：

```json
{
  "paymentId": "PAY-0000000001",
  "consentId": "CONS-abcd1234",
  "status": "INITIATED",
  "creditorAccount": "CRED-1",
  "amount": "12.50",
  "currency": "EUR",
  "replayed": false,
  "createdAt": "2026-09-24T15:00:00+08:00"
}
```

错误响应统一形如：`{"code":"CONSENT_REVOKED","message":"同意已被合作方撤销",
"timestamp":"...","traceId":"..."}`。

### 4.2 本地管理接口（仅用于本地验证）

| 方法 & 路径 | 说明 |
| --- | --- |
| `POST /local/admin/consents` | 创建同意：`{partnerId, scopes:[...], status, ttlSeconds}` |
| `POST /local/admin/consents/{id}/revoke` | 将同意置为 `REVOKED` |
| `POST /local/admin/consents/{id}/expire` | 将同意置为 `EXPIRED` |

---

## 5. 本地验证方法

### 5.1 运行测试

```bash
mvn test
```

测试共 8 个类、39 个用例（见
`src/test/java/com/github/xingjianluo/openbankingapigatewayjava/tests`）：

| 测试类 | 覆盖内容 |
| --- | --- |
| `ConsentAuthorizationServiceTest` | 通过 + 不存在/过期/已撤销/范围不足/合作方未知/越权归属，**四种拒绝原因可区分** |
| `ConsentConcurrencyTest` | 16 读线程并发判定 + 1 线程持续翻转同意状态，所有结论一致且可解释 |
| `IdempotencyServiceTest` | 顺序重放、**24 线程并发同键只一次副作用**、内容冲突、超期复用、失败缓存、键隔离 |
| `RequestHasherTest` | 新字段缺省归一、金额尾随零、内容差异哈希 |
| `VersionNegotiatorTest` | 缺省 V2、V1 废弃拒绝、未知版本拒绝 |
| `QuotaServiceTest` | 窗口超限、合作方独立计数、窗口滚动恢复 |
| `PaymentServiceTest` | 编排全链路：成功/重放/冲突/版本/范围/资源不存在/下游映射/参数校验 |
| `PaymentApiIntegrationTest` | MockMvc 端到端：HTTP 状态码与错误码映射、重放标记、错误响应不含内部细节 |

单测日志中会打印**请求输入与判定依据**（搜索 `判定依据` / `输入` 关键字），例如：

```
授权判定开始: partnerId=p-full, consentId=c-revoked, requiredScope=PAYMENTS_INITIATE
授权判定依据: consentId=c-revoked 已被撤销 -> CONSENT_REVOKED
幂等判定: key=k-concurrent 首次出现，原子认领并准备执行动作 hash=...
判定结论: idempotencyKey=k-concurrent firstExecution=false replayed=true paymentId=...
```

### 5.2 手工 curl 验证

```bash
mvn spring-boot:run

# 1) 创建一个已授权同意
curl -s -X POST localhost:8080/local/admin/consents \
  -H 'Content-Type: application/json' \
  -d '{"partnerId":"partner-full","scopes":["PAYMENTS_INITIATE","PAYMENTS_READ"],"status":"AUTHORISED","ttlSeconds":3600}'

# 2) 首次发起支付（记下 paymentId）
curl -s -X POST localhost:8080/api/v2/payments \
  -H 'X-Partner-Id: partner-full' -H 'Idempotency-Key: demo-1' -H 'X-Api-Version: 2' \
  -H 'Content-Type: application/json' \
  -d '{"consentId":"<上一步consentId>","debtorAccount":"DEBT-1","creditorAccount":"CRED-1","amount":12.50,"currency":"EUR"}'

# 3) 同键重放：replayed=true，paymentId 相同
curl -s -X POST localhost:8080/api/v2/payments \
  -H 'X-Partner-Id: partner-full' -H 'Idempotency-Key: demo-1' -H 'X-Api-Version: 2' \
  -H 'Content-Type: application/json' \
  -d '{"consentId":"<consentId>","debtorAccount":"DEBT-1","creditorAccount":"CRED-1","amount":12.50,"currency":"EUR"}'

# 4) 同键改金额：409 IDEMPOTENCY_CONFLICT
curl -i -X POST localhost:8080/api/v2/payments \
  -H 'X-Partner-Id: partner-full' -H 'Idempotency-Key: demo-1' \
  -H 'Content-Type: application/json' \
  -d '{"consentId":"<consentId>","debtorAccount":"DEBT-1","creditorAccount":"CRED-1","amount":99,"currency":"EUR"}'

# 5) 撤销同意后再发起：403 CONSENT_REVOKED
curl -s -X POST localhost:8080/local/admin/consents/<consentId>/revoke
curl -i -X POST localhost:8080/api/v2/payments \
  -H 'X-Partner-Id: partner-full' -H 'Idempotency-Key: demo-2' \
  -H 'Content-Type: application/json' \
  -d '{"consentId":"<consentId>","debtorAccount":"DEBT-1","creditorAccount":"CRED-1","amount":1,"currency":"EUR"}'

# 6) 使用废弃版本：426 VERSION_DEPRECATED
curl -i -X POST localhost:8080/api/v2/payments \
  -H 'X-Partner-Id: partner-full' -H 'Idempotency-Key: demo-3' -H 'X-Api-Version: 1' \
  -H 'Content-Type: application/json' -d '{}'
```

### 5.3 可调参数（`src/main/resources/application.properties`）

| 配置 | 默认 | 含义 |
| --- | --- | --- |
| `obg.idempotency.ttl-hours` | 24 | 幂等键保留时长，超期复用被拒绝 |
| `obg.quota.limit` | 5 | 每合作方每窗口调用上限 |
| `obg.quota.window-seconds` | 60 | 配额固定窗口长度 |

---

## 6. 代码结构

```
model/        consent(Consent/ConsentScope/ConsentStatus)、partner、api(ApiVersion/请求响应)、payment
consent/      ConsentRepository + 内存实现、ConsentAuthorizationService（授权判定）
partner/      PartnerRepository + 内存实现
idempotency/  IdempotencyService + IdempotencyOutcome（并发幂等/重放/超期/冲突）
version/      VersionNegotiator（版本协商）、RequestHasher（内容哈希）
quota/        QuotaService（固定窗口配额）
downstream/   BankClient + DownstreamException（模拟下游银行副作用/故障）
payment/      PaymentRepository + 内存实现
service/      PaymentService（版本→鉴权→幂等[配额→下游] 编排）
web/          PaymentController、LocalAdminController、GlobalExceptionHandler
config/       DemoDataInitializer（预置合作方）
error/        ErrorCode、ApiException、ErrorResponse
```
