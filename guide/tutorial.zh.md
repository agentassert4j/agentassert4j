# 实战教程：从接入到交付

> 一条贯穿完整生命周期的实战走线——接线、录制、建档、迭代、门禁、重驱、交付、审计。所有命令
> 与输出块均为演示库（虚构的「ShopMate」客服机器人）的真实 CLI 输出，
> 你在本机看到的就是这里的样子。
> 配套阅读：[README](../README.zh.md)（五个问题）、[运维手册](../OPERATIONS.zh.md)（任务导向参考）、
> [ARCHITECTURE](../ARCHITECTURE.zh.md)（代码地图）。

## 0. 场景

ShopMate 是跑在 Spring Boot 3 + Spring AI 1.x 上的客服机器人。用户一句请求让模型跑出一条链：
意图识别 → 查询订单 → 查询物流 → 提交退款 → 组织答复。两个真实时刻驱动整个教程：

- **第 15 天**：团队改了订单查询提示词，退款流程新增了理赔步骤。谁来告诉他们没改坏别处？
- **第 30 天**：客户内网跑的是另一个模型。怎么证明行为还在？

下面所有事情都发生在一台机器、一个 SQLite 文件上。没有服务、没有端口、默认路径零 API Key。

## 1. 接线

加 starter——业务代码一行不改：

```xml
<dependency>
    <groupId>io.github.agentassert4j</groupId>
    <artifactId>agentassert4j-starter-spring-ai1</artifactId>
    <version>1.0.0</version>
</dependency>
```

starter 自动包装容器内所有 `ChatModel` bean，旁路录制每次调用。两条来自真实宿主集成的形态事实，
第一天就该知道：

- 自动包装只覆盖容器内 bean。宿主在服务内部按需构建 `ChatModel`、不注册进容器时，在构建点手工
  包装：`RecordingChatModel.wrap(chatModel, recorder)`。接完线**确认记录真的落库**（`doctor` /
  `status`）——「以为在录、实际没录」是接入阶段代价最高的错误。
- 模板哈希（模板身份的锚点）从请求的 `SystemMessage` 计算。宿主把系统提示塞在 `UserMessage`
  里会失去模板漂移检测——能改就按 `SystemMessage` 传入。

