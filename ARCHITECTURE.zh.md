# 架构地图

> 面向贡献者的代码地图：模块如何分层、一次交互如何从 wire 走到判定、各 SPI 在哪、某类变更从
> 哪里下手。行为语义的权威来源是 `guide/spec/` 下的逐域契约（中文，相关处已链接）；本页记录
> 结构——结构比行为语义稳定得多。

## 模块分层

依赖单向——下层永不感知上层。core 出现任何非 JDK import 都是缺陷（CI 可 grep 验证）。

```text
L1  agentassert4j-core              model / spi / algorithm / result / util / config——仅 java.base
L2  agentassert4j-recorder          Disruptor 异步旁路管道（core + slf4j-api）
L3  agentassert4j-spring-ai1/-ai2   框架适配（工具回路观察装饰）
    agentassert4j-langchain4j       逐轮采集适配（零 Spring）
    agentassert4j-cli               组合根：baseline/status/replay/verify/audit/…
L4  agentassert4j-starter-*         Boot 自动装配（聚合 L1–L3 + 存储）
存储插件（独立，仅依赖 core）：
    agentassert4j-storage-sqlite    SQLite 持久化（v1 后端）
发行形态：
    agentassert4j-cli-standalone    全依赖 shaded 可执行 jar（slf4j-nop 单 provider）
```

## 一次交互的一生

```text
宿主应用内的 LLM 调用
  → 适配层捕获记录（结构化消息；wire 原文仅 MCP 摄取携带）
  → recorder（L2）：异步旁路 RingBuffer → 批量 → 存储写
      高压丢弃并记账（recorded = written + dropped + failed）；绝不阻塞业务线程
  → storage-sqlite：只追加 interactions 表、单文件、PRAGMA user_version = 1
  → 分析侧（CLI/MCP，独立进程，同一文件）：
      InvocationResolver   记录归组为调用点（声明标签 > 骨架 > 模板 > 请求锚），
                           链归组为任务（声明 taskKey > 请求文本）
      FingerprintExtractor 每次执行的四维结构指纹：工具调用 / 输出结构 /
                           内容规则 / 行为约束（后两维仅在钉入基线时参与）
      Comparator           最新执行 vs 该调用点的认可形态集合 → PASS / CHANGED（det-v1）
      对齐                 逐任务：两条真实链逐步配对 → missing / added / 逐步判定
      BaselineManager      establish / accept / reject / rollback + 治理事件时间线
```

关键语义锚点，全部确定性（判定路径永不引入 LLM）：

- **身份优先级**：声明标签 > 骨架哈希 > 模板哈希 > 请求锚。标签跨提示词编辑稳定；骨架定格
  动态模板；零声明归组在无任何声明时完整可用。
- **判定语义带版本**（`det-v1`）：任何改变「同样差异得出什么判定」的变更必须递增版本——
  旧基线永不被静默重解释。
- **幂等**：recordId 是全库去重键；写入只追加。

## core 包结构

| 包 | 职责 | 关键类型 |
|----|------|---------|
| `model` | wire 域值对象 | `InteractionRecord`、`ToolCall`（三态 `Boolean success`）、`InvocationProfile`、`DeterministicFingerprint` |
| `spi` | 扩展缝 | `StorageRepository`（组合七个角色 store：写/查/调用点/模板/归档/治理/健康）、`LlmClient`、`RecordingInterceptor`——单接口仍 ≤5 方法 |
| `algorithm` | 确定性引擎 | `InvocationResolver`、`FingerprintExtractor`、`DeterministicComparator`、`DriftDetector`、`BaselineManager`、`InMemoryDependencyGraph` |
| `result` | 判定/报告模型 | 对比结果、漂移/对齐报告、判定枚举 |
| `util` | 纯工具 | `HashUtil`、`RecursiveJsonParser`（深度封顶、退化安全）、`TextDiffUtils` |
| `config` | JSON 配置解析 | `agentassert4j.json` / 规则文件读取、安全默认值 |

## 按变更类型找入口

| 你想… | 从哪开始 | 域 spec |
|-------|---------|---------|
| 新增或修改 CLI 命令 | `agentassert4j-cli`（picocli 命令、JSON 报告、退出码） | `guide/spec/cli.md` |
| 新增内置行为检查 | core 判定管道（`rules` 目录）+ `rules` 命令 | `guide/spec/judgment.md` |
| 动指纹维度或判定语义 | `algorithm`（FingerprintExtractor、对比管道） | `guide/spec/judgment.md` |
| 动身份、归组或任务链 | `algorithm`（InvocationResolver） | `guide/spec/identity.md` |
| 动录制管道或捕获适配 | `agentassert4j-recorder`、L3 适配模块 | `guide/spec/recording.md` |
| 动基线治理（establish/accept/reject/rollback、audit） | `BaselineManager`、治理事件时间线 | `guide/spec/governance.md` |
| 新增框架适配 | 新 L3 模块；字段级先与既有适配 diff 对齐（见 AGENTS.md 平行面规则） | `guide/spec/sdk.md` |
| 动存储 schema | `agentassert4j-storage-sqlite`；schema 变更只增不改、版本守卫 | `guide/spec/storage.md` |
| 新增 MCP 工具 | `agentassert4j-cli` MCP 面（17 工具镜像 CLI 动词） | `guide/spec/mcp.md` |

适用于一切变更的仓规见 [AGENTS.md](AGENTS.md)——双语文档对、注释规范、三层审计、断言真源规则。
构建与测试机制见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 值得守护的不变式

- **core 永远零依赖**（仅 java.base）——发布承诺，机器可查。
- **分层永远单向**——下层不感知上层。
- **`det-v1` 永不静默变更**——判定语义变更是版本递增，不是重解释。
- **录制管道绝不阻塞业务线程**——丢弃并记账，绝不等待。
- **报告 schema 标签自出生冻结**（`task-report/1`、`error/1`……）——消费方依赖它们。
