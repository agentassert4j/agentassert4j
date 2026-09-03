# SDK 与接入面规格（sdk）

> 最近复核：364801f / 2026-09-03 · S6 成文（会话内对照两代 starter 自动装配 / RecordingChatModel /
> RecordingContext / 工具观察装饰实现逐项对账，辅以导读第 2 章既有叙事）
> 验证三档占比：【测试钉】8 条 ·【命令可证】0 条 ·【人工对账】2 条

## 职责与边界

**管**：两代 Spring AI 适配（RecordingChatModel 装饰、RecordingContext 声明作用域、工具观察
装饰）、两代 starter 自动装配与条件退出、编译兼容地板、`recorder_version`/`api_protocol`/`provider`
落库标记。

**不管**：录制管道语义（recording）、身份文法（identity——装饰器只透传声明位）、判定与治理、
JDK8 手动接入方的记录构造（OPERATIONS 最小录制契约承载，非本域代码）。

## 真源与派生

| 语义状态 | 真源 | 派生链 |
|---|---|---|
| 交互记录 | 容器内 `ChatModel` 的真实调用 | `RecordingChatModel` 计时捕获上下文 → 透传调用 → `SpringAiRecordMapper` 映射为 InteractionRecord → `recorder.intercept()` |
| 声明位 | `RecordingContext` 栈式 ThreadLocal（声明线程可见） | `withInvocationId/withTemplateId/withMetadata` 随请求合并进记录 |
| 工具编排事实 | 内部工具回路的真实执行 | 观察装饰器把每轮名称/参数原文/结果原文按序记入缓冲，合并进该条记录的 toolCalls |
| `recorder_version` | SDK 构件标识串 | 每条记录携带，写入 interactions 列 |
| `provider` | 模型名前缀启发 | deepseek→deepseek、gpt/o1/o3/o4→openai、claude→anthropic、qwen/qwq→qwen、gemini→gemini、llama→ollama、其余 custom |

## 状态机与生命周期

本域无状态机。Bean 生命周期：自动装配产出三 Bean（StorageRepository `close` / InteractionRecorder
`stop` / static BeanPostProcessor），Spring destroy 链保证存储关停晚于录制器 stop（录制器先 flush
剩余再关管道）。`enabled=false` 或 classpath 无 `ChatModel` 时自动装配整体退出，不建任何 Bean。

## 契约

1. **零业务改动接入**：`RecordingChatModel` 以装饰器包住容器内所有 `ChatModel`（BeanPostProcessor，
   已包装的不重复包装；static 注册避免容器启动顺序告警，recorder 经 `ObjectProvider` 延迟解析）。
   【测试钉】`AgentAssert4jAutoConfigurationTest`（包装生效/不双重包装）
2. **条件退出**：classpath 无 `ChatModel` 静默退出；`agentassert4j.enabled=false` 时整体退出、
   录制 API no-op（生产打包形态，库文件不产生）。【测试钉】`AgentAssert4jAutoConfigurationTest`
   （enabled=false 退出/无 spring-ai 静默退出）
3. **用户 Bean 让位**：用户自备 `StorageRepository`/`InteractionRecorder` 时
   `@ConditionalOnMissingBean` 让位；自带录制器需自行 `start()` 并显式设 destroy 方法名为 `stop`
   （Spring destroy 推断只认 close/shutdown，否则 Windows 上关停后 flush 线程锁住库文件）。
   【测试钉】`AgentAssert4jAutoConfigurationTest`（用户 Bean 优先）
4. **启动期失败语义（有意决策）**：存储初始化失败中断宿主启动——「录制静默失效（用户以为在录
   实际没录）」比启动失败更危险；不接受此语义的环境用 `enabled=false` 显式关闭。【人工对账】
   设计决策（装配测试覆盖正常路径，失败中断由 Spring 装配语义天然保证）
5. **流式与聚合**：`stream()` 在**调用线程**捕获 RecordingContext 闭包（聚合回调发生在异步完成
   信号线程，ThreadLocal 不可达），`MessageAggregator` 聚合完整响应后录制，TTFT 取首个 chunk；
   `publishOn` 切线程后仍能取到闭包捕获的上下文。【测试钉】`RecordingChatModelTest`（异步上下文
   传播）
6. **录制失败不伤业务**：录制侧任何异常只 WARN 不抛——业务调用永远不被录制问题打断。【测试钉】
   `RecordingChatModelTest`
