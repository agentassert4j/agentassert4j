# 录制管道规格（recording）

> 最近复核：364801f / 2026-09-03 · S5 成文（会话内对照 InteractionRecorder / RecorderConfig /
> DataSanitizer / BatchWriteHandler 实现逐项对账）
> 验证三档占比：【测试钉】9 条 ·【命令可证】1 条 ·【人工对账】1 条

## 职责与边界

**管**：异步旁路录制管道（Disruptor 环形缓冲与批量落库）、采集门与默认声明、脱敏、计数闭合
账本、record_id/session_id 兜底、enrich 派生字段就绪顺序。

**不管**：捕获侧如何构造记录（sdk/接入方）、派生键文法（identity）、落库承载契约（storage）、
判定与治理。

## 真源与派生

| 语义状态 | 真源 | 携带方式 |
|---|---|---|
| 交互记录 | 应用真实 LLM 调用 | SDK mapper 产出 `InteractionRecord` → `recorder.intercept()` |
| 计数账本 | AtomicLong 六件：recorded/filtered/dropped(生产)/dropped(消费)/written/failed | 公开 getter 是诊断契约；闭包公式见契约 1 |
| seq | 录制进程内单调序号源 | 丢弃造成的空洞合法；(session_id, seq) 是下游确定性排序键 |
| 派生字段 | 记录自身（模板全文/骨架文本） | enrich 在落库前补 templateHash/skeletonHash 投影与 invocationKey，显式设置优先 |

## 状态机与生命周期

录制器生命周期：构造 → `start()`（启动 Disruptor + 定时 flush）→ `intercept()`（业务线程，纳秒级
入队）→ `stop()`（先 flush 剩余 → 停定时线程 → Disruptor 10 秒优雅关闭，超时强制）。`enabled=false`
时 `start()` 不启动任何管道，录制器整体退化为 no-op（生产打包形态）。`intercept` 与 `stop` 以实例
监视器互斥——无锁窗口内关停完成会把事件发布进已停摆的 RingBuffer，记录永久滞留且计数不闭合。

## 契约

1. **计数闭合**：`written + dropped(生产侧 RingBuffer 满/发布异常 + 消费侧缓冲超限) == recorded`；
   被采集门过滤的记录从未进入管道，不计入 recorded——总到达 = recorded + filtered。聚合口径
   `getDroppedCount()` = 生产侧 + 消费侧。【测试钉】`InteractionRecorderTest`（计数闭合断言）
2. **零侵入**：RingBuffer 满时 `tryNext` 丢弃不阻塞业务线程；批量写失败计 failed 不重试；录制
   侧任何故障不阻塞业务请求。【测试钉】`InteractionRecorderTest`（满缓冲丢弃）
3. **采集门**：默认全量录制（`recordUndeclaredChat=true`——任务链完整性优先，链条终点的最终回答
   往往正是纯文本调用）；false 时未声明（invocationId/templateId 均无）且无可见工具调用的纯对话
   被过滤——过滤是决策不是故障，独立计数；告警节律 = 首条一次 + 此后每满 100 条重申累计数。
   【测试钉】`InteractionRecorderTest`（全量默认/过滤计数/告警节律）
4. **默认声明先于采集门**：应用级 `defaultInvocationId` 只落给「未声明且无可见工具调用」的记录，
   声明锚点在身份优先级中高于模板哈希（单技能应用零声明成本）。【测试钉】`InteractionRecorderTest`
5. **enrich 就绪顺序**：哈希投影（templateHash/skeletonHash）**先于**键派生——顺序颠倒会让首条带
   模板文本的记录以不完整信息落锚成 adhoc；捕获侧显式设置不被覆盖；单条补全失败不拦截整批落库
   （原始交互数据是真源，派生字段缺失可事后重建）；invocationId 声明位永不写派生哈希。【测试钉】
   `BatchWriteHandlerTest`
6. **批写整批原子与失败语义**：批写事务、rollback 顺序、`StorageException` 显式抛出归 storage 域
   契约，录制侧以 failedCount 计账不重试。【测试钉】`SqliteStorageRepositoryTest`（storage 域）+
   `BatchWriteHandlerTest`（失败计数）
7. **脱敏**：敏感字段按 `SanitizeStrategy`（MASK=`***` / HASH=SHA-256 小写十六进制 / DROP=删除
   键值对）就地替换；`sanitizeUserInput`/`sanitizeModelResponse` 默认 false；工具调用参数树递归
   脱敏、结果按 JSON 字符串处理；脱敏在入队前完成（落库即已脱敏）。【测试钉】`DataSanitizerTest`
8. **record_id/session_id 兜底**：缺失 record_id 以 UUID 兜底（INSERT OR IGNORE 防重放语义依赖
   全局唯一）；缺失 session_id 退化为独立会话（每条自成一组）——保住录制不整批失败。【测试钉】
   `InteractionRecorderTest`
