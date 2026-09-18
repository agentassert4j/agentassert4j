# 存储规格（storage）

> 最近复核：364801f / 2026-09-03 · S2 成文（会话内对照 Schema / SchemaMigrator /
> SqliteStorageRepository / JsonMapper / SPI 六接口实现逐项对账）
> 验证三档占比：【测试钉】11 条 ·【命令可证】0 条 ·【人工对账】2 条

## 职责与边界

**管**：SQLite 单文件持久化的全部契约——五表 schema 与三层列结构、事务与并发纪律、写读往返
保真、模板原文归档、基线归档、治理事件时间线、schema 契约版本纪律、SPI 六域接口面。

**不管**：画像字段的治理语义（谁在什么条件下改 fingerprint/候选/状态——governance）；
invocationKey 的派生文法（identity）；查询结果的业务消费（各消费域）。

## 真源与派生

| 数据 | 性质 | 重建性 |
|---|---|---|
| `interactions`（38 列） | 只追加的原始真源账本，record_id 主键 | 不可重建（录制即历史） |
| `prompt_texts`（3 列） | 模板原文唯一反查点（hash 不可逆，原文不落即永久丢失） | 不可重建 |
| `invocations`（16 列） | 治理档案 = 派生 + 治理写混合体 | 身份/视图列可从 interactions 重建；治理列（指纹/候选/审批/代码锚）以治理写为准 |
| `invocation_template_versions`（10 列） | 只追加归档历史（rollback 数据源） | 不可重建 |
| `governance_events`（8 列） | 只追加治理动作时间线（六动词；reject/rollback 等无状态痕迹动作的唯一审计载体） | 不可重建（发生时落账，无派生路径） |

**三层列结构**（interactions）：概念层（跨协议稳定的概念数据）/ 原文层（`*_raw` 逐字保留，
后续新增概念列的回填来源）/ 吸收层（`metadata` JSON 承接未预见扩展）。

**写侧变形契约**：`invocation_id`/`invocation_key` 的 null → 空串落库（换取 NOT NULL 列约束）；
落库后无键与空键不可区分。指纹列（invocations 与 invocation_template_versions 两表）载荷 =
**认可形态集合的有序数组 JSON**（首元素 = establish 种子锚，accept 追加于尾）：null/空集合 ↔
`"[]"` 对写读对称（NOT NULL 约束，读侧 `"[]"` 映射回 null = 无基线）；形态集合语义之前的
单对象旧行读侧响亮拒绝并指路删库重建（预发布承接，无兼容读取）。candidate_fingerprint 列
仍为单指纹 `"{}"` 形态。归档行指纹 = 该版本获批时的**全量集合快照**（rollback 整集恢复）。

## 状态机与生命周期

本域无状态机（基线三态流转是 governance 域语义，存储只承载其落库形态）。库生命周期：

```
initialize(): 建父目录 → JDBC 连接 → setAutoCommit(true) → SchemaMigrator.migrate()
  migrate 三段式：库版本 > 支持值 → 拒开（旧代码不得静默误读新语义）
                = 支持值 → 直接返回；< 支持值 → 执行全部 DDL 并盖戳 user_version
读写: 全程单连接，全部公开方法 synchronized（串行化是正确性前提，本地写无并发收益）
close(): 关连接置 null；与写路径共用实例监视器——flush 进行中不得关闭连接
```

## 契约

1. **契约版本 = 1**（`PRAGMA user_version`）：同版跳过、低版建表盖戳幂等、高版拒开抛
   `SQLException`。【测试钉】`SqliteStorageRepositoryTest`（schemaVersionStamped /
   reinitializeIsIdempotent / futureSchemaVersionRejected）
2. **批写整批原子**：`saveInteractions` 关 autocommit → 逐条 → commit；任何异常**先显式
   rollback 再恢复 autocommit**（对 sqlite-jdbc 未决事务执行 setAutoCommit(true) 是隐式提交，
   顺序颠倒会把半批脏数据落盘）。【测试钉】saveInteractions_runtimeExceptionMidBatch_rollsBackWholeBatch
3. **并发安全**：多 flush 源并发进入全量落库（串行化正确性）。【测试钉】
   saveInteractions_concurrentBatches_allRowsPersisted
4. **只追加幂等**：record_id 冲突（崩溃重放双写）`INSERT OR IGNORE` 静默跳过；单条写入面
   `saveInteractionIfAbsent` 以返回值回告 saved/duplicate，摄取方据此如实报告。【测试钉】
   duplicateRecordIdIgnored
5. **模板原文随行归档**：交互写入路径逐条把记录携带的模板原文按 templateHash 写入
   `prompt_texts`（`INSERT OR IGNORE` 首写为准）；原文写失败只降级不拖累交互主数据。
   写入面为实现私有，SPI 只暴露 `findTemplateText` 读取。
   【测试钉】saveInteraction_carriesTemplateTextIntoPromptTexts / savePromptText_sameHash_firstWriteWins
