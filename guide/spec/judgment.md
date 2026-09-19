# 判定与指纹规格（judgment）

> 最近复核：364801f / 2026-09-03 · S3 成文（会话内对照 FingerprintExtractor /
> DeterministicComparator / BehaviorChecker / JudgmentSemantics / BaselineManager 实现逐项对账）
> 验证三档占比：【测试钉】10 条 ·【命令可证】0 条 ·【人工对账】2 条

## 职责与边界

**管**：行为指纹的四维定义与现场提取、确定性逐维对比与二值判定、维度 3/4 的声明式规则
（rules）注入、内置行为校验器、判定语义版本戳。

**不管**：何时触发对比与候选登记时机（governance/replay）、任务链对齐编排与配对规则
（replay）、invocationKey 派生（identity）、基线三态流转（governance）。

## 真源与派生

| 语义状态 | 真源 | 派生链 |
|---|---|---|---|
| 指纹 | 交互记录本体（工具调用集/参数类型/模型响应文本/工具成败位）+ rules 配置（维度 3/4 声明） | `FingerprintExtractor.extract` 现场重提；**候选侧（当前证据）永远现场重提；基线侧 = 批准真相（认可形态集合）的定格投影，按路径三源取用（见契约 11）** |
| hasError | ToolCall.success 位（任一失败即 true） | 提取器内置维度 4 |
| 判定结论 | 基线指纹 × 当前指纹 × 当前输出文本 | `DeterministicComparator.compare` 逐维比对 → `ComparisonResult`（含逐维差异清单与二值 verdict） |
| 判定语义版本 | `JudgmentSemantics.VERSION`（det-v1） | 建立/批准/重建时盖章进画像 `algo_version` 列并随归档行留痕 |
| 规则查找键 | 记录的声明标签（invocationId） | null 视同空键——未声明调用点可用空键统一注入断言 |

## 状态机与生命周期

本域无状态机。基线三态（BASELINE/CANDIDATE/ARCHIVED）归 governance 域；本域精确边界：
**活跃画像行只会是 BASELINE 或 CANDIDATE**——`ARCHIVED` 枚举值从不写入活跃行，已归档基线
是 `invocation_template_versions` 表的事实而非活跃行状态。verdict 二值（PASS/CHANGED），
无第三态；严重程度由裁决人读逐维差异清单自行得出，程序不评判方向。

## 契约

1. **指纹四维**：维度 1 工具调用（工具名集合，忽略顺序 + 参数类型合并映射，键值双归一
   toLowerCase）；维度 2 输出结构（JSON → contentType=application/json + 字段路径集 + 字段
   类型映射，长度量级置 0；非 JSON/空白 → text/plain + 长度数量级 log10+1）；维度 3 内容规则
   （必需/禁用关键词 + 正则，来自 rules）；维度 4 约束行为（声明 behaviors + hasError）。
   无参提取维度 3/4 为空集。【测试钉】`FingerprintExtractorTest`（dim1-4 全景、嵌套路径、
   空响应三态、hasError 三态）
2. **规则注入口径**：三参 extract 按声明标签查规则并覆盖维度 3/4；rules 为 null → 空；
   标签 null → 空键查找。【测试钉】`FingerprintExtractorTest`（extractWithRules_overridesDim3And4 /
   extractWithRules_nullConfig_defaultToEmpty / extractWithRules_nullInvocationId_fallsBackToEmptyKey）
3. **二值判定**：任一维存在可行动差异即 CHANGED，否则 PASS。【测试钉】
   `DeterministicComparatorTest`（identicalFingerprints_passVerdict、各维 changed 组、
   verdictEnum_isBinary）
4. **维度 2 口径**：字段集增删同罚（纯新增也 CHANGED）；字段类型核对自基线键单向发起；
   contentType 相等；长度数量级子维仅在两侧均 text/plain 时比对。【测试钉】
   changedVerdict_addedFieldsOnly / changed_fieldsRemoved / textPlain_sameMagnitude_d2isOne /
   textPlain_magnitudeOffByOne_changed / contentTypeMismatch_changed
5. **ignorableFields 只作用于维度 2 字段路径**：归一化覆盖一切字段（含 error 类叶子名与嵌套
   路径）——用户显式声明的口径优先于内建敏感度；维度 1 工具集与参数类型不受其豁免。
   【测试钉】ignorableFields_removedFieldsNotCounted / addedErrorField_ignorableConfig_honored /
   addedNestedErrorField_ignorableConfig_honored
