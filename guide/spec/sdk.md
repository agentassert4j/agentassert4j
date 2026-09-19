# SDK 与接入面规格（sdk）

> 最近复核：364801f / 2026-09-03 · S6 成文（会话内对照两代 starter 自动装配 / RecordingChatModel /
> RecordingContext（单源 recorder 层）/ 工具观察装饰实现逐项对账，辅以导读第 2 章既有叙事）
> 验证三档占比：【测试钉】8 条 ·【命令可证】0 条 ·【人工对账】2 条

## 职责与边界

**管**：两代 Spring AI 适配（RecordingChatModel 装饰、RecordingContext（recorder 层单源）声明作用域、工具观察
装饰）、两代 starter 自动装配与条件退出、编译兼容地板、`recorder_version`/`api_protocol`/`provider`
落库标记。

**不管**：录制管道语义（recording）、身份文法（identity——装饰器只透传声明位）、判定与治理、
JDK8 手动接入方的记录构造（OPERATIONS 最小录制契约承载，非本域代码）。

## 真源与派生

| 语义状态 | 真源 | 派生链 |
|---|---|---|
| 交互记录 | 容器内 `ChatModel` 的真实调用 | `RecordingChatModel` 计时捕获上下文 → 透传调用 → `SpringAiRecordMapper` 映射为 InteractionRecord → `recorder.intercept()` |
| 声明位 | `RecordingContext` 栈式 ThreadLocal（声明线程可见） | `withInvocationId/withTemplateId/withTemplateSkeleton/withEndpoint/withMetadata` 随请求合并进记录 |
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
9. **落库标记与 wire 词形单源**：`recorder_version` 写 SDK 构件串、`api_protocol` 固定
   `openai-chat`（描述落库数据协议形状而非上游供应商）、`provider` 前缀启发推断；采样参数与
   工具定义 function 信封的 wire 键词/形状经 core `OpenAiWireUtil` 单源（含 JSON-Schema 键词，
   供类型化 schema 转换器共用），各 mapper 不再手写键名字面量。【测试钉】`SpringAiRecordMapperTest`
   + `OpenAiWireUtilTest`
10. **配置面最小开放**：starter 属性仅 `enabled`/`database`/`invocationId` 三项——配置项是发布后
    的永久契约，按需最小开放。【测试钉】`AgentAssert4jAutoConfigurationTest`（自定义路径建库等）
11. **LangChain4j 线（`langchain4j` + `starter-langchain4j`）**：拦截点钉在
    `ChatModel.doChat` 单点——`chat(ChatRequest)` 与带选项重载在 1.0.0/1.18.0 均经默认模板汇聚
    到 `this.doChat`（地板方法体经字节码核实），单点覆写覆盖全部入口且只在地板已存在符号上落笔；
    doChat 内委托 `delegate.chat`（非规范实现照常工作、内模型 listener 恰触发一次——装饰器
    `listeners()` 返回空清单是双触发防线）。两类装饰器分流：`RecordingChatModel`（阻塞接口 Bean，
    混合实现两接口者一次收全）+ `RecordingStreamingChatModel`（仅流式接口 Bean，OpenAiStreamingChatModel
    一类形状）。流式 handler 用动态 Proxy 全量转发——富回调是接口 default 方法且地板编译期覆写不到，
    普通包装类会静默丢用户回调（1.18 模板包装器已核实覆写全部富回调，转发链两端成立）；代理接口集
    动态纳入用户 handler 全部实现接口。采集形状恒为逐轮成记录（回路编排在 AiServices 层）：发起帧轮
    响应带 toolCalls 无结果，结果出现在下一轮请求历史的 tool 角色轮次；userInput 取末位消息规则
    （末位非用户消息时置 null、user 文本进 previousTurns——与 Spring AI 面同形）。工具结果方言归一
    经 core `ToolResultNormalizer` 单源（与 Spring AI 面共用）。模块纯净：langchain4j 零 Spring
    import（原生程序化接入可用），langchain4j-core 为 provided。混架共存：与 Spring AI starter 同
    前缀同语义，`@ConditionalOnMissingBean` 共用同一录制器/存储，两个后置处理器按各自框架类型互不
    误包。编译地板 langchain4j=1.0.0（starter 编译测试钉天花板 1.18.0——富回调转发等新增形状的
    验证面）。【测试钉】`LangChain4jRecordMapperTest`（映射契约/方言/形状防御）、
    `RecordingChatModelTest`/`RecordingStreamingChatModelTest`（透传/单次 listener/流式 Proxy）、
    langchain4j.springboot.`AgentAssert4jAutoConfigurationTest`（双类型包装/退出/共存/天花板富回调/
    真管道落库）、`LangChain4jToolLoopE2eTest`（真机全链：AiServices 回路→链末判定 PASS→值溯源
    HIGH 边；手动回路帧/结果跨记录）

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
| LangChain4j blocking Bean（含混合实现两接口） | 包为 RecordingChatModel，流式入口在 delegate 实现流式接口时照常录制 |
| LangChain4j streaming-only Bean | 包为 RecordingStreamingChatModel（不漏包） |
| LangChain4j 非流式 delegate 走流式入口 | 显式 UnsupportedOperationException（不静默） |
| LangChain4j 工具结果多元素/非文本形状（新版 text() 抛错） | 反射读 contents() 逐元素取文本兜底，不中断录制 |
| 混架（Spring AI + LangChain4j 同容器） | 共用同一录制器/存储，各自装饰器各包各的，计数闭合 |

