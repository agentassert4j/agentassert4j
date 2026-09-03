# 存储规格（storage）

> 最近复核：364801f / 2026-09-03 · S2 成文（会话内对照 Schema / SchemaMigrator /
> SqliteStorageRepository / JsonMapper / SPI 六接口实现逐项对账）
> 验证三档占比：【测试钉】11 条 ·【命令可证】0 条 ·【人工对账】2 条

## 职责与边界

**管**：SQLite 单文件持久化的全部契约——五表 schema 与三层列结构、事务与并发纪律、写读往返
保真、模板原文归档、基线归档、图快照、schema 契约版本纪律、SPI 六域接口面。

**不管**：画像字段的治理语义（谁在什么条件下改 fingerprint/候选/状态——governance）；
invocationKey 的派生文法（identity）；查询结果的业务消费（各消费域）。

## 真源与派生

| 数据 | 性质 | 重建性 |
|---|---|---|
| `interactions`（38 列） | 只追加的原始真源账本，record_id 主键 | 不可重建（录制即历史） |
| `prompt_texts`（3 列） | 模板原文唯一反查点（hash 不可逆，原文不落即永久丢失） | 不可重建 |
| `invocations`（15 列） | 治理档案 = 派生 + 治理写混合体 | 身份/视图列可从 interactions 重建；治理列（指纹/候选/审批）以治理写为准 |
| `invocation_template_versions`（9 列） | 只追加归档历史（rollback 数据源） | 不可重建 |
| `graph_snapshot`（1 行 JSON） | 派生缓存 | 可随时从 interactions 全量重建 |

**三层列结构**（interactions）：概念层（跨协议稳定的概念数据）/ 原文层（`*_raw` 逐字保留，
后续新增概念列的回填来源）/ 吸收层（`metadata` JSON 承接未预见扩展）。

**写侧变形契约**：`invocation_id`/`invocation_key` 的 null → 空串落库（换取 NOT NULL 列约束）；
落库后无键与空键不可区分。指纹列 null ↔ `"{}"` 对写读对称（NOT NULL 约束）。

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
4. **只追加幂等**：record_id 冲突（崩溃重放双写）`INSERT OR IGNORE` 静默跳过。【测试钉】
   duplicateRecordIdIgnored
5. **模板原文随行归档**：`saveInteraction` 逐条把记录携带的模板原文按 templateHash 写入
   `prompt_texts`（`INSERT OR IGNORE` 首写为准）；原文写失败只降级不拖累交互主数据。
   【测试钉】saveInteraction_carriesTemplateTextIntoPromptTexts / savePromptText_sameHash_firstWriteWins
6. **确定性读序**：全部交互查询 `ORDER BY timestamp, seq, record_id`（平局可决胜）。
   【测试钉】saveAndFindInteraction / findBySessionId 等查询组的顺序断言
7. **敌对内容逐字保真**：特殊字符/NUL/控制符/深嵌套 JSON 在文本列与 JSON 列写读往返逐字一致。
   【测试钉】specialCharacters_roundTripUnescaped / jsonColumns_roundTripHostileContent /
   fingerprintColumns_roundTripHostileContent
8. **列 ↔ 模型 setter 契约**：interactions 捕获保真列、invocations 治理列写读往返逐字段对齐；
   指纹 null ↔ `"{}"` 对称。【测试钉】captureFidelityColumnsRoundTrip /
   skillProfileGovernanceColumnsRoundTrip / fingerprintColumn_nullRoundTripsAsNull
9. **归档 tiebreaker**：同调用点同版本标签重复归档时「最近归档者胜」（`archived_at DESC,
   rowid DESC LIMIT 1`，自增 id 决胜）；归档列表按最新在前。【测试钉】
   findArchivedTemplateVersion_duplicateTag_latestArchiveWins / findArchivedVersions_listsByInvocationLatestFirst
10. **失败语义显式**：存储故障抛专用 `StorageException` 不吞不换型；初始化失败清理已开连接。
    【测试钉】storageFailure_throwsStorageException_neverSwallowed
11. **图快照单行**：`id='current'` 整图 JSON `INSERT OR REPLACE`，无快照返回 null。
    【测试钉】saveAndLoadGraph / loadGraph_empty
12. **SPI 六域面**：写（2 方法）/ 查（6 方法）/ 调用点（3）/ 模板原文（2）/ 图（2）/ 归档（3），
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

- 五表列集与 `user_version` 语义 = 冻结契约：发布前变更 = 删库重建；发布后新增列走
  「可空 + raw 回填」，禁止破坏性变更（单向门）。
- `*_raw` 列逐字保留承诺：任何概念列新增必须能从 raw 回填，raw 不得改写。
- 存储实现可替换（R3 插件平等）：契约 = SPI 六接口，不是 SQLite 实现；实现侧新行为先补本
  spec 再补码。

## 复核台账

| 日期 | 方式 | 发现 |
|---|---|---|
| 2026-09-03 | S2 成文：Schema/SchemaMigrator/SqliteStorageRepository/JsonMapper/SPI 六接口全量对账 + 测试指针核实 | ①导读「测试怎么钉住它」称「38 列与占位符逐一核对」有测试——实为 SQL 字面拼接逐列对齐（无独立列数断言），措辞过强，导读对账批顺修；②查询域 6 方法超接口隔离目标为既有阶段债（本 spec 契约 12 显式跟踪，随瘦身批回到 5）；③测试方法名 `skillProfileGovernanceColumnsRoundTrip` 保留 pre-B1' 的 skill 旧词（纯命名残留，语义正确）——随任一代码批顺修可选 |
