# 等价类规格（equivalence）

> 最近复核：成文 2026-09-17 · 依据通道 2 七轮双宿主复盘（119 项发现收敛七族）反向枚举成文；
> 等价类 = 「必须同形」的格子对（命令 × 表面 × 路径 × 模式），本 spec 是它们的唯一登记处
> 验证三档占比：【测试钉】4 条 ·【命令可证】3 条 ·【人工对账】2 条

## 职责与边界

**管**：等价类登记——哪些语义在哪些格子（CLI / MCP / JSON / 人读 / 兄弟路径 / 模式）之间必须
同形；每类的权威实现（真源）、成员格子、等价钉与现状。

**不管**：各格子自身的行为契约（归各域 spec）；发现新等价类之前的问题排查（归各域）；禁词
的语义裁决（归维护者决策，本 spec 只登记结果）。

## 真源与派生

等价类的真源 = 「权威实现」列指名的代码站点；其余成员格子必须消费同一实现或被等价钉覆盖。
手写第二份实现（拷贝权威逻辑）即违约——派生数据必须从显式真源计算（与既有真源规范同源）。

## 登记表

| 等价类 | 权威实现 | 成员格子 | 等价钉 | 状态 |
|---|---|---|---|---|
| 命令↔工具存在性与参数镜像 | picocli 命令声明 | CLI 全命令全参数 × MCP 全工具全入参 | CliMcpParityTest（反射枚举双向断言 + 显式豁免清单） | 钉在 |
| 重驱目标解析 | `TaskReplayRunner.reDriveTargets` | dry-run 计划（含报价输入）× 真跑目标 | `reDrive_dryRunPlanEqualsRealTargets`（TaskReplayRunnerTest） | 钉在 |
| 自动建档豁免语义 | `BaselineService.establishMissing` 扫建路径的裂键过滤 | 裸 `baseline` 扫建 × bare replay 自动建档 ×（未来）任何新扫建入口 | `autoEstablishSweep_pathsAgreeOnSplitKeys`（TaskReplayRunnerTest） | 钉在 |
| 种子选择语义 | `BaselineService` 播种桶内最新 | establish × force × MCP establish | `seedIsLatest_regardlessOfInsertionOrder`（BaselineServiceTest，生产路径钉） | 钉在 |
| 治理写时间线 | `governance_events` 表（经 BaselineManager 单源落账） | audit 命令 × audit 工具 × 全部治理动词 | SqliteStorageRepositoryTest 治理事件往返 + 七轮 audit 对账 | 钉在（动词全覆盖断言列增强项） |
| 披露字段面 | 各能力的披露实现 | seed record / approvedBy / 扇出披露 / member 窗口计数 × CLI 人读 × CLI JSON × MCP | `DisclosureParityTest`（四能力抽查；新披露字段交付时按 §12.11 DoD 增行） | 抽查钉在 |
| 术语 | 本 spec 禁词表（下节，唯一真源） | 全部用户可见输出（主码串/help/instructions/报告模板）× 文档 | doc-tools/scan_banned_terms.py（读本表扫描；私有资产，丢失可按本表重建） | 扫描器在 |
| 退出码语义 | `CliErrorCode` 枚举 | CLI 各命令 × MCP 各工具同错误同码 | 错误路径测试逐命令钉（error/1 包络批） | 部分（新错误码随批补钉） |
| 缩域语义 | `CliSupport` 选择器解析（目标选择器=精确+唯一前缀；缩域选择器=前缀过滤） | status / report / check / diff / export / verify / replay 各命令 | 选择器语义钉（SelectorSemantics 相关测试）+ R7 实测 | 部分（跨命令等价断言列增强项） |

## 禁词表（术语等价类的真源；扫描器消费本表）

每行 = 一条已退役表述。**新增行**：决策翻转当批登记（谁退出、替成什么）；**匹配**：`re:` 前缀
按正则、否则按字面（区分大小写）；**扫描对象**：src/main 与 src/test 全部 Java 源码（必须零
命中）+ README×2 / OPERATIONS / guide 全部 markdown（命中 = D1 类文档债工作清单）。