6. **确定性读序**：全部交互查询 `ORDER BY timestamp, seq, record_id`（平局可决胜）。
   【测试钉】saveAndFindInteraction / findBySessionId 等查询组的顺序断言
7. **敌对内容逐字保真**：特殊字符/NUL/控制符/深嵌套 JSON 在文本列与 JSON 列写读往返逐字一致。
   【测试钉】specialCharacters_roundTripUnescaped / jsonColumns_roundTripHostileContent /
   fingerprintColumns_roundTripHostileContent
   （形态集合整集往返与数组载荷见 fingerprintColumn_legacySingleShapeRow_failsLoudly、
   saveAndFindProfile 的集合断言）
8. **列 ↔ 模型 setter 契约**：interactions 捕获保真列、invocations 治理列（含 code_ref）写读往返逐字段对齐；
   指纹 null ↔ `"{}"` 对称。【测试钉】captureFidelityColumnsRoundTrip /
   skillProfileGovernanceColumnsRoundTrip / fingerprintColumn_nullRoundTripsAsNull
9. **归档 tiebreaker**：同调用点同版本标签重复归档时「最近归档者胜」（`archived_at DESC,
   rowid DESC LIMIT 1`，自增 id 决胜）；归档列表按最新在前。【测试钉】
   findArchivedTemplateVersion_duplicateTag_latestArchiveWins / findArchivedVersions_listsByInvocationLatestFirst
10. **失败语义显式**：存储故障抛专用 `StorageException` 不吞不换型；初始化失败清理已开连接。
    【测试钉】storageFailure_throwsStorageException_neverSwallowed
    【测试钉】saveAndLoadGraph / loadGraph_empty
11. **治理事件表**：`governance_events` 只追加（六动词封闭词表 verb，wire 值 kebab-case）；
    `happened_at` 由存储实现方写入时刻盖章（调用方不携带时钟）；读取恒 `happened_at, rowid`
    升序（同刻按写入序决胜）；verb 未知线上值按 null 退化不中断读取（宁缺勿错注记）。
    SPI 写入点唯一 = BaselineManager 六个治理写（幂等早退与前置失败不落事件）。
    【测试钉】governanceEvents_roundTripHostileContent_orderedAscending
12. **SPI 六域面**：写（2 方法）/ 查（6 方法）/ 调用点（3）/ 模板原文（2）/ 归档（3）/ 治理事件（2），
    `StorageRepository` 聚合门面加 type/initialize/close。查询域现有 6 方法超出「每接口 ≤5」
    的接口隔离目标——既有阶段债，随命令面瘦身批删除 `findInvocationKeysByTemplateHash` 后
    回到 5。【人工对账】债务跟踪
13. **零迁移代码**：预发布阶段 schema 变更 = 删库重建，不存在旧版迁移路径；发布后演进只允许
    「新增可空列 + 从 raw 回填」。【人工对账】版本纪律

## 行为矩阵

| 场景 | 行为 |
|---|---|
| 库版本高于代码支持值 | initialize 抛 StorageException（拒开，不静默误读） |
| 批写中单条失败 | 整批回滚、异常上抛、autocommit 恢复，无半批提交 |
| 批写中连接级故障 | 同上（RuntimeException 路径同样先 rollback） |
| 同 record_id 重复写 | 静默跳过（只追加幂等） |
| 同 hash 模板文本重复写 | 首写为准（不覆盖、created_at 不漂移） |
| 原文归档失败 | 计数日志降级，交互主数据照常落库 |
| 画像写入时 type/status 为 null | 落库默认 `TOOL` / `BASELINE` |
| 查询无命中 | 空列表 / null（findInvocationByKey/findTemplateText/findArchivedVersion），不抛异常 |
| flush 进行中 close | 实例监视器串行化，不产生 null 连接竞态 |
| NUL/控制符/深嵌套内容 | 逐字往返保真 |

## 域间边界

- **上游 recording**：只依赖 `InteractionWriteStore` 最小知识面；批量写是唯一事务入口。
- **上游 CLI**：判定面经 `StorageRepository` 全域门面读写；画像直写仅限簿记例外。
- **下游 identity**：`invocation_key`/`invocation_id`/`skeleton_hash`/`template_hash` 列是
  身份落库点；读侧映射把投影列还原进记录。
- **下游 judgment / replay**：指纹列、任务链查询（按 session/键/标签）由本域供数，读序确定性
  是对齐规范序的前提。

## 变更纪律

- 五表列集与 `user_version` 语义 = 冻结契约：发布前变更 = 删库重建（含 governance_events——开发期旧库缺表会被打开守卫拒开，删库或换新路径承接）；发布后新增列走
  「可空 + raw 回填」，禁止破坏性变更（单向门）。
