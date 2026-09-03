# 身份与分组规格（identity）

> 最近复核：364801f / 2026-09-03 · S1 成文（会话内对照 InvocationResolver / BatchWriteHandler /
> CliSupport / ParameterValueTracer / Schema 实现逐项对账）
> 验证三档占比：【测试钉】7 条 ·【命令可证】0 条 ·【人工对账】3 条

## 职责与边界

**管**：调用点身份的确定性派生（invocationKey 键文法与四锚点）、键的落库定格与读侧消费口径、
全库记录按身份分桶、身份的显示形与选择器解析、模板全文变更在键空间的 three-form 表现。

**不管**：基线治理状态与候选流转（governance）、指纹维度与判定（judgment）、任务链派生与对齐
（replay）、录制管道的采集与背压（recording）、CLI 命令面整体（cli）。

## 真源与派生

| 语义状态 | 真源 | 携带方式 | 派生链 |
|---|---|---|---|
| 声明标签 | 应用在捕获侧显式声明 | `InteractionRecord.invocationId` → `interactions.invocation_id` 列（NOT NULL，未声明以空串承载） | 无派生，原样进画像 `label` 列 |
| 模板全文哈希 | 模板全文文本 | 捕获侧缺省时 enrich 以 `sha256(templateText)` 回填投影 `template_hash` 列；显式设置优先 | `InvocationResolver.resolve` 消费；`prompt_texts` 原文库以同 hash 归档全文 |
| 骨架哈希 | 骨架文本（动态段替换为占位符后的模板形态） | 文本现算 `sha256(templateSkeleton)` 为唯一真源；`skeleton_hash` 投影列仅在文本不落库时（读侧）兜底 | 文本与投影并存时文本现算胜出 |
| invocationKey | 以上三个锚点字段 | `interactions.invocation_key` 列（NOT NULL，enrich 在落库前定格） | `InvocationResolver.resolve(record)` 纯函数派生；键一经落库定格，读侧不重算覆盖 |
| 调用点画像（invocations 行） | interactions 交互历史 | 派生数据 | 建档按桶种子生成，可从 interactions 全量重建 |

**就绪顺序（enrich 内钉死）**：哈希投影（templateHash/skeletonHash）先于键派生——键锚点消费
哈希，顺序颠倒会让首条带模板文本的记录以不完整信息落锚（键成 adhoc）。显式设置的值永不被
覆盖；单条 enrich 失败不拦截整批落库（原始交互数据是真源，派生字段缺失可事后重建）。

**双哈希各司其职**：全文哈希答「这条记录是用哪份完整文本组装的」（内容门控与重放取回凭据）；
骨架哈希答「这条记录属于哪个调用点」（身份定格）。骨架只定身份，从不参与内容门控与取回。

## 状态机与生命周期

本域无状态机。身份在 enrich 派生时定格、随记录落库后永不变更；治理侧的画像身份前移
（approve 重算/自动收编）是 governance 域对画像行的写行为，不回改任何记录的键。

## 契约

1. **键文法四锚点**（优先级从高到低，任一命中即停）：
   - 锚点 1 声明：`invocation:<标签>`，有模板时加 `:<细分哈希>` 细分（骨架哈希优先，退全文哈希）；
   - 锚点 2 骨架：未声明有骨架 → `skeleton:<骨架哈希>`；
   - 锚点 3 模板：未声明无骨架有模板哈希 → `template:<全文哈希>`；
   - 锚点 4 兜底：`adhoc:<sha256(modelRequestRaw)>` → `adhoc:<sha256(userInput)>` → `adhoc:no-anchor`。
   【测试钉】`InvocationResolverTest` 黄金键组（四锚点字面键值，含骨架细分、落库投影重算同键、
   no-anchor 兜底不含字面 null）
2. **键文法单射**：所有可控组件经百分号编码（`% : + [ ] ,` 转义）后拼装，四前缀命名空间互相
   隔离——组件内容永不伪造文法结构，用户命名零约束零碰撞。【测试钉】`InvocationResolverTest`
   Injectivity 组（冒号注入对抗、全文法字符转义、声明/模板命名空间隔离）
3. **形状不参与身份**：工具调用与纯对话同模板同键（TOOL/PURE_CHAT 分类与 paramSignature 是
   视图列）；有模板时用户输入不影响身份。【测试钉】`InvocationResolverTest` IdentitySemantics 组
4. **骨架真源唯一**：文本现算优先于投影列（即使投影被错误手工设置）；同骨架异全文同键。
   【测试钉】`InvocationResolverTest`（skeletonText_beats_projection、sameSkeletonDifferentFullText）
5. **落库就绪顺序**：enrich 内哈希投影先于键派生（带模板文本的记录键必为 template:/skeleton:
   锚，绝不落 adhoc）；显式值不覆盖；单条失败不炸批。【测试钉】`BatchWriteHandlerTest`
   （flush_derivesTemplateHashFromText 反向钉住顺序、flush_keepsExplicitTemplateHash、
   flush_enrichFailureRecordStillSaved）
6. **显示形与选择器解析**：status 展示短形（`标签@细分8位`、`skl@…`/`tpl@…`、`adhoc@…`）可直接
   粘贴为 `--invocation` 值；目标解析顺序 = 完整键精确 > 业务标签（唯一）> 显示短形 > 唯一前缀，
   多命中报错列候选；哈希段大小写不敏感；非 8 位十六进制后缀不视为显示短形。
   【测试钉】`CliSupportResolverTest`、`CliSupportKeyDisplayTest`