7. **工具观察装饰**：在请求 options 副本上为每个工具回调换装纯观察装饰器——内部回路每次真实执行
   工具的同一时刻，名称/参数原文/结果原文按序入缓冲并合并进记录（参数经 RecursiveJsonParser 解析
   后按 ArgTypeUtil 同词表派生类型，与 native 路径可比）；100% 委托透传、装饰失败静默退回原请求、
   业务对象零触碰。1.x 覆盖默认内部执行姿势；ChatClient 驱动的逐轮姿势响应自带 toolCalls，观察
   缓冲自动让位不双计。覆盖面诚实边界：注入 options 的回调可见，未经 options 的私有执行通路
   不可见。【测试钉】`RecordingChatModelTest`（观察与不双计）
8. **两代隔离与兼容地板**：包名各自隔离（`springai1`/`springai2`、`springboot`/`springboot4`，
   两代坐标同名互斥必分模块）；适配模块编译基线 = 声称支持的最老 GA patch（spring-ai1=1.0.0 /
   spring-ai2=2.0.0），M/RC 不支持；2.0.0 API 断代处（旋钮移除等）在各自模块内分叉实现，不做
   运行时版本嗅探转发。【人工对账】地板由 javap 符号快照核对（兼容基线契约）
9. **落库标记**：`recorder_version` 写 SDK 构件串、`api_protocol` 固定 `openai-chat`（描述落库
   数据的协议形状而非上游供应商）、`provider` 前缀启发推断。【测试钉】`SpringAiRecordMapperTest`
10. **配置面最小开放**：starter 属性仅 `enabled`/`database`/`invocationId` 三项——配置项是发布后
    的永久契约，按需最小开放。【测试钉】`AgentAssert4jAutoConfigurationTest`（自定义路径建库等）

## 行为矩阵

| 场景 | 行为 |
|---|---|
| classpath 无 ChatModel | 自动装配静默退出，不建 Bean |
| enabled=false | 整体退出；录制 API no-op；库文件不产生 |
| 存储初始化失败 | 中断宿主启动（有意决策） |
| 用户自带 StorageRepository/Recorder | @ConditionalOnMissingBean 让位 |
| 容器内 ChatModel 已被包装 | 不重复包装 |
| RecordingContext 嵌套 | 栈式恢复（close 回到外层） |
| Reactor 异步线程发起调用 | 上下文须在发起 stream() 的线程捕获（ThreadLocal 不可达处取不到） |
| 观察装饰失败 | 静默退回原请求（业务零感知） |
| ChatClient 逐轮姿势（响应自带 toolCalls） | 观察缓冲让位，不双计 |
| 1.x 默认姿势（ChatModel 内部完整工具回路） | 一次 call = 完整回合，工具轮由观察装饰逐轮可见 |

## 域间边界

- **下游 recording**：mapper 产出的记录经 `recorder.intercept()` 进入管道；装饰器不感知管道内部。
- **上游 identity**：声明位（invocationId/templateId/metadata.taskKey）原样透传，键派生归 identity。
- **上游 spring**：Boot 两代自动装配结构同构，属性前缀 `agentassert4j`；配置查找链归 cli/config
  域（starter 只消费 `database` 属性直连）。

## 变更纪律

- starter 配置项（enabled/database/invocationId）= 发布后永久契约，新增按需最小开放。
- 编译地板（spring-ai1=1.0.0 / spring-ai2=2.0.0）= 冻结契约；跨大版本不做运行时嗅探转发，
  一条大版本线一个模块（AGENTS.md 既定结论）。
- 工具观察装饰的覆盖面表述（可见/不可见边界）必须诚实保留——夸大覆盖即误导用户。

## 复核台账

| 日期 | 方式 | 发现 |
|---|---|---|
| 2026-09-03 | S6 成文：两代 starter/RecordingChatModel/RecordingContext/观察装饰对账（辅以导读第 2 章既有叙事与测试清单核实） | ①启动失败中断语义（契约 4）为设计决策、装配测试覆盖正常路径——失败路径由 Spring 装配语义天然保证，无独立测试钉（诚实标注人工对账）；②JDK8 手动接入方的记录构造契约由 OPERATIONS 最小录制契约承载，非本域代码——本域只承诺 intercept 入口与 core 零依赖；③流式聚合 TTFT 取首 chunk 的实现事实在导读第 2 章，未单独测试钉（随 RecordingChatModelTest 整体覆盖） |
