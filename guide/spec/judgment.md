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
|---|---|---|
| 指纹 | 交互记录本体（工具调用集/参数类型/模型响应文本/工具成败位）+ rules 配置（维度 3/4 声明） | `FingerprintExtractor.extract` 现场重提；**存档指纹只作展示与审计，任何对比一律现场重提、不消费存档值** |
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
   （关键词 contains、禁用 noneMatch、正则全匹配）。【测试钉】dimension3_*/dimension4_* 组
7. **加权评分仅展示**：权重随声明维动态重分配，判定分支不消费 score。【测试钉】
   dynamicWeight_* / passVerdict_scoreExactly095 / changed_lowScore
8. **行为校验器**：八个内置 behavior（mustUseChinese/mustUseEnglish/returnsEmptyOnError/
   returnsErrorCode/noError/jsonOutput/nonEmptyOutput/containsCjk），语言类用码点扫描不用正则；
   未知名默认通过（不误报），CLI 加载时点破并列出合法名。【测试钉】`BehaviorCheckerTest` +
   `CliSupportResolverTest`（unknownBehavior_warnedAtLoad）
9. **判定语义版本守卫**：建立/批准/重建三条成为基线的路径统一盖章；重放入口校验基线
   `algo_version` 与引擎一致，不一致（含未标记历史行）拒绝判定——算法升级不得静默重解释
   已批准基线。【测试钉】`BaselineManagerTest`（approve 后活跃行与归档行的 algoVersion 断言）+
   `ReplayFlowTest`（staleAlgoVersion_refusesJudgment / unstampedAlgoVersion_refusesJudgment /
   报告头钉 judgmentSemantics）
10. **空值兜底**：程序化构造的缺省集合字段、null 输出文本、双空指纹均安全比对不 NPE。
    【测试钉】nullOutput_treatedAsEmpty / bothEmptyFingerprints_pass / nullConfig_usesDefaults
11. **现场重提原则**：对比两侧指纹均由对齐器/重放器现场提取注入，存档值仅展示审计。
    【人工对账】实现分别位于 TaskAligner（alignMatched 双侧现场重提）与重放执行器；
    行为由对齐与重放域测试间接覆盖，无直接对偶用例
12. **returnsEmptyOnError 的空数组子句宽泛**（`contains("[]")` 会把含空数组字面量的正常输出
    误判为空）——已在源码标注 TODO，改结构化判空需随版本纪律走。【人工对账】既有债务

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
| 2026-09-03 | S3 成文：FingerprintExtractor/DeterministicComparator/BehaviorChecker/JudgmentSemantics/BaselineManager 全量对账 + 测试指针核实 | ①ARCHIVED 枚举值从不写入活跃行（导读「基线三态流转」的表述易误读为活跃行三态，governance spec 成文时精确化）；②指纹序列化字节可复现（FingerprintJson 键序固定 + TreeMap/TreeSet 归一），提取器内存 HashMap 不影响；③维度 1 不受 ignorableFields 豁免为现行事实（测试未显式反向钉「维度 1 不豁免」，为可收缩项） |
