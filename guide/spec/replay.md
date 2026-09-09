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
7. **JSON 报告 task-report/1**：逐行分段——drift-detection（漂移集/零模板计数）→
   task-align（逐步 action/verdict/dims/cost）或 selfEstablished → drift-disposition（逐漂移
   点 action: collected/candidate/hung/external/uncollected）；dry-run 为 task-dry-run。键名
   稳定、单行、null 缺省即契约；报告头钉判定语义版本。【测试钉】
   `TaskReplayRunnerTest.JsonContract`
8. **守卫五项**：判定语义版本守卫（任一画像版本不符拒绝判定）；`--ci` 未建档拒绝判定
   （缩域名单）+ 漂移 PASS 不收编；换模型告警（配置缺省时比对客户端实际生效模型）；
   全败按基础设施故障退出（重驱层）；served 模型不一致逐步标注。【测试钉】`TaskReplayRunnerTest.Guards` 全组
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
13. **成员判定（--member-check）**：每任务最新链对同任务最近 N 条历史链逐一核成员资格，
    样本窗上限 5（常量钉死，防「匹配任何历史」稀释判定）；任一样本行为全匹配（步级全
    MATCHED+PASS）即合法成员，报告 matchedSession；全不匹配取信号分最高者为最接近样本
    （升序迭代+严格大于=平局取最早），差异报告与候选登记挂在证据对齐上。任务纪律为样本
    不变量，从证据对齐取一次计一份，不跨样本累计。缺省配对语义不变（最新 vs 次新）。
    mode=member-check。【测试钉】`TaskReplayRunnerTest.MemberCheck`（匹配成员/平局取最早/
    样本窗封顶与 JSON 字段）
14. **首航即批改**：单链任务首航自建基线时，已声明 taskKey 且配了任务规则即现场评纪律——
    违规折叠 exit 1（与对齐模式同语义），selfEstablished 报告携带 ruleViolations；
    「基线声明、当前答卷」从第一份答卷生效，不必等第二条链。【测试钉】
    `TaskReplayRunnerTest.FirstVoyageAndExitHealth.firstVoyage_taskRuleViolation_exits1`
15. **优化信号（非判定）**：task-align/member-check 报告的 summary 携带 comparedPairs/
    skippedPairs（对齐在首个 CHANGED 配对即停，聚合只承认已比对配对，缺失分数不默认补值）；
    signal 对象=已比对步骤信号分均值（无已比对步骤时整体省略）。明示非判定——判定始终
    二值。【测试钉】`TaskAlignerTest.comparedSkippedPairs_earlyStopOnFirstChanged` +
    `TaskReplayRunnerTest.SignalAndStability.signalAndPairCounts_json`
16. **稳定性注记（纯读侧）**：逐任务对组内全链逐调用点提取指纹（与判定同源），报告
    executions/points/fluctuating[]（形态数 >1 的点）；判定不受影响（缺省配对最新 vs 次新、
    成员模式见契约 13）。人读一行明示 informational 并提示不追噪音。【测试钉】
    `TaskReplayRunnerTest.SignalAndStability.stabilityNote_fluctuatingPoint`
17. **出口健康摘要**：replay/status/verify 出口附裂键/自建任务/多步零标签链三计数一行
    （与 doctor 同源口径，`CliSupport.isMultiStepUnlabeled` 单源谓词）；人读全零不打印；
    机器通道=status/1 与 verify-report/1 的 `health` 对象、replay 的 exit-health 报告行
    （mode 封闭词表见 cli 契约 6）。【测试钉】
    `TaskReplayRunnerTest.FirstVoyageAndExitHealth`（人读行/JSON 行）