7. **invocationKey 永不进指纹**：指纹维度保持输出侧，输入侧（键、变量、历史）不参与判定——
   adhoc 以输入派生键因此合法（键是溯源身份不是判定输入）。【人工对账】提取器实现只读取
   工具调用与模型响应（`FingerprintExtractor` 全部维度来源），`FingerprintExtractorTest`
   对四维输出做穷举断言（物料记录不带身份字段）——尚无「仅身份字段不同 → 指纹相等」的
   直接对偶用例，为可收缩项
8. **全库分桶口径**：按完整 invocationKey 分桶，四命名空间同权（未声明不失去框架服务资格）；
   桶内规范序 timestamp → seq → recordId，建档种子取桶内规范序最早记录（基线描述最早观察到的
   行为）。【测试钉】`BaselineServiceTest`（seedIsCanonicalEarliest_regardlessOfInsertionOrder——
   种子与录制插入顺序无关）；桶的字典序属内部消费序（建档枚举顺序），无用户可见消费面
   【人工对账】（`CliSupport.invocationBuckets` 的 TreeMap 与规范序比较器）
9. **三分形态**：模板全文变更在键空间的表现按键锚点分三种——声明+骨架/未声明+骨架为**同键
   新全文哈希变体**（Rule A）；声明无骨架为**裂出新细分键、旧画像仍在、标签相同**（Rule B）；
   template:/adhoc: 为**全新键、结构上与旧键无关联**（Rule C）。【人工对账】键值机制由契约 1
   黄金键覆盖，三分表现语义待漂移检测器（统一重放引擎批）测试成钉
10. **身份纪元纪律**：键派生规则与字面键值一经发布即冻结，任何变更等价于身份纪元事件
    （历史基线全部失配），必须走显式设计；开发期承接 = 删库重建。【人工对账】流程纪律

## 行为矩阵

| 输入（锚点字段状态） | 键形态 | 模板全文变更后 |
|---|---|---|
| 声明标签 + 骨架 | `invocation:标签:骨架哈希` | 同键，记录携带新全文哈希变体（Rule A） |
| 声明标签 + 全文哈希（无骨架） | `invocation:标签:全文哈希` | 裂出新细分键，标签不变（Rule B） |
| 声明标签、无模板无骨架 | `invocation:标签` | 键恒定 |
| 未声明 + 骨架 | `skeleton:骨架哈希` | 同键（Rule A） |
| 未声明 + 全文哈希 | `template:全文哈希` | 全新键，与旧键无关联（Rule C） |
| 未声明 + modelRequestRaw | `adhoc:sha256(原文)` | 全新键（Rule C）；当前框架适配层无采集侧填充方，实际仅程序化构造触达 |
| 未声明 + userInput | `adhoc:sha256(输入)` | 全新键（Rule C）；现网 SDK 录制的 adhoc 主形态 |
| 全部缺失 | `adhoc:no-anchor` | 恒定（程序化构造防御桶，不含字面 null） |
| 骨架文本与陈旧投影并存 | 按**文本现算**定格 | 投影错误不影响身份 |
| enrich 派生失败（畸形记录） | 键列空串落库（NOT NULL 兜底） | 单条不阻断整批 |
| 读侧记录键列缺失 | 存储值优先、解析器现算兜底；现算失败返回 null | 跳过分桶/建边，不阻断全库处理 |
| 显示短形前 8 位撞车 | 报错并列出完整键，要求提供完整 invocationKey | — |

## 域间边界

- **上游 recording**：SDK mapper 填声明标签/模板全文/骨架文本；enrich 补哈希投影与键。
- **下游 governance**：invocations 画像以 invocationKey 为主键，建档分桶消费本域分桶口径。
- **下游 judgment**：指纹不消费键（契约 7）。
- **下游 replay**：对齐分组键 = 声明标签（跨模板版本配对）/ 完整键（无标签不跨版本）两式，
  均由本域键文法保证可解析与单射；验收包装载侧从键首段解析回声明标签。
- **下游 graph**：依赖图节点 = invocationKey 单一身份空间；存储值优先口径在 core 与 cli 各有
  一处同语义实现，两侧不得分叉。

## 变更纪律

- 键文法、四锚点优先级、字面键值 = 冻结契约；黄金键测试的期望值不得为实现适配而修改
  （测试红线）。变更 = 身份纪元事件，走显式设计并删库重建（开发期）；发布后为单向门变更。
- 分桶口径、选择器解析、显示形格式 = 演进面，但变更须同步 cli 域 spec 与本文件。
- `--invocation` 的四写法等价性是命令面契约，属 cli 域；本域只保证显示形可反解。

## 复核台账

| 日期 | 方式 | 发现 |
|---|---|---|
| 2026-09-03 | S1 成文：InvocationResolver/BatchWriteHandler/CliSupport/ParameterValueTracer/Schema 全量对账 + 测试指针核实 | ①`BatchWriteHandler` 类注释仍引用 `group_key` 列名（B1' 全库改名残留，实列为 `invocation_key`）——待随代码批修复；②导读第 5 章「invocations 16 列」与 Schema 实际 15 列不符（第 4 章正确）——导读对账批修正；③modelRequestRaw 无采集侧填充方属实（与导读第 4 章「预留」一致，非漂移），已写入行为矩阵 |
| 2026-09-03 | C1 交付后全量代码审查（同会话第二轮，对抗性自证） | ①契约 8 原【命令可证】指针失实——status 画像行按 `findAllInvocations` 原始行序输出而非分桶字典序，已改写为建档种子【测试钉】（随批新增 `BaselineServiceTest` 种子序回归钉）+ 内部字典序【人工对账】；②C1 已落地治理侧身份前移（approve 前移/rollback 恢复/显式收编 + DriftDetector），归属 governance 域，待 S4 成文承接；③①②之外的漂移发现随审查批修复（group_key 注释/导读列数/测试旧词） |
