# 重放与对齐规格（replay）

> 最近复核：364801f / 2026-09-03 · S7 成文（会话内对照统一重放引擎 TaskReplayRunner /
> TaskAligner / TaskChainView 实现逐项对账——本 spec 以统一引擎落地代码为基准）
> 验证三档占比：【测试钉】13 条 ·【命令可证】0 条 ·【人工对账】0 条

## 职责与边界

**管**：重放引擎编排（三层判定模型）、任务链派生与逐任务对齐配对、任务纪律评估、漂移处置
的引擎侧驱动、缩域选择器、退出码复合、task-report/1 报告契约、dry-run 预演。

**不管**：模板身份漂移的感知与治理写（identity/governance——引擎是处置执行器，写只经
BaselineManager）、指纹与判定口径（judgment）、CLI 命令面注册与参数解析（cli）、存储承载
（storage）。

## 真源与派生

| 语义状态 | 真源 | 派生链 |
|---|---|---|
| 任务链 | interactions 交互历史（派生视图，无实体表） | 按 session 分组、请求文本切片；metadata 显式 `taskKey` 声明优先于 userInput 派生（损坏 metadata 按未声明退化，不中断） |
| 对齐结果 | 基线链 × 新链现场指纹 | TaskAligner 逐调用点配对、两侧现场重提指纹比对，不消费任何存档指纹 |
| 漂移处置输入 | DriftReport（只读巡检产出） | 引擎按对齐步结果驱动三出口（出口语义归 governance） |
| 图 | interactions 全量重建的内存邻接表 | 每次重放现场重建；快照落盘供 status 巡检（dry-run 不落盘） |

## 状态机与生命周期

本域无自身状态机；漂移处置状态机（收编/候选/挂起三出口）见 governance 域，引擎是执行器。
一次 bare 重放的编排顺序钉死：检测报告（全项目）→ 缩域 → `--ci` 未建档守卫 → 开发态自动
建档 → 判定语义守卫 → 逐任务对齐 → 漂移处置 → 退出码复合。

## 契约

1. **三层判定模型**：bare 执行 = 身份检测（全项目零调用）→ 真实对齐（缩域内逐任务，零调用）
   →（受控重驱，花 LLM 钱的显式层）。缺省路径零 LLM 调用。【测试钉】`TaskReplayRunnerTest`
   （Alignment/DriftStateMachine 全组 + Guards.dryRun_readOnly 的零调用只读性）
2. **任务链派生**：同 session 内按请求文本切片成链（声明 taskKey 优先）；同文本多链是同一
   任务的多轮执行（升序全保留，对齐取最新 vs 次新）。【测试钉】`TaskChainViewTest`
3. **对齐配对**：有声明标签的步骤按标签分组、跨模板版本配对（细分哈希差异记 versionSwitch
   注记，判定照常）；无标签按完整键分组（版本即身份，不跨版本）；组内规范序 1:1 配对、
   较少侧配对、富余计数进报告不判差异；缺步骤/新增步骤是行为差异。【测试钉】
   `TaskAlignerTest`
4. **任务纪律**：rules.tasks 只对声明 taskKey 的链生效（键=声明值精确相等）、按新链侧评估
   必备步骤/顺序子序列/次数范围；违规折叠为链级 CHANGED，不新增 verdict 值。【测试钉】
   `TaskAlignerTest` + `TaskReplayRunnerTest.Alignment.taskRuleViolation_exits1`
5. **退出码复合**：行为差异或证据缺口（对齐 CHANGED/缺步骤/新增/规则违规/漂移挂起）→ 1；
   用法/数据/环境截断（选链错误/守卫拒绝/预算耗尽/全败）→ 2；否则 0。判据原则：缺证据因为
   「没跑够」是 1，因为「被截断或环境故障」是 2。【测试钉】`TaskReplayRunnerTest`（Alignment
   与 DriftStateMachine 的退出码断言）+ `Guards`（守卫拒绝路径）
6. **缩域可复合（AND）**：--task 请求文本前缀选择器（精确优先、唯一前缀采用、多候选歧义
   报错列候选）× --invocation 目标解析（命中含该键记录的链）；检测报告全项目不受缩域影响，
   处置限缩域。【测试钉】`TaskReplayRunnerTest.Scoping`（精确优先/歧义/唯一前缀/无命中/
   键缩域/空交集）
7. **JSON 报告 task-report/1**：逐行分段——drift-detection（漂移集/下游/零模板计数）→
   task-align（逐步 action/verdict/dims/cost）或 selfEstablished → drift-disposition（逐漂移
   点 action: collected/candidate/hung/external/uncollected）；dry-run 为 task-dry-run。键名
   稳定、单行、null 缺省即契约；报告头钉判定语义版本。【测试钉】
   `TaskReplayRunnerTest.JsonContract`
8. **守卫六项**：判定语义版本守卫（任一画像版本不符拒绝判定）；`--ci` 未建档拒绝判定
   （缩域名单）+ 漂移 PASS 不收编；换模型告警（配置缺省时比对客户端实际生效模型）；依赖图
   重建与快照落盘（dry-run 除外）；全败按基础设施故障退出（重驱层）；served 模型不一致
   逐步标注。【测试钉】`TaskReplayRunnerTest.Guards` 全组