18. **重放客户端族谱与协议自动推导**：客户端构造单点 `CliSupport.createLlmClient`
    装配协议路由客户端（ProtocolRoutingLlmClient，内建三方言客户端
    OpenAiCompatibleClient / AnthropicMessagesClient / OpenAiResponsesClient）。
    **每次调用的方言按三级优先解析**：显式配置 `llm.protocol`（封闭词表=LlmWireProtocol，
    跨协议重放是显式意图）> 请求携带的记录方言提示（LlmRequest.wireProtocol——重放装配
    侧随基线记录的 apiProtocol 塞入，同协议原样重放零配置）> `openai-chat` 兜底；提示值
    不在词表内（历史脏数据）同样兜底不抛错。HTTP 管道共享基座 `AbstractHttpLlmClient`：单次尝试超时契约
    （连接与读取各自上限，超时即 LlmTimeoutException 不重试）、429/5xx/连接被拒的指数
    退避重试、响应体 10MB 读取上限、健康探测口径统一为**传输层可达**（GET models 端点，
    2xx/404/405 算可达——鉴权有效性在首个真实调用暴露，不在健康检查里花成本验证）。
    未知协议值在构造点抛 E-USAGE 并列三合法值。**帧合成通用不变量**（三客户端同一条）：
    工具结果帧必须携带配对键，缺失的帧跳过并可见告警，绝不构造会被服务端 400 拒绝的
    请求；文法要求显式发起帧而录制侧无独立载体时，从结果帧的关联键合成最小合法发起帧
    且同一配对只合成一次；system 恒走协议的 system 位（chat 首消息 / anthropic 顶层
    system / responses instructions），历史 system 帧一律跳过。方言特化：anthropic 的
    max_tokens 必填兜定常量 4096、tool_result 逐对重建（发起帧与结果帧相邻、配对键一致）
    且相邻同角色消息合并为单条消息的 content 块数组（规范交替形——官方服务端对连续同角色
    静默合并、严格旧实现直接拒绝；user 消息内 tool_result 块按文法要求前置于文本块；
    LangChain/Vercel AI SDK 同款合并实践）、
    范式 data-URI 图像拆解回 base64 source（http URL 形丢弃告警）、工具定义扁平形带方言
    必填字段矫正（input_schema 必填：无参工具补空对象 schema、缺 type 补 "object" 只补缺
    不覆盖显式值、损坏 schema 定义宁可不带）；responses 的 input 恒
    items 数组（文本 part 按角色取 input_text/output_text）、有状态成员
    （previous_response_id/store）不携带。方言归一合同（finish/usage 归一表）与
    recording.md「wire 方言归一」节共用单源。【测试钉】`CliSupportLlmClientTest`（分发/
    未知值/可达口径）+ `ProtocolRoutingLlmClientTest`（三级解析优先级）+
    `RegressionTestExecutorTest`（记录方言提示装配）+ `OpenAiCompatibleClientTest`（归位行为零变更回归）+
    `AnthropicMessagesClientTest` / `OpenAiResponsesClientTest`（组装逐字段钉/帧守卫敌对/
    协议头）+ `ThreeProtocolDeepSeekIntegrationTest`（三协议 DeepSeek 真机连通，key 门控）

## 行为矩阵

