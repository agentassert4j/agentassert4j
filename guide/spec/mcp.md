# MCP server 规格（mcp）

> 最近复核：2026-09-08 · MCP 批成文（本 spec 先行，实现与测试同批交付）
> 规范基准：modelcontextprotocol.io，2026-07-28 修订当日复核；本 server 说握手式方言
> （2025-11-25 及更早客户端的 initialize 协商），理由与钉点见契约 1。
> 验证三档占比：【测试钉】`McpServerTest` + `SqliteStorageRepositoryTest`（saved/duplicate 回告）·
> 【命令可证】`agentassert4j mcp --help` ·【人工对账】通道 2 双宿主实测（1.0.0 验收标准）

## 职责与边界

**管**：`agentassert4j mcp` 子命令（stdio MCP server）、协议方法面（五方法）、工具清单与
manifest、工具→CLI 命令的薄壳适配、record 摄取（wire JSON → 交互记录）、错误分类法、
stdout 纯净性。

**不管**：判定与对齐语义（replay/judgment）、治理状态机（governance）、身份文法（identity）、
存储承载（storage）、CLI 人类面文案（cli）。授权与同意归 harness 权限系统——工具
description 声明使用要求，框架不内嵌第二套同意机制（规划 §〇-4 终审）。

## 心智模型

**standalone jar 即 MCP server**：`java -jar agentassert4j-standalone.jar mcp --db <path>`
起一个绑定单一数据库的 stdio server。工具面是既有 CLI 命令的薄壳（零新判定能力）+ record
摄取（非 Java 栈的入场券）。server 无会话状态——状态全在 SQLite，子进程生命周期即客户端
会话。工具调用在进程内直调命令类（命令实例的包级 out/err 流字段被替换为捕获流，命令产出的
stdout JSON 报告成为工具结果本体；不经过 picocli 参数解析，无全局 System 流替换）。

## 五层结构（每层独立可测）

| 层 | 类 | 职责 |
|---|---|---|
| 传输 | `StdioTransport` | 换行分隔 framing、UTF-8、行预算、CRLF 容忍、空行跳过、EOF/断管静默退出 |
| 分发 | `McpDispatcher` | JSON-RPC 校验、握手状态机、方法路由、通知静默、错误包络 |
| 注册表 | `McpToolRegistry` / `McpTools` | 工具清单 + manifest（description 即面向 AI 的文档）+ 薄壳适配器 |
| 命令适配 | 薄壳直调命令类 | 参数注入（含 --db 与 --json）、流捕获、退出码→isError 映射 |
| 结果组装 | dispatcher 内结果装配 | 双形态（text 报告行 + structuredContent 对象）、证据预算 |

## 契约

1. **协议方言与版本协商**：握手式（initialize）。`initialize` 请求的 `protocolVersion` 属于
   支持集 {2024-11-05, 2025-03-26, 2025-06-18, 2025-11-25} 时原样回显；否则回
   **2025-11-25**（本 server 声明的最新支持版）。2026-07-28 修订改为逐请求 `_meta` 版本
   声明 + `server/discover`（非握手）；本 server 不实现该机制，仅前瞻性保留分发层可扩展
   （新增方法 = 注册表一处改动）。initialize 结果携带
   `capabilities: {"tools":{"listChanged":false}}`（工具清单静态）、
   `serverInfo: {name:"agentassert4j", version:<FRAMEWORK_VERSION>}`、一段工作流
   instructions。【测试钉】`McpServerTest` 握手组
2. **方法面（封闭集合）**：`initialize`、`notifications/initialized`（通知）、`tools/list`、
   `tools/call`、`ping`。未知**请求**回 -32601；未知**通知**静默忽略（JSON-RPC 通知无 id
   不应答，对通知回错即协议违规）；`notifications/cancelled` 收到即忽略并在本文档化
   （v1 无可取消语义：工具串行执行、无进度通知）。批式数组（2025-06-18 起已从规范移除）
   按 invalid request（-32600）处置。分页不支持：`tools/list` 忽略 cursor、不返回
   nextCursor（工具恒 ≤ 12 个）。【测试钉】路由组
3. **JSON-RPC 校验与 id 保真**：行不是合法 JSON → -32700（id:null；字面 `null` 是合法
   JSON 值，按非对象消息归 -32600——解析器退化契约下失败与字面 null 同返 null，分发器以
   原文区分两者）；合法但非对象 / `jsonrpc` ≠ "2.0" / method 缺失或非字符串 / id 为对象或
   数组 → -32600（id 尽力回显）。请求 id 为字符串/数字（含 null）时原样回显
   （parser 产 Long/Double，序列化保形）。initialize 完成前的请求（ping 除外）回 -32002
   "Server not initialized"；重复 initialize 回 -32600 "Already initialized"。
   【测试钉】路由组