6. **维度 3/4 = 基线声明、当前答卷**：无声明该维不构成差异；声明后按当前输出文本校验
   （关键词 contains、禁用 noneMatch、正则全匹配）。正则按声明实例编译一次复用；不设匹配
   超时（墙钟超时会引入平台相关的非确定性），回溯复杂度由声明者自负。
   【测试钉】dimension3_*/dimension4_* 组
7. **加权评分仅展示**：权重随声明维动态重分配，判定分支不消费 score。【测试钉】
   dynamicWeight_* / passVerdict_scoreExactly095 / changed_lowScore
8. **行为校验器**：八个内置 behavior（mustUseChinese/mustUseEnglish/returnsEmptyOnError/
   returnsErrorCode/noError/jsonOutput/nonEmptyOutput/containsCjk），语言类用码点扫描不用正则；
   未知名默认通过（不误报），CLI 加载时点破并列出合法名。【测试钉】`BehaviorCheckerTest` +
   `CliSupportResolverTest`（unknownBehavior_warnedAtLoad）
9. **判定语义版本守卫**：建立/批准/重建三条成为基线的路径统一盖章；重放入口校验基线
   `algo_version` 与引擎一致，不一致（含未标记历史行）拒绝判定——算法升级不得静默重解释
   已批准基线。【测试钉】`BaselineManagerTest`（accept 后活跃行与归档行的 algoVersion 断言）+
   `ReplayFlowTest`（staleAlgoVersion_refusesJudgment / unstampedAlgoVersion_refusesJudgment /
   报告头钉 judgmentSemantics）
10. **空值兜底**：程序化构造的缺省集合字段、null 输出文本、双空指纹均安全比对不 NPE。
    【测试钉】nullOutput_treatedAsEmpty / bothEmptyFingerprints_pass / nullConfig_usesDefaults
11. **判定基线真源模型（现场重提原则收窄为候选侧）**：候选侧（当前证据）永远现场重提——
    任何路径不得消费存档值充当候选侧；**候选侧的判定域按路径分形**：链路径与成员判定 =
    新链全部记录（每条记录都是证据）；CI 对照 = 新链逐调用点的**链末执行**（每调用点组内
    最新记录，更早的同会话记录是迭代草稿，经透明层可见不进判定——任务纪律与前缀标记仍看
    全链）。基线侧 = 批准真相的**定格投影**，按路径三源取用：
    链路径 = 上一条真实链的记录（两侧同为记录，对称现场重提——行为对行为的差分）；验收包 =
    画像认可形态集合定格（与 CI 同源——导出时逐调用点取组末记录为证据锚，步骤指纹消费画像
    批准真相；出厂偏离检测在组末提取与批准指纹的**结构维**不一致（维度 1/2 + hasError——判定尺
    不消费候选侧声明集，偏离检测不得比门禁更严：仅声明集漂移时门禁判 PASS，不计偏离）
    或在途候选时计 unadjudicatedSteps 并警告，自违检查只在组末与画像一致时执行）；CI 对照 = 画像认可形态集合（establish/accept 时刻的
    定格，经 BaselineSides.fromProfiles 投影）——三源中包/CI 两源合一为**批准真相
    投影**（承诺 = 批准形态集合，证据 = 链末记录锚；B2 批实施）。
    跨口径可比性由语义版本守卫强制、不可比即拒判：本地画像 = algoVersion 守卫（CI 路径同样
    经过）、验收包 = judgmentSemantics 守卫，绝不静默跨口径对比。口径对称性另有两道结构保证：
    维度 3/4 是「基线声明、当前答卷」（候选侧声明集不进判定，rules 配置漂移不可能制造假
    CHANGED）；ignorableFields 是比较期口径（非提取期烙印），三源同享。注意「任何对比一律
    现场重提」作为全称命题自 verify 存在起已被包路径突破（包基线侧本就不是现场值）——本
    模型是把既有实践升格为明示规范，CI 画像指纹是同一模式在本地治理库的实例化。
    （B1 批登记：链末判定入口 = TaskAligner.alignLatestPerInvocation，core 单源、
    verify 同尺复用；判定对象不随 accept 翻转 = A2「同证据复检绿」承诺的机械保证。）
    【测试钉】`BaselineSidesTest`（投影属性）+ `TaskReplayRunnerTest.CiAlign`（CI 消费画像
    指纹的判定行为）+ `VerifyExportTest`（包定格侧回归网）