9. **CHANGED 步落候选**：显式 replay 即测试行为——对齐首个 CHANGED 配对的新记录现场重提
   指纹落候选；自动建档先于候选登记（裂键新画像必须先存在）；`--ci` 已在建档守卫处拒判。
   【测试钉】`TaskReplayRunnerTest.Alignment.stepDiff_changed_exits1_andRegistersCandidate`
   + `ReplayFlowTest.DiffAndAdjudicate`
10. **对齐差异的语义**：对齐层陈述「最近两次真实执行之间行为变了吗」——事实差异在新真实链
    入账前如实存续；approve 清候选转正基线、收敛漂移身份，不追溯改写已录链。变异/测试工件链
    是只追加事实：被拒工件任务在两条干净链入账前每次 bare replay 如实 exit 1，自愈方式 =
    该任务再真实执行两轮；CI 库是本流水线新鲜录制，工件不跨库携带。【测试钉】
    `ReplayFlowTest.DiffAndAdjudicate.diff_candidate_approve_settles`
11. **served 模型对偶检测**：基线链与新链的 served 模型族不相交即报告模型身份变更（同模板
    跨执行行为漂移的主因），零新增存储。【测试钉】`Guards.servedModelPairNoted`
12. **受控重驱层**：`--re-drive` 逐点以最新归档模板真重驱，目标三档优先级——`--full-chain`
    为缩域内全部记录逐条；带缩域（`--task`/`--invocation`）为缩域内全部调用点每键取最新
    可分组记录（显式缩域即显式重驱域，不要求漂移在册）；缺省为仅漂移点（同键漂移 + 标签
    裂键，含挂起点补证）。预算池合计封顶、原文缺席跳过可见、全败出 2；dry-run 出成本报价。
    【测试钉】`TaskReplayRunnerTest.ReDrive`（PASS/CHANGED 落候选/预算/全败/原文缺席/
    fullChain/缩域即域/bare 零漂移零目标/dry-run 九场景）

## 行为矩阵

| 场景 | 结果 |
|---|---|
| bare、全库无录制 | exit 2 + 录制引导（stderr in --json） |
| bare、全部任务单链 | 逐任务自建基线，exit 0 |
| bare、任务两链同构 | 对齐 PASS；无漂移出 0；有漂移按处置出口 |
| 任一对齐 CHANGED / 缺步骤 / 新增 / 规则违规 | exit 1（CHANGED 步落候选） |
| 漂移 + 步骤 PASS（开发态 / --ci） | 收编前移身份 / 不收编附警告；均 exit 0 |
| 漂移 + 缺步骤 / 无可对齐链（bare） | 挂起，exit 1 |
| 漂移 + 键不在缩域对齐范围 | 仅检测报告，不处置，不贡献退出码 |
| --task 前缀歧义 / 无命中 / AND 交集为空 | exit 2 |
| --ci 且缩域内有未建档键 | exit 2 + 名单 |
| 任一画像判定语义版本不符 | exit 2 + 重建指引 |
| dry-run | 只读预演（检测 + 对齐计划），恒 exit 0 |
| 换模型执行 | 告警行，判定照常（结果不可比性留给使用者） |

## 域间边界

- **上游 identity**：对齐分组键（标签/完整键）与选择器解析以键文法为保证；检测凭据口径
  与治理前移共用单一实现。
- **上游 governance**：处置三出口的写行为全部经 BaselineManager（收编/候选）；引擎零直写。
- **上游 judgment**：比较器与规则口径由 CLI 工厂单源构造注入，ignorableFields 与重放一致。
- **下游 cli**：ReplayCommand 只做参数解析与引擎委派；status 复用同一检测器（漂移列单一
  真源）。

## 变更纪律

- 退出码 0/1/2 语义与优先序 = 冻结契约（细分属单向门，调研已论证维持）。
- task-report/1 报告 schema 开发期恒定（mode 值扩展属开发期语义演进，不 bump）；发布后
  只增不改。
- 三层模型的层序与缺省零调用原则 = 已批准设计，变更走显式裁决。

## 复核台账

| 日期 | 方式 | 发现 |
|---|---|---|
| 2026-09-04 | 盲跑复盘批（同日）：D1 同指纹候选短路 + 缩域即重驱域落地 | 契约 9 增补登记前置（候选≠现役指纹，governance.md 同步）；契约 12 增缩域分支；契约 10 增工件自愈语义——三处均来自 dogfood 门 13 盲跑的实际摩擦（无信息候选界面/定向复核无入口/工件任务长期 exit 1） |
| 2026-09-03 | S7 成文：统一引擎落地代码全量对账（TaskReplayRunner/TaskAligner/TaskChainView） | ①调用点域采样引擎（ReplayRunner/ImpactAnalyzer/AnalysisResult）已随统一引擎批拆除，replay-report/1 模式随之退役（task-report/1 承接）；②「同键富余不判差异」与「缺步骤」的边界经测试夹具纠偏后钉清——富余=同键记录数不齐，缺步骤=键整组缺席；③重驱层为下一批次唯一人工对账项 |