9. **stop 排空**：关停先 flush 剩余再停管道，10 秒超时强制；关停瞬间新到达记录按计数口径处理，
   不阻塞生产者。【测试钉】`InteractionRecorderTest`
10. **采集门过滤告警节律**：`getFilteredWarnEmissions()` 首条一次、每满 100 条重申一次——静默
    丢数据比丢数据本身更危险。【测试钉】`InteractionRecorderTest`（告警节律）
11. **故障排查计数面**：`stop()` 与各 getter 输出的六计数是用户可观察的诊断面（录了多少/滤了多少/
    丢了多少/写了多少/败了多少）。【命令可证】应用关闭日志的 recorded/filtered/dropped/written/
    failed 汇总行

## wire 方言归一（MCP record 摄取；重放客户端共用）

record 摄取把三协议 wire 报文归一为 **OpenAI chat 范式记录**（协议中立落库，跨协议重放的
比较在结构指纹层天然成立）；重放客户端（cli.llm 客户端族）反向执行同一合同。协议词表单源 =
`LlmWireProtocol` 枚举（`openai-chat`/`anthropic-messages`/`openai-responses`）——同一方言
不得有两套词表（finish/usage 归一器实现于 cli.llm 的方言工具类，摄取与客户端共用）。

**finish 归一表**（内部词表 stop/tool_calls/max_tokens/content_filter/other）：

| 内部值 | openai-chat | anthropic-messages | openai-responses（派生） |
|---|---|---|---|
| stop | stop | end_turn、stop_sequence | status=completed 且无 function_call |
| tool_calls | tool_calls、function_call | tool_use | output 含 function_call item（无论 status） |
| max_tokens | max_tokens、length | max_tokens | incomplete(reason=max_output_tokens) |
| content_filter | content_filter | refusal | incomplete(其他 reason) |
| other | 未知值 | 未知值 | failed 等其余 |

**usage 归一表**（inputTokens=总量口径 per-protocol 公式；求和缺项按 0 计；cacheRead/
cacheWrite/reasoning 字段缺失为 null——未知≠0；usageRaw=usage 子树原文 serialize）：

| 记录字段 | openai-chat | anthropic-messages | openai-responses |
|---|---|---|---|
| inputTokens | prompt_tokens（已是总量） | input_tokens + cache_creation_input_tokens + cache_read_input_tokens（非缓存口径求和） | input_tokens |
| outputTokens | completion_tokens | output_tokens | output_tokens |
| cacheReadTokens | prompt_tokens_details.cached_tokens | cache_read_input_tokens | input_tokens_details.cached_tokens |
| cacheWriteTokens | —（null） | cache_creation_input_tokens | —（null） |
| reasoningTokens | completion_tokens_details.reasoning_tokens | —（null） | output_tokens_details.reasoning_tokens |

**请求面映射矩阵**（wire 请求 → 范式记录字段）：