- `*_raw` 列逐字保留承诺：任何概念列新增必须能从 raw 回填，raw 不得改写。
- 存储实现可替换（R3 插件平等）：契约 = SPI 六接口，不是 SQLite 实现；实现侧新行为先补本
  spec 再补码。

## 复核台账

| 日期 | 方式 | 发现 |
|---|---|
| 2026-09-16 | D2 结构批随批 | 指纹列载荷契约换为形态集合数组（DDL 零改动）；null↔"[]" 对称；legacy 单对象行响亮拒绝；归档行=整集快照（测试钉 fingerprintColumn_legacySingleShapeRow_failsLoudly + 往返钉改集合断言） |-14 | A3 修复批（批 3）：governance_events 新表 + GovernanceEventStore 域（2 方法） | ①真源表增行（不可重建——发生时落账）；契约 11 补位成文（六动词/实现方盖章/升序读/未知 verb 退化）；契约 12 五域面→六域面；②「五表」计数自 S2 成文起即失真（实为 4 表，graph 表已随图降级摘除）——本批加表后恰为 5，旧失真一并回填；③开发期旧库（channel2/dogfood）user_version=1 且缺新表，打开守卫直接拒开并给删库指引（非静默降级——方案文档原「事件写入恒走 L1 降级」表述据此修正，L1 降级仅作为 BaselineManager 写入侧防御保留） |
|---|---|---|
| 2026-09-11 | 批B SPI 死面修剪（维护者「零兼容残留」指令）： SPI 删 `saveInteraction`/`type()`/`findByTemplateHash`/`saveTemplateText`/`isAvailable`，`saveInteractionIfAbsent` 升入 InteractionWriteStore； ②`idx_template_hash` 索引随唯一查询方消亡（删库重建承接）； ③McpRecordIngestion/CliSupport.openRepository 回归 StorageRepository 接口类型 | 契约 2 措辞更新（写入面私有化）；SPI 六接口瘦身为五域面；无行为变更，往返测试全绿为钉 |
| 2026-09-12 | 批3 N6 首跑 + 义务登记 | ①LOW：`FingerprintJson.asStringMap`（core util，private）与 `JsonMapper.asStringMap`（storage，private）同一 Map→Map<String,String> 小工具双份——storage 依赖 core 可单点化（FingerprintJson 出包级公共或挪 TextUtil），列 1.0.x；②契约张力登记：三 raw 列的「未来一切新概念列的回填来源」承诺对 **SDK 采集记录不成立**（ChatModel 抽象层拿不到线上原文，raw 恒 null，两 mapper 注记在案）——raw 回填仅覆盖 CLI 重驱记录与 MCP 摄取记录（wire 原文全量），SDK 面新增概念列的回填来源需届时单独设计 |
| 2026-09-03 | S2 成文：Schema/SchemaMigrator/SqliteStorageRepository/JsonMapper/SPI 六接口全量对账 + 测试指针核实 | ①导读「测试怎么钉住它」称「38 列与占位符逐一核对」有测试——实为 SQL 字面拼接逐列对齐（无独立列数断言），措辞过强，导读对账批顺修；②查询域 6 方法超接口隔离目标为既有阶段债（本 spec 契约 12 显式跟踪，随瘦身批回到 5）；③测试方法名 `skillProfileGovernanceColumnsRoundTrip` 保留 pre-B1' 的 skill 旧词（纯命名残留，语义正确）——随任一代码批顺修可选 |
| 2026-09-17 | TODO 终裁批 | 三 raw 列契约张力行维持原判；SDK mapper 的 raw-null TODO×2 转正为契约注释（N3 前提永真改判）——raw 回填覆盖面（CLI 重驱+MCP 摄取）为确定契约，SDK 面未来概念列回填来源仍属届时设计 |
| 2026-09-17 | 1.0.0 收尾批：asStringMap 单点化 | 台账在册的双份小工具收编：`RecursiveJsonParser.asStringMap` 升公共静态（JSON 对象→Map&lt;String,String&gt; 强转，值归一/null 保留/非 Map 返 null），FingerprintJson 与 JsonMapper 两处私有拷贝删除、改道调用；新公共助手直钉两条（强转语义/非 Map 输入）；拷贝扫描器后续巡检该对不再报 |
| 2026-09-17 | 延迟池终裁（维护者裁决）：raw 回填 SDK 面销账 | 前提经字节码复核钉死为**Spring AI 框架既有限制**：ChatModel 接口 1.0.0 与 2.0.0 两代签名均为 `call(Prompt)→ChatResponse`，只交付结构化对象，线上报文在 provider HTTP 客户端内部、本层不可达——非本框架待办，不挂账；既有限制在 OPERATIONS（录制来源原文覆盖）与 recording.md 契约 8 标注。若未来真出现「需从原文回填的新概念列」，SDK 面届时走 provider HTTP 层拦截的新适配模块，与本契约行无关 |
