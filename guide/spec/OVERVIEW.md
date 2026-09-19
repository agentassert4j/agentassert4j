# AgentAssert 现状规格 · 骨架总览（OVERVIEW）

> 最近复核：364801f / 2026-09-03 · S0 成文（会话内对照仓库结构与模块 POM 核对）
> 验证三档占比：【测试钉】0 条 ·【命令可证】0 条 ·【人工对账】3 条——本页是地图不是契约承载页，
> 全部硬契约在分域 spec；本页声明均可由仓库结构与模块 POM 直接核对。

## 职责与边界

- 本文是 `guide/spec/` 现状规格系统的入口：模块地图、分层铁律、数据主链路、域索引。
- 本文不承载任何域的细节语义——细节在分域 spec；方法签名与调用图不写入 spec，指向代码（grep 可得）。
- 「新代理可 1:1 复刻」的三档精度：**语义 1:1**（分域 spec 的契约，验证档位钉住）、**结构 1:1**
  （本文 + AGENTS.md 的分层与铁律）、**细节指向代码**。逐行级复刻手册是反目标——那是一份
  Markdown 方言的第二实现，每次变更双真源手工对齐，必然重蹈注释失真。

## 项目术语表（正式术语登记处）

按 AGENTS「术语与措辞规范」升格为正式术语的词在此登记一句话定义；全库注释与文档同词使用，清理历史措辞时按本表批量替换。除此表与 AGENTS 白名单（六个「面」词）之外的比喻与行话壳为禁写项。

| 术语 | 定义 |
|---|---|
| 落库 | 写入 SQLite 存储（交互记录、画像、候选、治理事件的统称）。 |
| 漂移（drift） | 已认可行为与新录制的真实行为之间的偏离；模板身份偏离称模板漂移。 |
| 裂键 | 同一调用点标签因提示词模板变更分裂出的多个调用点键（label-split 的键级后果）。 |
| 摄取（ingest） | 把一次已完成 LLM 交互的原始 wire 报文解析并写入存储（MCP record 动词）。 |
| 锚点（anchor） | 调用点身份的判定依据：声明标签 > 骨架哈希 > 模板哈希 > 请求锚。 |
| 门面（facade） | Facade 模式的标准译名；指聚合多个子接口的组装根类型（如 StorageRepository）。 |
| 画像（profile） | 调用点的档案：活跃基线指纹集合、版本标签、审批链与归档历史的载体（对应 InvocationProfile）。 |
| 播种（seed） | 为尚无基线的调用点建立首个基线（取桶内规范序最新记录为种子）。 |
| 骨架（skeleton） | 动态模板中动态段替换为稳定占位符后的模板形态（templateSkeleton），骨架哈希是其锚点。 |
| 就近可见 | 契约违约与故障在离问题最近的位置显式暴露给调用方或用户，不做静默吞掉。 |

## 模块地图与分层铁律

Maven reactor 共 13 个构建节点、产出 11 个 jar（聚合 POM 不产出构件）：

```
Layer 4   starter-spring-ai1          starter-spring-ai2     starter-langchain4j
               │ 聚合 core+recorder+         │ 聚合 core+recorder+      │ 聚合 core+recorder+
               │ spring-ai1+storage-sqlite      │ spring-ai2+storage-sqlite   │ langchain4j+storage-sqlite
Layer 3   spring-ai1            spring-ai2            langchain4j（纯程序化零 Spring）
          cli（组合根：core+recorder+storage-sqlite+picocli，默认后端随行）
          cli-standalone（cli 的 shade 打包运行面，java -jar 单文件可跑，随 GitHub Releases 分发）
               │
Layer 2   recorder（core + Disruptor + SLF4J API）                （异步旁路管道）
               │
Layer 1   core（纯 java.base，零外部依赖）                          （模型/SPI/算法/判定）
          storage-sqlite（只依赖 core + SQLite JDBC，独立插件分支）   （唯一存储后端）
```