| 退役表述 | 替代表述 | 退出原因 |
|---|---|---|
| `re:(?i)\bapprove\b` | accept | approve→accept 全局改名（动词统一裁决） |
| `re:(?i)\bapproved baselines\b` | approved shape set | 基线=认可形态集合（D2 结构批） |
| `re:(?i)\bpromote(s)? the candidate\b` | add the candidate shape to the set | accept 是追加不是提升（D2 结构批） |
| `re:(?i)\bearliest record\b` | latest record | establish 播种=桶内最新（D1 裁决） |
| `re:agent\s*透镜` | 全量治理时间线（AI 与人同账本） | audit 全量时间线（D7 裁决） |
| `never flips` | （整句重写） | 旧 instructions 夸大承诺（R6 U12 清除） |

## 状态机与生命周期

本域无状态机——等价类登记行只有两态：钉在 / 待钉（新增成员格子在钉落地前进「待钉」并计入
复核台账，不允许无声缺席）。

## 契约

1. 【测试钉】登记表「等价钉」列指名的测试必须存在且绿——删除或改弱断言 = 破坏本 spec，评审
   必须拦截。
2. 【测试钉】新增公开面（命令/工具/参数/路径/模式）时，必须反查本登记表：它加入哪些等价类
   的成员格子；加入即要么消费权威实现、要么补等价钉（§12.11 DoD 勾稽面之一）。
3. 【命令可证】禁词表扫描：src/main + src/test 零命中（`python doc-tools/scan_banned_terms.py`
   退出码 0）；文档命中允许存在但必须登记为文档批工作项。
4. 【命令可证】跨命令缩域等价：同一选择器串在各命令解析出同一目标集（status / replay /
   check / diff / export / verify）。
5. 【人工对账】披露字段面：新披露字段交付时四格（CLI 人读 / CLI JSON / MCP 文本 /
   MCP structuredContent）齐查——当前为抽查钉（四能力），全量齐查靠 DoD 流程。
6. 【人工对账】退出码等价：新错误码接线时 CLI 与 MCP 同步钉——当前靠 error/1 包络逐命令
   测试覆盖。

## 行为矩阵

| 变更类型 | 登记表义务 |
|---|---|
| 新命令/新工具 | 命令↔工具行加成员；参数镜像进 CliMcpParityTest |
| 新执行路径复用既有语义 | 反查该语义的权威实现行；兄弟路径入成员列并补钉 |
| 术语/命名决策翻转 | 禁词表加行 + 全表面回扫（决策变更全表面重审） |
| 新披露字段 | 披露字段面加能力行 + 四格抽查 |
| 判定语义/指纹算法变更 | 通知全部登记行复核（真源变更波及所有成员格子） |

## 域间边界

**上游**：各域 spec（行为契约的真源）——本 spec 只登记「格子间同形」，不重复定义行为。
**下游**：doc-tools 扫描器（禁词表消费方）；CliMcpParityTest 与各等价钉（登记行的机械执行）；
§12.11 DoD（本登记表为新增公开面的勾稽底册）。

## 变更纪律

- 登记表行与禁词表行 = 可自由追加（追加即扩大保护面）；**删除或改弱 = 评审必须显式批准**，
  与删测试同级别。
- 权威实现列的迁移（单源化重构） = 常规重构，但成员格子必须随迁并在台账留痕。
- 禁词表新增不设门（决策产物）；语义性删词（把退役词复活）= 维护者裁决。

## 复核台账

| 日期 | 方式 | 发现 |
|---|---|---|
| 2026-09-17 | 成文（七轮复盘反向枚举 + 首版钉落地） | 首版九行：六行已有钉/扫描器在位，退出码与缩域两行标「部分」待增强项；禁词表首版六行（approve/approved baselines/promote the candidate/earliest record/agent 透镜/never flips） |