| 场景 | 结果 |
|---|---|
| bare、全库无录制 | exit 2 + 录制引导（stderr in --json；机器包络见 cli 契约 7） |
| bare、全部任务单链 | 逐任务自建基线，exit 0；声明任务有规则违例时首航即批改 exit 1（契约 14） |
| bare、任务两链同构 | 对齐 PASS；无漂移出 0；有漂移按处置出口 |
| 任一对齐 CHANGED / 缺步骤 / 新增 / 规则违规 | exit 1（CHANGED 步落候选） |
| --member-check、新链匹配任一最近链 | 成员 PASS，exit 0，报告 matchedSession（契约 13） |
| --member-check、全样本不匹配 | exit 1，按最接近样本报差异并落候选（契约 13） |
| 漂移 + 步骤 PASS（开发态 / --ci） | 收编前移身份 / 不收编附警告；均 exit 0 |
| 漂移 + 缺步骤 / 无可对齐链（bare） | 挂起，exit 1 |
| 漂移 + 键不在缩域对齐范围 | 仅检测报告，不处置，不贡献退出码 |
| --task 前缀歧义 / 无命中 / AND 交集为空 | exit 2 |
| --ci 且缩域内有未建档键 | exit 2 + 名单 |
| 任一画像判定语义版本不符 | exit 2 + 重建指引 |
| dry-run | 只读预演（检测 + 对齐计划），恒 exit 0 |
| 换模型执行 | 告警行，判定照常（结果不可比性留给使用者） |
| 任意判定完成出口（dry-run 预演与 fail 除外） | 出口健康摘要一行（人读全零静默；JSON 为 exit-health 行/health 对象，契约 17） |

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
| 2026-09-09 | 连续同角色合并吸收自外部对标（官方 API 参考 2024-10 起合法+服务端合并；LangChain 源码 "Merge runs of human/tool messages"；Vercel convertToAnthropicMessagesPrompt 同款；LiteLLM #18271 未决即反面教材） | buildMessages 重构为结构化帧+合并趟（顺带收益：转义改走 serialize 单源）；tool_result 帧与末位文本输入不再产出连续 user 消息——严格旧兼容实现的最后风险位关闭；单文本常态保持字符串 content（golden 形态最小扰动） |
| 2026-09-09 | 三协议批②（发射）同日：AnthropicMessagesClient/OpenAiResponsesClient 交付，契约 18 扩帧合成通用不变量与方言特化；真机三协议 DeepSeek 连通 6/6（含工具历史帧合成被真实端点接受） | 方案表格原表述「并行调用结果块集中同一条消息」按实现修正为逐对重建（发起帧与结果帧相邻）——文法等价（服务端按 id 配对）且忠实于记录的逐帧结构；真机发现 deepseek-chat 被 DeepSeek 服务端别名映射为 deepseek-v4-flash 回报，servedModel 断言不钉具体值 |
| 2026-09-09 | 三协议批①（基座+摄取）：契约 18 新增；HTTP 管道自 OpenAiCompatibleClient 抽入共享基座（归位行为零变更，现有测试全绿为钉） | ①isAvailable 旧口径（GET models 仅 200 算可达）统一为传输层可达（2xx/404/405）——鉴权有效性让位给首个真实调用，属本批有意变更非回归；②llm.protocol 词表与 record 摄取/apiProtocol 列同源（LlmWireProtocol 单源四处同形） |
| 2026-09-08 | 优化包批实施（同日）：成员判定/首航批改/优化信号/稳定性注记/出口健康 | 契约 13–17 新增；mode 词表增 member-check 与 exit-health；对齐渲染抽出 renderAlignment 共用（task-align/member-check 两模式同源）；D9（evaluateTaskRules 提升 public）与 D3（comparedPairs/skippedPairs）随批兑现；信号/稳定性明示非判定，缺省判定语义零变更 |
| 2026-09-04 | 盲跑复盘批（同日）：D1 同指纹候选短路 + 缩域即重驱域落地 | 契约 9 增补登记前置（候选≠现役指纹，governance.md 同步）；契约 12 增缩域分支；契约 10 增工件自愈语义——三处均来自 dogfood 门 13 盲跑的实际摩擦（无信息候选界面/定向复核无入口/工件任务长期 exit 1） |
| 2026-09-03 | S7 成文：统一引擎落地代码全量对账（TaskReplayRunner/TaskAligner/TaskChainView） | ①调用点域采样引擎（ReplayRunner/ImpactAnalyzer/AnalysisResult）已随统一引擎批拆除，replay-report/1 模式随之退役（task-report/1 承接）；②「同键富余不判差异」与「缺步骤」的边界经测试夹具纠偏后钉清——富余=同键记录数不齐，缺步骤=键整组缺席；③重驱层为下一批次唯一人工对账项 |