4. **错误分类法（工具调用）**：
   - **协议错误**（JSON-RPC error）：请求结构级缺陷——`tools/call` 缺 name/name 非字符串
     → -32602；arguments 存在但非对象 → -32602；**未知工具名 → -32602**（规范原文示例
     如此，2025-11-25 与 2026-07-28 双确认；实施规划中「未知动词值一律 isError」据此收窄为
     「已知工具的参数值语义错误」，此为规范驱动的修正，防重开）。
   - **工具执行错误**（`isError:true` 结果体）：工具入参的语义校验失败（record 的 request/
     response 非法 JSON、protocol 非已知值、必填空串）与命令 exit 2（守卫拒绝/环境故障/
     预算截断）。执行错误结果的结构化本体复用 CLI 的 agentassert4j.error/1 包络
     （errorCode 四族 + hints + nextAction），AI 消费方可自助续行。
   - 工具执行抛出的未捕获异常 → isError:true + E-ENV 包络（分发循环永不因工具异常崩溃）。
   - exit 0/1（含 CHANGED 判定）**不是**错误：isError=false，报告本体即结果——判定语义
     由报告承载，不由 isError 承载。【测试钉】工具组
5. **工具面（12 个，注册序即清单序，静态）**：CLI 薄壳（读动词 ci 语义 + 变异动词 + record
   摄取）。工具名与 CLI 动词同词（check/report/diff/verify/doctor/graph/record/establish/
   accept/reject/re-drive/export）。每个工具的 manifest description 讲清：做什么、前置条件
   （要有录制轨迹）、PASS/CHANGED 语义；变异动词另声明使用要求（见契约 7）。全文见
   `McpTools` 常量，golden 测试钉名字集与顺序、inputSchema 可解析为 type:object、变异动词
   description 含治理写声明。参数值类型与 inputSchema 不符时（如 task 传数字）按该参数
   缺省处理——类型契约由 schema 声明、校验归客户端侧，服务端不回类型错误。【测试钉】manifest 组
6. **读动词 ci 语义（零治理写）**：check/diff/re-drive 适配 replay --ci——不自动建档；
   缩域内存在未建档调用点时拒绝判定（E-GUARD 包络 + 指向 establish 的 nextAction）；
   漂移身份不收编。理由：MCP 读动词必须与 CLI 读动词同等「只读」，治理写只能经显式变异
   动词（establish/accept/reject）发生。report/verify/doctor/graph 本就是只读命令，直调。
   注意：拒绝与失败包络的 nextAction/hints 以 CLI 命令形书写（如 `agentassert4j
   baseline`）——工具动词与 CLI 命令同词（baseline→establish），AI 按 initialize
   instructions 的映射消费，MCP 层不改写包络文本。【测试钉】工具组（未建档拒绝且不落
   画像 + 建档后通过）
7. **变异动词使用要求与 agent 身份申报**：establish/accept/reject 的 description 声明
   「治理写，应在人类指示后调用；agent 以 approver="agent:<name>" 申报身份」。授权确认由
   harness 权限系统执行（MCP 原生同意点）；框架不校验 approver 值，事后经 CLI `audit`
   回溯 agent 申报的治理写（governance.md「agent 治理与审计」节为权威表述）。
   【测试钉】manifest 组（description 含治理写声明）
8. **record 摄取（幂等，三协议 wire 方言）**：入参 sessionId（必填）、request/response（必填，
   原始请求/响应 JSON 文本）、protocol（可选，封闭词表 `openai-chat`/`anthropic-messages`/
   `openai-responses`，词表单源=LlmWireProtocol 枚举——显式声明优先；缺省按响应形态自动
   识别：choices → openai-chat，stop_reason 或顶层 content 块数组 → anthropic-messages，
   output 数组或 status → openai-responses，全部不中 → E-USAGE 列三候选）、invocation
   （声明标签）、taskKey（任务键声明）、recordId（幂等键）、timestamp/latencyMs（遥测
   可选）、metadata（JSON 对象文本）。三协议的映射矩阵与 finish/usage 归一表的权威表述
   见 recording.md「wire 方言归一」节（归一器与重放客户端共用，同一方言不得有两套词表）；
   摄取产出的落库记录恒为 OpenAI chat 范式形（工具定义转范式嵌套形、图像转 data-URI），
   apiProtocol 列记录实际摄取方言。**幂等三层**：caller recordId > response.id >
   sha256(sessionId+invocation+turnIndex+requestRaw+responseRaw) 内容哈希；存储层
   INSERT OR IGNORE 天然去重，`saveInteractionIfAbsent` 回告 saved/duplicate，报告
   schema=agentassert4j.record/1（含 `protocol` 字段回显实际采用的方言——含自动识别
   结果，误判当场可见，重发显式 protocol 即可）。taskKey 落 metadata 的 `taskKey` 字段
   （TaskChainView.DECLARED_TASK_KEY 契约，声明优先于派生）。身份派生走与录制管道同一
   enrich 顺序：哈希投影先行、后键派生（wire 摄取无骨架，未声明时锚到 template/adhoc）。
   不可转换的 part（历史轮图像、无 base64 源的图像块）宁缺勿非法——丢弃并经工具结果
   stderr 可见告警。入参语义校验失败（必填缺失、request/response/metadata 非法 JSON、
   protocol 非法值、无法识别）→ isError + E-USAGE 包络。【测试钉】`McpServerTest` record 组
   （chat 全链）+ `McpRecordIngestionTest`（三协议矩阵/归一表全值/自动识别/敌对）