非 Boot 栈：程序化装配（`RecorderConfig.builder()` + `InteractionRecorder`），或经 MCP `record`
工具上报原始 wire JSON——最小录制契约见[运维手册 §8](../OPERATIONS.zh.md#8-最小录制契约)。

## 2. 首录与身份声明

照常跑一遍冒烟/e2e——每次 LLM 调用自动入库，零采集代码。然后跟着 `doctor` 的提示，在值得的
位置声明身份：

```bash
agentassert4j doctor
```

三层声明，各回答一个问题：

1. **调用点标签（`invocationId`）**——「这个步骤叫什么」：`RecordingContext.start(sessionId)
   .withInvocationId("refund")`、应用级 `agentassert4j.recorder.default-invocation-id=tavern`、
   或适配注解。声明标签跨提示词编辑稳定，并给任务规则提供步骤名。
2. **任务键（`taskKey`）**——「这条链属于哪个业务场景」：`RecordingContext.withMetadata("taskKey",
   <场景id>)`。声明键跨会话归组同任务；不声明时按相同请求文本自动归组。
3. **任务规则（`rules.tasks`）**——「这个场景必须怎么走」：`requiredSteps` / `requiredOrder` /
   步骤次数，存在对照后参与判定。

零声明是一等公民——不声明的记录按模板哈希归组，重放、裁决样样可用。声明影响报告粒度，
永不影响判定对错。

## 3. 播种基线

```bash
agentassert4j baseline --approver wang
```

演示库真实输出：

```text
  意图识别 → invocation:意图识别:854e05b8…: baseline established (seed record demo-demo-session-0802-5)
  提交退款 → invocation:提交退款:b47b21ea…: baseline established (seed record demo-demo-session-0801-3)
  查询物流 → invocation:查询物流:8d9dbac2…: baseline established (seed record demo-demo-session-0801-2)
  查询订单 → invocation:查询订单:b3e4b38c…: baseline established (seed record demo-demo-session-0802-6)
  组织答复 → invocation:组织答复:8fd8be58…: baseline established (seed record demo-demo-session-0802-7)
Done: 5 invocations established.
```

每一行都披露**种子**：该调用点的最新执行——即你在建档时刻认可的行为。这里的基线不是一份冻结
的标准答案，而是**每个调用点的认可形态集合**：`accept` 向集合追加，`rollback` 恢复整集快照。

## 4. 改提示词，真实跑一遍，replay

团队发了两处变更：订单查询提示词加了「订单不存在时明确告知」条款，退款流程新增理赔步骤。
真实重跑一遍后：

```bash
agentassert4j replay
```

```text
Drift: 1 same-key, 0 label splits (0 zero-template invocations undetectable)
  ▲ 查询订单@b3e4b38c (查询订单) template ba3e3bc4 → c30f63a2
Alignment basis: each task's latest chain is judged against its previous chain (same request text; declared taskKey groups first).
Task "订单 1234 的物流太慢，我要退款": baseline chain (session demo-session-0801) → new chain (session demo-session-0901)
  [1] 意图识别@854e05b8  PASS
  [2] 查询订单@b3e4b38c  PASS
  [3] 查询物流@8d9dbac2  similarity=0.80 verdict=CHANGED | tool calls match | added fields: [delivery.promise]
Candidate registered: 查询物流@8d9dbac2 (behavior change awaiting adjudication; accept adds the shape to the approved set, reject discards).
  [4] 提交退款@b47b21ea  missing step: baseline invoked '提交退款@b47b21ea', new chain did not
  [6] 理赔查询@3e4c2031  added step: new chain invoked '理赔查询@3e4c2031', baseline did not
Alignment summary: PASS 3 | CHANGED 1 | missing 1 | added 1
Collected: 查询订单@b3e4b38c (no behavioral difference; template identity ba3e3bc4 → c30f63a2)
Pending adjudication (database-wide): invocation:查询物流:8d9dbac2…
Accept with `agentassert4j accept --invocation <prefix>`, or reject with `agentassert4j reject --invocation <prefix>`.
```

<img src="../assets/cli-align-report.png" alt="replay --task 对齐报告（中文演示库真实输出）：PASS 3 | CHANGED 1 | missing 1 | added 1" width="880"/>

报告分三层读：

- **Drift 层**点名哪些调用点的模板身份变了。`查询订单` 被编辑（同标签换模板）——但行为没变，
  框架**自动收集**（Collected）：纯模板漂移且对齐 PASS，不打断你。
- **对齐层**把两条真实链逐步配对：`查询物流` 工具调用相同但输出**新增字段**（`delivery.promise`
  ——输出结构里多出的时效承诺）→ 行为变化，注册为**候选**；`提交退款` 从链里消失
  （**missing**）；`理赔查询` 出现（**added**）。
- **判定只读结构指纹**——措辞差异以低置信呈现给人看，永不进判定。

## 5. 什么产生候选、什么不产生

两类漂移形态的行为差异是设计使然：

| 形态 | 发生了什么 | 结果 |
|------|-----------|------|
| 同标签、新模板、行为不变 | 提示词编辑，输出形状一致 | **自动收集**（Collected，报告披露）——不打断 |
| 同键、输出形状变了 | 换模型、采样变化、工具结果形状变化 | 注册**候选**——等你裁决 |
| 新标签或新模板哈希 | 未声明的编辑、新调用点 | **裂键**——只披露、永不自动并入；门禁保持 fail-closed 直到显式建档 |

未经你裁决的候选，永远不会悄悄把门禁变绿或变红。

## 6. 裁决

promise 字段是预期改进——接受它，CLI 会逐维度点名差异：

```bash
agentassert4j accept --invocation 查询物流 --approver wang --ref demo
```

```text
  查询物流@8d9dbac2 candidate diff (baseline → candidate); each row names the changed dimension:
    Output field set: added [delivery.promise];
```

`accept` 把新形态加入该调用点的认可集合（此前整集归档为版本快照，`rollback --version v1` 可
恢复）。回归则 `reject` 丢弃候选——提示词回滚是 git 的事。共享调用点天然可以多形态：每个
形态认可一次，凡以认可形态收尾的链复检为绿。

注意两个申报身份：`--approver`（谁裁决的）与 `--ref`（申报制、从不校验的代码锚，回答「哪个
提交最后认可了这个行为」）。两者都落在审计时间线上（第 11 步）。

## 7. 入集前先量稳定性

单次匹配是弱证据。稳定性探针抽样任务的最近几条历史链：

```bash
agentassert4j replay --member-check --task "订单 1234"
```

```text
Task "订单 1234 的物流太慢，我要退款": member check — new chain (session demo-session-0901) against the 1 most recent chain(s) of 1 (window 5)
No member match: closest historical chain is session demo-session-0801; differences below are against that sample.
```

**读数看计数，不看布尔**：近邻 `matched 2 of 3` 是稳定复现；`1 of N` 只命中一条远古会话是
考古。JSON 的 `isMember` 只表示「历史任一命中」，刻意不承载阈值。

## 8. 门禁流水线

CI 里固定两步：应用带 recorder 跑一遍冒烟/e2e（新模板真实运行、归档入库），然后一条命令
全项目门禁：

```bash
agentassert4j replay --ci
```

```text
Task "订单 1234 的物流太慢，我要退款": baseline comparison (--ci) — new chain (session demo-session-0901) against the approved shape set
  [1] 意图识别@854e05b8  PASS
  [2] 查询订单@b3e4b38c  PASS
  [3] 查询物流@8d9dbac2  PASS
  [4] 理赔查询@3e4c2031  PASS
  [5] 组织答复@8fd8be58  PASS
Alignment summary: PASS 5 | CHANGED 0 | missing 0 | added 0
```

<img src="../assets/cli-ci-green.png" alt="accept 后 replay --ci：以被认可形态收尾的链复检为绿" width="880"/>

退出码直接 gating：`0` 放行、`1` 行为差异（人裁决）、`2` 环境或证据问题（含缩域内存在未建档
调用点的 fail-closed 拒绝）。门禁从不自动建档、不在流水线里并入漂移身份——未经裁决的裂键
让它保持诚实。

## 9. 换模型，给差异标价

把重驱指向新模型：录制过的提示词原样发出，结构指纹逐调用点点名行为影响，报告附
token/成本/时延对照——reasoning tokens 含在内：

```bash
agentassert4j replay --task "订单 1234" --re-drive --model <新模型> --dry-run
```

`served_model` 注记披露服务端实际服务的模型（含厂商别名映射——配置模型 ≠ 到手模型，当场可见）。
同一配方也是模型选型评测与微调验收；`--max-total-calls/--max-total-tokens` 预算池封顶所有真实
调用，花钱之前先 `--dry-run` 看报价。

## 10. 交付验收

把演示过的行为打包成可携带证据——客户内网跑别的模型也能验：

```bash
agentassert4j baseline export --out pack.json
```

```text
Acceptance pack written: pack.json
  2 task chains / 8 steps (no samples)
  SHA-256: 9f599f92… (reconcile with the accepting party)
```

验收侧真实执行交付清单后：

```bash
agentassert4j verify --pack pack.json --report verify-report.md
```

```text
Per-task verdicts:
  Order 1234 is too slow, I want a refund: PASS (similarity 1.00)
  Where is my order 1234?: PASS (similarity 1.00)
Verification summary: PASS 2 | CHANGED 0 | missing 0 | added 0 | coverage gaps 0 | out-of-scope chains 0
Cross-model acceptance: dev side deepseek-v3 / local deepseek-v4; structural verdicts valid, text differences are expected wording variation.
```

覆盖缺口（包内任务本地未执行）出 2——证据不完整不允许冒充通过。`verify` 全程只读可反复
执行，markdown 报告即交付证据。

## 11. 治理与审计

每笔治理写（establish / force-rebuild / accept / reject / rollback / collect）都落在同一条
时间线上，带主体与代码锚——AI（`agent:*`）与人写同一条线：

```bash
agentassert4j audit
```

```text
Governance writes (9, from the governance event timeline):
  [establish] 查询物流@8d9dbac2 v1 wang 2026-10-03T08:15:37.497Z
  [establish] 查询订单@b3e4b38c v1 wang 2026-10-03T08:15:37.511Z
  [establish] 理赔查询@3e4c2031 v1 auto:Administrator 2026-10-03T08:15:38.693Z
```

机器调用方申报 `--approver agent:<名称>`；MCP 工具面的变异动词必填 approver 参数（缺席即拒）。
授权判定不归框架——那是 harness 权限系统的事；框架贡献的是透明与事后审计。

## 12. 架构与设计取舍

机器背后的推理——判定为什么 100% 确定性（不做 LLM-as-judge）、指纹为什么只读结构维度、录制
为什么宁可丢数据也不阻塞业务、core 为什么只依赖 `java.base`——代码级地图见
[ARCHITECTURE.zh.md](../ARCHITECTURE.zh.md)，逐域行为契约在 `guide/spec/`（唯一权威来源，中文）。

| 决策 | 买到什么 | 代价是什么 |
|------|---------|-----------|
| 确定性结构判定 | 同样的差异在任何机器得到同一判定；CI 可门禁 | 措辞变化对门禁不可见（内容规则按需声明） |
| 旁路录制 | 零侵入；业务时延无感 | 管道高压时丢数据（逐笔记账）而非阻塞 |
| 单文件 SQLite | 零基础设施；库文件即全部状态 | 无防篡改检测——像保护事实源一样保护文件 |
| core 零依赖 | JDK 8 客户可接入；无许可负担 | core 自己实现本可依赖的东西 |
| 派生不建表 | 任务链与依赖图随时可全量重建 | 每次分析运行的重复计算成本（有界、本地） |