## 域间边界

- **下游 recording**：mapper 产出的记录经 `recorder.intercept()` 进入管道；装饰器不感知管道内部。
- **上游 identity**：声明位（invocationId/templateId/metadata.taskKey）原样透传，键派生归 identity。
- **上游 spring**：Boot 两代自动装配结构同构，属性前缀 `agentassert4j`；配置查找链归 cli/config
  域（starter 只消费 `database` 属性直连）。

## 变更纪律

- starter 配置项（enabled/database/invocationId）= 发布后永久契约，新增按需最小开放。
- 编译地板（spring-ai1=1.0.0 / spring-ai2=2.0.0 / langchain4j=1.0.0）= 冻结契约；跨大版本不做运行时嗅探转发，
  一条大版本线一个模块（AGENTS.md 既定结论）。langchain4j 线内消息形状有变更史
  （ToolExecutionResultMessage 从 String 改为内容列表）——新增 minor 发布时复跑地板↔天花板
  符号对照。
- 工具观察装饰的覆盖面表述（可见/不可见边界）必须诚实保留——夸大覆盖即误导用户。

## 复核台账

| 日期 | 方式 | 发现 |
|---|---|---|
| 待触发：langchain4j 官方模型 starter 发布稳定版 | 补「属性建模型被包」直接钉（测试引稳定版 starter 按属性装配，断言属性建的 ChatModel Bean 被 RecordingChatModel 包装） | 官方模型 starter 当前仅 beta 版本线，为结构性必然（BPP 对一切来源 Bean 生效）引 beta 耦合不划算——A3 接入格暂以推断+用户 Bean 钉承载，本行为闭合该星号的触发条件与验证方式 |
| 2026-09-18 | LangChain4j 适配批：两新模块（langchain4j + starter-langchain4j）成文对账 |
| 2026-09-18 | 质量收尾批（残留项处置） | ①wire 词形单源化：采样键×3 mapper/函数信封×3 mapper 的手写字面量收敛为 core OpenAiWireUtil（含 schema 键词常量），新增适配线只消费不再手写；②引用式 schema 补 $defs 映射（此前 $ref 悬空、重放时 provider 拒绝——延迟爆点修复+钉）；③流式录制改 finally（用户完成回调抛错不丢该次记录）+ 代理解包 InvocationTargetException（异常透明性）+ 首分片计时按方法名匹配（真机实证 1.18 走双参富重载致 ttft 丢失）；④深度截断记 WARN（就近可见）；⑤spring-ai1/ai2 历史工具轮补方言归一（三面 wire 统一，指纹不消费 previousTurns 故零基线影响）；⑥e2e 断言按调用点分组（模型多走一轮工具不假红）；⑦流式真机格落地（ttft/聚合/分片透传三断言） |
 ①单点装饰前提（chat 模板汇聚 doChat）经 1.0.0/1.18.0 字节码双向核实；②流式富回调转发链两端成立（1.18 模板包装器覆写全部富回调 × 代理全量转发）；③混架共存共用录制器有计数闭合钉；④F4b 形状防御（text() 抛错反射兜底）有地板子类钉+真机 L-B 覆盖正常路径；⑤逐轮形状的链末建档纪律（基线锚链末记录，先到先得建档下锚帧轮会与判定侧错配）在真机 L-A 实证并写入测试注释 |
| 2026-09-03 | S6 成文：两代 starter/RecordingChatModel/RecordingContext/观察装饰对账（辅以导读第 2 章既有叙事与测试清单核实） | ①启动失败中断语义（契约 4）为设计决策、装配测试覆盖正常路径——失败路径由 Spring 装配语义天然保证，无独立测试钉（诚实标注人工对账）；②JDK8 手动接入方的记录构造契约由 OPERATIONS 最小录制契约承载，非本域代码——本域只承诺 intercept 入口与 core 零依赖；③流式聚合 TTFT 取首 chunk 的实现事实在导读第 2 章，未单独测试钉（随 RecordingChatModelTest 整体覆盖） |
| 2026-09-19 | 冻结门全量巡检（doc-tools 三扫描器：零消费/同名双类型/逐字拷贝） | 零消费 0 命中；同名双类型全组均为设计内镜像（三 starter 配对线/三框架适配线/各模块测试桩夹具，包全限定名互异），零新增裁决面；新增单源化候选 1 条登记（记账不修，维护者裁决）：RecordingContext 在 langchain4j/spring-ai1/spring-ai2 三适配模块逐字镜像（78 窗口）——类本体零框架依赖（仅 java.base），可下沉 recorder 层单源，但属公开 API 模块间搬家（三包各持同名类是用户 import 面），列 1.0.x 池，pre-1.0 无外部消费者窗口内实施成本最低；现三份逐字相同零漂移，扫描器按特性批复跑兜底 |
| 2026-09-19 | 冻结门收敛批（维护者裁决采纳下沉方案） | 台账在册的 RecordingContext 三镜像单源化实施：类迁至 recorder 层（公开只读访问器 + metadata 只读视图），三适配模块删本地副本改 import（18 文件手术），LC4j 域 RecordingContextTest 随迁 recorder；spring-ai2 类头「不跨模块共享」设计注记随副本消亡，单源注记由 recorder 类头承载——「避免为单一工具类引入公共模块耦合」的原始前提在 recorder 本就是适配线公共下层后不再成立；pre-1.0 免费窗口完成，发布后再挪即破坏性变更。拷贝扫描器该组 78 窗口清零 |