9. **结果双形态与证据预算**：`content` = 文本块（命令 stdout 的 schema 标签报告行原样，
   预算 100 000 字符，超出截断并标注——AI 可缩域重取）；`structuredContent` = JSON 对象
   （2025-11-25 方言要求对象形）：成功 = `{"reports":[<逐行解析的报告对象>]}`
   （仅收可解析为对象的行），执行错误 = error/1 包络对象。规范建议「返回 structuredContent
   的工具应同时在文本块携带序列化 JSON」——文本块即报告行本体，天然满足。不声明
   outputSchema（报告 schema 的演化归 cli.md 契约，不为 12 个工具维护双份 schema 税）。
   【测试钉】工具组
10. **stdout 纯净性与传输纪律**：server 的 stdout 只写协议消息（单行 JSON + `\n`，UTF-8，
    每消息 flush）；诊断/日志只走 stderr（`--diag` 开关时逐消息记 method+耗时）。工具执行
    的任何输出经命令实例捕获流隔离，全主码无 System.out 直写（审计事实）。行预算
    2 000 万字符（record 携带 base64 多模态可达 MB 级），超限行排空至换行后回
    -32600 类 framing 错误。CRLF 行尾容忍（trim \r）；空行跳过不回应；stdin EOF 或读写
    断管 → 静默退出 exit 0。【测试钉】传输组
11. **串行执行**：工具在同一读循环线程串行执行（SQLite 单连接语义 + 判定一致性）；长任务
    （re-drive 分钟级）期间后续请求排队——re-drive 的 description 如实声明耗时与预算建议，
    客户端据此设超时。无服务端会话状态、无服务器发起的请求。

## 客户端接入配方（stdio）

```json
{
  "mcpServers": {
    "agentassert4j": {
      "command": "java",
      "args": ["-jar", "/path/to/agentassert4j-standalone.jar", "mcp", "--db", "/path/to/agentassert4j.db"]
    }
  }
}
```

- 工作目录建议指向项目根（`agentassert4j.json` 的隐式查找链 cwd → home → classpath）；
  `--db` 显式绑定库文件，未给时走配置 storage.url。
- `--diag` 开关：逐消息向 stderr 记 method/id/耗时（排障用；默认静默）。
- Claude Code：`claude mcp add agentassert4j -- java -jar … mcp --db …`；
  ZCode/OpenCode 等同构（stdio 客户端只需拉起子进程 + 读写管道）。

## 域间边界

- 工具适配层不复刻命令逻辑：参数注入 → 命令类 call() → 捕获流/退出码。命令语义变更
  自动生效；适配层只维护「工具参数 ↔ 命令字段」映射，映射变更须同步 manifest 与 golden 测试。
- record 摄取的字段映射契约与 `SpringAiRecordMapper` 同源演化：映射语义变更 = 跨源可比性
  变更，须同步 sdk spec（recording.md）与本 spec。
- 协议面的演化纪律：新增 MCP 方法/字段前先读规范 changelog diff（维护债四道防火墙的
  第四道）；握手方言 → 2026-07-28 方言的迁移（server/discover 等）凭通道 2 实测证据立项。

## 变更纪律

- 工具名集合、协议版本钉点、错误分类法、record 报告 schema（agentassert4j.record/1）
  均为发布面契约，变更走显式评审。
- 工具面与 CLI 命令面的动词同词约束（§12.9 术语同形）在两通道间维持，不得分叉。

## 复核台账

- 2026-09-08 成文（MCP 批）：五方法面、12 工具、错误分类法（含 unknown tool -32602
  规范修正——规划原文与规范冲突，判规范侧成立并就地收窄）、record 幂等三层、ci 语义
  读动词、双形态结果、stdout 纯净性。实现与测试同批交付，通道 2 双宿主实测为 1.0.0
  验收标准（本 spec 契约 6/7 的最终裁判）。
- 2026-09-09 三协议批①：契约 8 由「OpenAI 方言 only、多协议后续批」改写为三协议摄取
  （protocol 参数 + 自动识别 + protocol 字段回显）；映射矩阵与归一表移 recording.md
  「wire 方言归一」节单源承载。归一器实现与测试同批交付（McpRecordIngestionTest）。