| 范式字段 | openai-chat | anthropic-messages | openai-responses |
|---|---|---|---|
| templateText | system/developer 消息（末者为准） | 顶层 system（字符串或 text 块拼接） | instructions；或 items 内 system/developer message |
| userInput + turnIndex | 末位 user 消息（content 数组按多模态落库） | 末位 user 消息；含 tool_result 块的末位 user 是链式中间态，按历史轮处理（turnIndex=下一轮计数，同 chat 末位 tool 帧语义） | input 字符串直为输入；或末位 user message item |
| 多模态（范式=OpenAI content 数组） | 原样（范式原生） | image 块 base64 source → data-URI 组装 | input_image 的 image_url 直通（url 与 data-URI 均合法） |
| previousTurns | 逐轮展开（tool 帧带 toolCallId；assistant 的 tool_calls 逐调用收编为发起帧——id/name/arguments 真值，与另两协议结构一致） | text 块聚合（空串直拼）+ tool_use 块→assistant 发起帧（id/name/**arguments 真值**）+ tool_result 块→tool 结果帧（名字由同请求内 id 配对回填） | message item→轮 + function_call→发起帧（**arguments 真值**）+ function_call_output→结果帧（名字回填）；reasoning item 跳过（思考痕迹非对话内容） |
| toolsDefinition（范式=OpenAI tools 嵌套形） | tools 原样（范式原生） | 扁平形 `{name,description,input_schema}` 转嵌套形 | 扁平形 `{type,name,description,parameters}` 转嵌套形 |
| samplingParams | temperature/top_p/top_k/max_tokens/frequency_penalty/presence_penalty/stop/seed | temperature/top_p/top_k/max_tokens/stop_sequences | temperature/top_p/max_output_tokens |

**响应面映射矩阵**（wire 响应 → 范式记录字段）：

| 范式字段 | openai-chat | anthropic-messages | openai-responses |
|---|---|---|---|
| modelResponse | choices[0].message.content | text 块空串直拼（纯 tool_use 响应为 null） | output 中 message item 的 output_text 拼接 |
| toolCalls（argTypes 同源派生、success 恒真） | message.tool_calls（arguments 字符串二次解析） | tool_use 块（input 对象直取） | function_call item（arguments 字符串二次解析） |
| servedModel / 幂等 response.id | 顶层 model / id | 同左 | 同左 |
| modelRequestRaw / modelResponseRaw | 原文双列（三方同规则——wire 摄取比 ChatModel 层捕获更富） | 同左 | 同左 |

发起帧 arguments 真值随轮次落库（TurnContext.toolArguments，previous_turns JSON 键）——
重放的「当时输入」重建依赖它。不可转换的 part（历史轮图像、无 base64 源/无 image_url 的
图像）宁缺勿非法：丢弃并经工具结果 stderr 可见告警；敌对形态（元素非对象、无 type、坏
JSON 参数）静默退化——对应字段 null/空，退化不中断。显式 protocol 声明优先于自动识别；
识别失败与非法值报 E-USAGE 列三候选（宁报错不静默错判）。【测试钉】`McpRecordIngestionTest`
（三协议矩阵/finish 与 usage 归一表全值参数化/自动识别与显式覆盖/敌对组）

## 行为矩阵

| 场景 | 行为 |
|---|---|
| enabled=false | start 为 no-op，intercept 直接返回，零管道零计数 |
| RingBuffer 满 | 丢弃 + 生产侧 dropped 计数 + WARN，业务线程零等待 |
| 定时/满批 flush 前进程停止 | stop 先 flush 剩余再关停 |
| 批量写失败 | failed 计数 + ERROR 日志，不重试（批次丢弃） |
| 未声明纯对话（默认门） | 正常录制（全量默认） |
| 未声明纯对话（recordUndeclaredChat=false） | 过滤 + 计数 + 告警节律 |
| 捕获侧已设 templateHash/skeletonHash/invocationKey | enrich 不覆盖 |
| 记录缺 record_id / session_id | UUID / 独立会话兜底 |
| enrich 单条异常 | 该记录以缺失派生字段落库，不拦截整批 |
| 脱敏策略 DROP | 字段值置 null（JSON 序列化时删除键值对） |

## 域间边界

- **上游 sdk/接入方**：intercept 是唯一入口（`RecordingInterceptor` SPI）；捕获侧构造契约见
  sdk 域与 OPERATIONS 最小录制契约。
- **下游 identity**：enrich 定格的 invocationKey/templateHash/skeletonHash 是读侧键消费的落库点；
  就绪顺序（哈希先于键）由本域钉死。
- **下游 storage**：`saveInteractions` 批写是唯一事务入口；模板原文随行归档由存储侧完成。

## 变更纪律

- 六计数口径与闭包公式 = 冻结契约（公开 getter 是诊断面，口径变更即用户可见破坏）。
- `recordUndeclaredChat` 默认值（true）为既定裁决，翻转须显式裁决；配置项是发布后的永久契约。
- 管道参数（batchSize/flushIntervalMs/ringBufferSize/maxBufferSize）为调优面，语义见 RecorderConfig。

## 复核台账

| 日期 | 方式 | 发现 |
|---|---|---|
| 2026-09-03 | S5 成文：InteractionRecorder/RecorderConfig/DataSanitizer/BatchWriteHandler 全量对账 + 测试指针核实 | ①filtered 与 dropped 分列是语义要求（过滤=决策、丢弃=故障），诊断时不得合并；②ConsumerDropped 与生产侧 dropped 分属不同线程域，聚合口径以 getDroppedCount() 为准；③record_id UUID 兜底处保留既有 TODO（SDK 接线前是最终设计位） |
| 2026-09-09 | 三协议全量审查批：chat 摄取收编 assistant tool_calls 发起帧（原历史近似「细节不入轮」废弃——三协议 previousTurns 结构就此一致，重放合成发起帧拿到真值而非空名占位）；发射协议改三级推导（配置显式>记录 apiProtocol 提示>openai-chat 兜底，LlmConfig.protocol 缺省改 null 表达未配置） | 审查发现两点：①chat 摄取对 assistant tool_calls 的忽略使三协议记录结构不对称；②llm.protocol 缺省恒 openai-chat 使未配置协议的 anthropic/responses 库重放打错方言——按「记录基线可自动推导」主张修复 |
| 2026-09-09 | 三协议批①新增「wire 方言归一」节：McpRecordIngestion 三协议映射矩阵 + finish/usage 归一表对码成文（归一器实现于 AnthropicMessagesWireFormat/OpenAiResponsesWireFormat，摄取与重放客户端共用单源） | ①anthropic input_tokens 是非缓存口径，总量须三项求和——chat 客户端旧 TODO 就此兑现于 anthropic 侧；②TurnContext.toolArguments 此前不随 previous_turns JSON 落库（历史轮无载体故无感），三协议摄取可存 arguments 真值，JsonMapper 读写两侧已补键（JSON 内部加键向后兼容，非 schema 列变更）；③空 messages 列表在旧摄取路径会 IndexOutOfBounds 崩溃（E-ENV 兜底），重构中就近修复为退化落库并有敌对钉 |