12. **returnsEmptyOnError 按结构判空**：空 = 纯空白或 JSON 根为空数组/空对象；文本中出现
    `[]` 字面量不构成空，解析失败按非空处理（出错应空场景不得误放行）。
    【测试钉】`BehaviorCheckerTest` 空对象/内嵌空数组/非空数组/文本含 `[]` 四钉

## 行为矩阵

| 基线侧 × 当前侧 | 判定 |
|---|---|
| 四维全部相等（或可归一化相等） | PASS |
| 工具集不同 / 参数类型映射不同 | CHANGED（维度 1，ignorable 不豁免） |
| JSON 字段新增 / 删除 / 类型变化 | CHANGED（维度 2） |
| contentType 变化（含 JSON↔text） | CHANGED |
| 两侧纯文本长度数量级差 ≥1 | CHANGED（展示分按 ±1/≥2 分档） |
| 声明必需词缺失 / 禁用词命中 / 正则不匹配 | CHANGED（维度 3） |
| 声明 behavior 不满足 | CHANGED（维度 4） |
| 无声明规则/行为 | 该维不构成差异 |
| ignorable 字段路径变化 | 不构成差异（仅维度 2） |
| 规则文件含未知 behavior 名 | 判定按通过处理 + CLI 加载告警 |
| 基线 algo_version 缺失或 ≠ 引擎版本 | 重放入口拒绝判定 |
| 当前输出文本 null | 视同空串参与维度 3/4 校验 |

## 域间边界

- **上游 identity**：声明标签是维度 3/4 规则的查找键；键本身永不进指纹（判定正确性与声明
  质量解耦）。
- **上游 config**：rules 文件（invocations 段=维度 3/4、tasks 段=任务纪律）与 regression 段
  （ignorableFields）经配置加载进入本域；CLI 侧比较器单一工厂构造保证口径不分叉。
- **下游 governance**：候选指纹 = 重放/对齐路径首个 CHANGED 配对的现场重提结果，经
  recordCandidate 登记；本域只保证提取与判定的确定性，不触发登记。
- **下游 replay**：对齐器逐配对注入比较器；报告头携带判定语义版本。
- **展示面**：逐维差异清单（summary）是唯一诊断输出，score 仅供排序参考。

## 变更纪律

- 指纹维度定义、比较器裁决规则、内置 behavior 语义 = **判定语义冻结契约**（det-v1）：
  开发期变更 = 删库重建；发布后任何改变「同样差异得出什么判定」的变更必须递增版本号
  （仅增强捕获保真或纯性能优化不递增）；同一版本号下判定语义永不改变。变更属单向门
  （静默重解释用户已批准的历史基线）。
- 内置 behavior 名单扩充属新增能力（不改变既有判定），可随小版本演进；既有 behavior 的
  判定语义变更按上一条纪律走。
- 维度 1/2 的归一化策略（toLowerCase、长度数量级）与 identity 域 paramSignature 口径对齐，
  两侧不得分叉。

## 复核台账