铁律：**依赖单向，下层不感知上层**——core 不 import 任何 recorder/sdk/storage/cli 的类；
core 内出现任何 `com.*`/`org.*`（非 JDK）import 都是缺陷。core 内部六包：`model/`（数据模型）、
`spi/`（全部 SPI 接口与专用异常）、`algorithm/`（纯算法）、`result/`（判定结果模型）、
`util/`（RecursiveJsonParser 是全框架唯一的 JSON 解析/序列化真源）、`config/`（配置加载）。

【人工对账】分层与零依赖可机械核对：各模块 POM 的 dependencies 逐层递增；
`grep -rn "^import \(com\|org\)\." agentassert4j-core/src/main/java/` 输出必须为空（AGENTS.md 硬门槛）。

## 数据主链路

```
业务线程: chatClient.call() ──→ SDK 观察装饰器映射为 InteractionRecord
        │（业务调用照常返回，零侵入 R8）        │
        ↓                                     ↓
  录制管道: 脱敏 → 兜底 → RingBuffer 入队（满则丢弃计数，不阻塞生产者）
        → 消费线程缓冲 →（满批/定时）→ enrich 补派生字段 → SQLite interactions 落库
        → CLI 判定面: status 画像巡检 / baseline 建档 / replay 变更检测与对齐 / verify 验收
        → 差异落候选 → accept/reject 人工裁决 → 基线转正/作废，旧基线按模板版本归档
```

派生事实不建实体表是全库统一哲学：任务链从 `interactions` 现场派生（TaskChainView），
值溯源图不经任何实体表——`graph show` 每次从交互数据现场重建，验收包是一个 JSON 文件。

【人工对账】主链路各环节的分域规格见下方域索引；录制/存储/判定/治理各域的真源链在其 spec 内钉死。

## 域索引

| spec 文件 | 覆盖 | 状态 |
|---|---|---|
| `OVERVIEW.md` | 骨架总览（本文） | 成文 |
| `identity.md` | 身份与分组：键文法四锚点/三分形态/骨架/黄金键 | 成文 |
| `storage.md` | 存储：schema 列集/真源链/prompt_texts/归档/版本纪律 | 成文 |
| `judgment.md` | 判定与指纹：四维/comparator/rules/语义版本 | 成文 |
| `governance.md` | 治理生命周期：基线三态/候选/裁决/归档/漂移状态机 | 成文 |
| `recording.md` | 录制管道：Disruptor/背压/脱敏/enrich/采集门 | 成文 |
| `replay.md` | 重放与对齐：三层判定模型/检测器/对齐器/预算/退出码 | 成文 |
| `sdk.md` | SDK/starter：两代观察装饰/LangChain4j 单点装饰/链式半重放/兼容地板 | 成文 |
| `cli.md` | CLI 面：命令/参数/选择器/报告 schema/退出码 | 成文 |

导读 Part II 各章是本规格系统的 bootstrap 原材料：对应域 spec 成文后，该章瘦身为指向 spec
的地图，不再双写事实（防双真源）。

## 变更纪律

- 现状规格是 tracked 基准文档：触碰某域的代码变更须同批更新对应 spec 节（并入代码批 DoD）。
- spec 与代码冲突：按「注释不作证据」铁律处置——以代码为准，显式修败方（修 spec 或修代码），
  在该 spec 复核台账留痕。
- 新增公开行为先补 spec 再补码。
- 开发期版本标识（判定语义版本、报告 schema 版本、user_version）恒定不 bump，语义变更以
  删库重建承接；递增机制发布后才生效。

## 复核台账

| 日期 | 方式 | 发现 |
|---|---|---|
| 2026-09-03 | S0 成文：对照根 POM、Schema.java、core 包列表、导读第 1 章 | 导读第 1 章与仓库现状一致；第 9 章标题「重放执行（调用点域）」在 CLI 统一引擎落地后将失真，已随域索引标注 replay.md 先写即旧 |