| 日期 | 方式 | 发现 |
|---|---|---|
| 2026-09-16 | Round 7 验收 Z#2 判明（设计确认，非缺陷） | invocation 级声明（requiredKeywords/behaviors）在判定侧生效的前提=**已被钉入基线指纹**（establish/accept 时刻的 extract 注入）；事后建规则文件不回溯钉定——「基线声明、当前答卷」+ F-A 同尺（候选侧声明集不进判定）的共同结论。黑盒可发现性缓解=Rules 正证行补绑定语义；刷新路径仍是 R6-D3 两条（check→accept 带 current rules / --force 重播种）。验收误判教训：先建档后建规则的探针链路全通但判定全空，黑盒须先用 doctor/status --diff 或 force 重建确认钉定态再下「通道失明」结论 |
| 2026-09-16 | D2 结构批随批 | 基线真源=**认可形态有序集合**（establish 单元素起步/accept 尾部追加/rollback 整集恢复）；判定 = 链末指纹 ∈ 集合——任一命中 PASS，全不命中 CHANGED 且差异对最近似成员计算（平局取集合序更早，确定性不妥协）；候选守卫从「≠现役」推广为「∉集合」（镜像候选 churn 根治）；集合大小>1 时步骤注记 shapeIndex/shapeCount。【测试钉】`TaskAlignerTest.MultiShapeBaseline` + `BaselineManagerTest.MultiShapeSemantics` + `TaskReplayRunnerTest` D2 工作流钉 |
| 2026-09-15 | B2 批（验收包钉批准真相）：包路径基线侧真源从「导出时刻现场提取的最新链快照」改为「画像活跃指纹定格（与 CI 同源）」 | ①候选侧同尺：verify 换轨 alignLatestPerInvocation（与 CI 同一判定入口，任务纪律/前缀看全链，dry-run 配对行 localSteps 改 judged 计数）；②出厂偏离检测：组末提取与批准指纹的结构维不一致（同判定尺口径——仅声明集漂移不计偏离）或在途候选 → PackTask.unadjudicatedSteps（恒序列化，缺字段读取缺省 0）+ export-report/1 总计数 + 人读警告；③自违检查重排：只在组末与画像一致时执行（未批准形态走偏离出口，不误诊自违）；④步骤=调用点（组末证据锚；stepCount 值语义=调用点数、servedModels 只取组末记录）；⑤包语义单向门（§12.4）：开发期 det-v1 不 bump |
| 2026-09-15 | B1 批（链末判定）：契约 11 候选侧定义分形——CI 路径候选侧 = 链末执行（草稿进透明层） | 判定域按路径分形（链/成员=全记录，CI=链末）；replay 契约 19 同批重写；判定语义单向门开发期承接（det-v1 不 bump）；B2 批将把包路径候选侧同尺化为链末执行（见 B1B2 实施方案） |
| 2026-09-14 | A1/A2 修复批（批 1）同批修订：--ci 基线对照让画像存档指纹首次进入**判定**（基线侧） | 契约 11 由「两侧现场重提」全称表述收窄为「候选侧永远现场重提 + 基线侧三源定格投影 + 版本守卫强制可比」；真源表同步；「任何对比一律现场重提」全称命题自 verify 包路径存在起即被突破，本修订是把既有实践升格为明示模型（单向门 ×2 标注：--ci 判定基准变更 + 本规范修订） |
| 2026-09-03 | S3 成文：FingerprintExtractor/DeterministicComparator/BehaviorChecker/JudgmentSemantics/BaselineManager 全量对账 + 测试指针核实 | ①ARCHIVED 枚举值从不写入活跃行（导读「基线三态流转」的表述易误读为活跃行三态，governance spec 成文时精确化）；②指纹序列化字节可复现（FingerprintJson 键序固定 + TreeMap/TreeSet 归一），提取器内存 HashMap 不影响；③维度 1 不受 ignorableFields 豁免为现行事实（测试未显式反向钉「维度 1 不豁免」，为可收缩项） |
| 2026-09-17 | D1 术语清扫（维护者「质量优先」裁决） | 契约 11 两处单数旧词：「CI 对照 = 画像活跃指纹」→「画像认可形态集合」、「承诺 = 批准指纹」→「批准形态集合」（与同契约既有的集合措辞及 BaselineSides.fromProfiles 的形态集合投影对齐）；台账历史行保留原词 |
| 2026-09-17 | 1.0.0 收尾批：S3 可收缩项补钉 | 台账在册的「维度 1 不受 ignorableFields 豁免为现行事实、测试未显式反向钉」补钉：DeterministicComparatorTest.ignorableFields_neverExemptToolDimension——把参数键名配成 ignorable 不能掩盖参数类型差异（CHANGED + paramTypeMatch=false + 结构维不受影响）；ignorableFields 的归一化边界自此只此一份语义 |
| 2026-09-19 | 冻结门契约对齐批（独立审查发现收敛） | ①契约 12 改写为结构判空现状（原「已在源码标注 TODO」表述因 09-17 修复而过时）；②契约 6 补正则语义：声明实例惰性编译一次复用（setPattern 失效有钉）、不设匹配超时（墙钟超时引入平台相关非确定性）、回溯复杂度声明者自负；③维度 1 argTypes null 值跳过（存储往返病态行不中断建档，dim1_nullArgTypeValue_skippedNotThrown）；④BaselineManager.rollback 对 null versionTag 抛 IllegalArgumentException（公开 API 防御，rollback_nullVersionTag_rejected） |
