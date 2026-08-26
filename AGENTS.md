# AGENTS.md — AgentAssert4j 开发规范

> **本文件是仓库级协作契约**，面向所有贡献者（人类开发者与 AI 编码代理）。ZCode、Codex 等主流 code agent
> 会在每次会话自动加载根目录的本文件；人类贡献者请在提交第一个 PR 前通读。本文件随代码入库，对所有克隆者生效。
>
> 本文件自包含：不依赖仓库外文档。与既有审计决策的关系——冲突时以本文件最新版本为准；对既定结论有新证据，先开 issue
> 提案，勿在任务中顺手推翻。

---

## 〇、项目定位与既定结论（防重开清单）

**AgentAssert4j** 是 JVM 原生的 AI Agent 行为断言测试框架：旁路录制真实 LLM 交互 → 确定性四维指纹与基线 → prompt
变更按依赖图裁剪影响集 → 真实调用重放判定回归。核心卖点：确定性、可复现、CI 可 gating、core 零依赖、单文件 SQLite 存储、内网离线可用。

以下结论已审计定案（2026-08-26），**任何会话与 PR 不得重开论证**；有新证据请先开 issue：

1. **图数据库永久不引入**（GPL 许可证一票否决 + 规模差 4 个数量级 + 摧毁零基础设施部署叙事）。图 = 纯内存邻接表 + SQLite 单行
   JSON 快照，图是可从 interactions 表全量重建的派生数据。
2. **embedding 语义模块永久不实现**。语义漂移的正解是场景层真实调用回归；确定性层掺语义 = 自毁「确定性」卖点。
3. **core 零外部依赖（仅 java.base）是发布卖点与合规优势**，不是待优化技术债。
4. **SQLite 是 v1 唯一存储后端**；mysql/pg 是双向门延迟项而非否决项，真实需求出现前不重建。
5. **判定语义 100% 确定性**（见 R9）——核心链路永不引入 LLM/概率模型做判定。

---

## 一、Maven 坐标与包名规范

| 概念                | 值                          | 说明                                              |
|-------------------|----------------------------|-------------------------------------------------|
| Maven groupId     | `io.github.agentassert4j`  | Maven Central 发布要求，用 GitHub 组织（agentassert4j）验证 |
| Java base package | `io.github.agentassert4j`  | Java 包名不允许 `-`，无需替代字符                           |
| 目录路径              | `io/github/agentassert4j/` | 与 base package 严格对应                             |
| artifactId 前缀     | `agentassert4j-`           | 所有模块统一前缀                                        |
| 版本                | `1.0.0-SNAPSHOT`           | 发布时改为正式版                                        |

**import 示例**：

```java
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.StorageRepository;
```

**Maven 依赖示例**：

```xml

<dependency>
    <groupId>io.github.agentassert4j</groupId>
    <artifactId>agentassert4j-core</artifactId>
</dependency>
```

---

## 二、模块组织架构（v1 裁剪后实际结构）

### 2.1 目录结构（同类聚合）

```
agentassert4j/
│
│  ── 核心模块 ──
├── agentassert4j-core/                        ← 零依赖心脏（java.base ONLY）
├── agentassert4j-recorder/                    ← Disruptor 异步录制
├── agentassert4j-cli/                         ← Picocli 命令行工具（待实现）
│
│  ── 存储插件（聚合在 agentassert4j-storage/ 下）──
├── agentassert4j-storage/                     ← 聚合 POM (packaging=pom)
│   └── agentassert4j-storage-sqlite/          ← core + SQLite JDBC（默认，v1 唯一后端）
│
│  ── 框架适配 SDK（聚合在 agentassert4j-sdk/ 下）──
└── agentassert4j-sdk/                         ← 聚合 POM (packaging=pom)
    ├── agentassert4j-sdk-spring/              ← core + recorder + Spring AI（待实现）
    └── agentassert4j-spring-boot-starter/     ← 聚合 core + sdk-spring + storage-sqlite + 自动装配（待实现）
```

> **裁剪说明**：曾存在的空壳模块（proxy / agent / dashboard / embedding / storage-mysql / storage-pg / sdk-lang / bom）已于
> v1 裁剪时物理删除。除 embedding 永久否决（§〇-2）外，其余均为**双向门延迟**——将来恢复时按本节聚合结构重建即可，**勿在 v1 提前重建空
pom**。

**聚合规则**：

- 同类模块收在聚合 POM 下（如 storage-* 收在 agentassert4j-storage/ 下）
- 聚合 POM 设 `<packaging>pom</packaging>`，parent 指向根 POM
- 子模块的 parent 仍指向根 POM，通过 `<relativePath>../../pom.xml</relativePath>` 定位
- 每个 artifactId 保持不变，Maven Central 发布不受影响
- 聚合 POM 不产出 JAR，仅用于目录归类和批量构建

### 2.2 模块分层与依赖方向（单向，上层依赖下层）

```
Layer 1: agentassert4j-core          ← 零外部依赖，纯 java.base
           │
Layer 2: agentassert4j-recorder      ← core + Disruptor + SLF4J API
           │
Layer 3: agentassert4j-cli           ← core + recorder + Picocli
         agentassert4j-sdk-spring    ← core + recorder + Spring AI
           │
Layer 4: agentassert4j-spring-boot-starter ← 聚合 core + sdk-spring + storage-sqlite + 自动装配

存储插件（独立，只依赖 core）：
  agentassert4j-storage-sqlite       ← core + SQLite JDBC（默认）
```

**铁律**：下层绝对不感知上层。core 不 import recorder/sdk/storage 的任何类。

### 2.3 agentassert4j-core 内部包结构

```
io.github.agentassert4j/
├── model/      核心数据模型（InteractionRecord, ToolCall, SkillProfile 等）
├── spi/        全部 SPI 接口（StorageRepository, LlmClient, RecordingInterceptor 等）
├── algorithm/  纯算法（SkillGrouper, FingerprintExtractor, Comparator 等）
├── result/     判定结果模型（ComparisonResult, Verdict, DiffClassification）
├── util/       纯 Java 工具（HashUtil, RecursiveJsonParser, TextDiffUtils）
└── config/     配置加载（agentassert4j.yml 解析）
```

---

## 三、核心设计原则（铁律）

| 编号  | 原则            | 说明                                | 禁止做法                                          |
|-----|---------------|-----------------------------------|-----------------------------------------------|
| R1  | **核心零依赖**     | agentassert4j-core 只能依赖 java.base | core 中 `import com.*` / `import org.*`（非 JDK） |
| R2  | **面向 SPI 编程** | 核心只定义接口，不 import 任何实现类            | `new SqliteStorageRepository()` 出现在 core      |
| R3  | **插件平等**      | 所有 SPI 实现地位平等，仅靠优先级区分             | 硬编码 `if (type == "sqlite")`                   |
| R4  | **配置驱动**      | 行为差异靠配置切换，不靠代码分支                  | 核心逻辑中判断 `storageType`                         |
| R5  | **单向依赖**      | 上层依赖下层，下层不感知上层                    | core 引用 cli/sdk 的类                            |
| R6  | **接口隔离**      | 每个 SPI 接口 ≤ 5 个方法                 | Repository 接口 20+ 个方法                         |
| R7  | **图在内存**      | 依赖图谱纯内存邻接表 + JSON 持久化             | 引入 Neo4j/图数据库（永久结论，见 §〇-1）                    |
| R8  | **零侵入**       | 框架不干预 Agent 正常运行                  | 录制失败阻塞业务请求                                    |
| R9  | **确定性优先**     | 核心判定逻辑 100% 确定性、可复现               | 核心链路依赖 LLM/概率模型                               |
| R10 | **退化不中断**     | 异常时退化到安全默认值，不抛异常                  | RecursiveJsonParser 失败抛异常中断流程                 |

---

## 四、错误处理三层策略

| 层级 | 范围                        | 策略                                               |
|----|---------------------------|--------------------------------------------------|
| L1 | core                      | 防御性校验：null 输入返回安全默认值，解析失败退化为空集合，从不中断流程           |
| L2 | recorder                  | 异步容错：RingBuffer 满则丢弃、批量写入失败记计数器不重试、DB 连接失败本地文件备份 |
| L3 | 接入层（sdk-spring / starter） | 连接健壮性：上游超时透传错误、非标准格式尽力提取、版本不兼容静默退出               |

**通用规则**：框架的任何故障都不应影响 Agent 主流程。宁可丢失录制数据，不可阻塞业务请求。

---

## 五、日志策略

| 模块                       | 日志框架                      | 级别规则                                            |
|--------------------------|---------------------------|-------------------------------------------------|
| agentassert4j-core       | java.util.logging（JDK 内置） | 只记 SEVERE（不可恢复错误），不记 INFO/WARNING               |
| 上层模块（recorder/cli/sdk-*） | SLF4J API（不绑定实现）          | INFO：启停/统计/摘要；DEBUG：交互细节/算法中间结果；ERROR：DB/API 失败 |

---

## 六、模型类编写规范

1. 所有模型类使用 POJO 风格：private 字段 + getter/setter，无业务逻辑
2. 工具类以 `Util` 结尾，构造方法 private（如 `TypeInferUtil`）
3. 枚举类以含义命名（如 `BaselineStatus`、`SkillType`），注释说明每个值的语义
4. 跨包引用必须显式 import（即使是同 base package 的子包）
5. 有工厂方法的模型（如 `AnalysisResult.noBaseline()`）放在 model 包

---

## 七、SPI 接口编写规范

1. 所有 SPI 接口定义在 `spi/` 包，实现在独立模块
2. 接口方法 ≤ 5 个（接口隔离原则，R6）
3. 异常用专用异常类（如 `LlmApiException`、`LlmTimeoutException`），不抛通用 Exception
4. 插件发现优先级：CLI 显式配置 > Spring Boot AutoConfig > ServiceLoader > 默认 SQLite

---

## 八、临时代码标注规范（强制）

所有尚未实现后续功能而写的硬编码、临时简化、空实现、占位内容，**必须**添加 TODO 注释。

**TODO 格式要求**：

```java
// TODO: [简述临时方案] 详细说明该处为临时实现，以及后续需由哪个具体功能模块或类来完善
```

**必须标注 TODO 的场景**：

1. 因后续模块未实现而写死的硬编码值（如 group_key 写空串，待 SkillGrouper 实现）
2. 因核心算法未完成而暂存的占位值（如 fingerprint 暂存 null，待 FingerprintExtractor 实现）
3. 因依赖组件未就绪而做的简化实现（如 JSON 序列化省略嵌套字段，待 RecursiveJsonParser 统一）
4. 与其他模块存在代码重复，后续需统一消除的技术债（如手写 JSON 解析待 RecursiveJsonParser 替代）
5. 违反设计原则但因阶段原因暂不处理的债务（如 R6 接口隔离违反，待评估拆分）

**禁止做法**：

- 禁止裸写硬编码值而没有任何注释说明
- 禁止 TODO 只写 "TODO: fix later" 等模糊描述，必须说明具体原因和后续完善方案

---

## 九、全限定类名使用规范（强制）

**规则**：除以下明确允许的场景外，**禁止**在代码中使用全限定类名（如 `java.util.Map`、`com.example.SomeClass`），必须通过
`import` 语句导入后使用简单类名。

**允许使用全限定类名的场景（白名单）**：

1. **反射**：`Class.forName("com.example.SomeClass")` 或 `com.example.SomeClass.class` 所在的动态加载场景
2. **配置类/Bean 注册**：Spring `@Bean` 方法返回类型、`@Configuration` 中的 Bean 定义等需要显式指定完整类路径的场景
3. **Bean 过滤/条件装配**：Spring `@ConditionalOnClass`、`AutoConfiguration` 过滤器等需要类名全路径的场景
4. **SPI 配置文件**：`META-INF/services/` 文件内容（必须写全限定名）
5. **同包内同名类消歧**：当两个 import 的类同名时（极少见），可用全限定名消歧

**禁止做法**：

```java
// 禁止：普通代码中使用全限定类名
private java.util.Map<String, String> map;
private java.util.List<String> list;
Map<String, String> types = new java.util.HashMap<>();

// 正确：通过 import 导入后使用简单类名
import java.util.Map;
import java.util.HashMap;
private Map<String, String> map;
Map<String, String> types = new HashMap<>();
```

---

## 十、分支模型与命名规范

采用 **GitHub Flow**（开源社区主流共识，匹配单人维护 + AI 代理协作 + 多贡献者 PR 的现实）：

### 10.1 分支模型

- `main` 是**唯一长期分支**，任何时点保持可构建、可发布（`mvn -B test` 全绿）。
- **禁止直接向 main 推送功能提交**；一切功能、修复、文档变更走短生命周期分支 + Pull
  Request。唯二例外（维护者直接提交）：文档错字修正、CI/构建脚本紧急修复。
- 分支从最新 main 切出；**一个分支只做一件事**；合并后删除分支；生命周期以天计，不养长期私有分支。
- PR 合并方式：squash 或 rebase 保持 main 线性历史（维护者裁量），合并前必须 CI 绿 + 通过评审。

> **生效时间**：分支与 PR 规范自 **V1 公开发布、main 设为保护分支之日起全面生效**。在此之前（预发布阶段）维护者直接在 main
> 上开发提交；提交信息规范（§十一）与代码质量规范（§十二）自即刻生效。

### 10.2 分支命名

格式：`<type>/<issue号-]<短描述>`，type 与提交类型（§十一）一致，短描述用小写 kebab-case 英文：

| type       | 用途    | 示例                                |
|------------|-------|-----------------------------------|
| `feat`     | 新功能   | `feat/replay-tools-schema`        |
| `fix`      | 缺陷修复  | `fix/12-ringbuffer-overflow`      |
| `docs`     | 文档    | `docs/agents-md`                  |
| `refactor` | 重构    | `refactor/split-storage-spi`      |
| `test`     | 测试补充  | `test/impact-analyzer-edge-cases` |
| `chore`    | 构建/杂务 | `chore/upgrade-surefire`          |
| `perf`     | 性能    | `perf/graph-bfs-cache`            |

有关联 issue 时把编号放进分支名（`fix/12-...`），并在 PR 描述用 `Closes #N` 自动关联。

---

## 十一、提交信息规范（Conventional Commits）

采用 [Conventional Commits 1.0.0](https://www.conventionalcommits.org/zh-hans/v1.0.0/)（开源社区主流共识；语义化、可自动生成
CHANGELOG）：

```
<type>(<scope>): <description>

[body]

[footer]
```

### 11.1 字段规则

- **type（必填，白名单）**：`feat` `fix` `docs` `style` `refactor` `perf` `test` `build` `ci` `chore` `revert`
- **scope（可选，受影响模块）**：`core` `recorder` `storage` `cli` `sdk` `starter` `build` `release`
- **description（必填）**：英文祈使句（"add" 不是 "added"/"adds"），建议 ≤ 72 字符，结尾不加句号
- **body（可选）**：说明**动机与实现要点**——为什么改，而不是改了什么（diff 自己会说话）；英文
- **footer（可选）**：
    - `Closes #N`：关联并自动关闭 issue
    - `BREAKING CHANGE: <说明>`：破坏性变更（同时 type 后加 `!`，如 `feat(core)!: ...`）
    - `Assisted-by: AI coding agent`：AI 代理参与生成的提交**必标**，固定取值，不写具体代理名称与邮箱（见 §十三-5）
- **语言**：提交信息一律英文（国际化开源共识；README/用户文档走中英双语，与此不冲突）

### 11.2 原子提交

一个提交一个意图。禁止在同一提交中混合功能变更、格式化重排、无关顺手修改。发现任务外问题时：记 TODO（§八）或开 issue，不顺手修。

### 11.3 示例

```
feat(core): carry recorded tool definitions in replay requests

Replay requests previously omitted the tools array captured at
recording time, making dimension-1 tool-set comparison always
mismatch. InteractionRecord now stores the tools schema and
RegressionTestExecutor passes it through to LlmRequest.

Closes #12
Assisted-by: AI coding agent
```

```
fix(recorder): drop instead of block when ring buffer is full

Aligns with R8 (zero intrusion): producer threads must never
wait on the recording pipeline.
```

---

## 十二、代码质量规范

### 12.1 硬门槛（提交/PR 前必须满足）

1. **`mvn -B test` 全绿**——在仓库根执行，9 个 reactor 模块 BUILD SUCCESS。红色测试的代码不允许提交。
2. **core 零依赖自检**（R1）：
   ```bash
   grep -rn "^import \(com\|org\)\." agentassert4j-core/src/main/java/
   ```
   输出必须为空。

### 12.2 测试要求

- 新增公开 API 或行为变更**必须伴随单元测试**；没有测试的公开 API 视为未完成。
- bug 修复先写**失败复现测试**，再写修复，两者同一 PR（评审可看到测试由红转绿）。
- 测试风格跟随现状：JUnit 5；测试类以 `XxxTest` 命名；场景分组用 `@Nested` 嵌套类（如 `BaselineManagerTest` 内分 `Approve`/
  `Reject`/`Rollback` 组）；断言信息写明预期与实际。
- 现有测试规模（core 主代码与测试代码约 1:1.2）是质量资产，**禁止为通过测试而削弱断言或删除测试**；测试本身错误才可改，且须在提交说明中给出理由。

### 12.3 依赖与体积

- 新增任何外部依赖必须在 PR 描述中给出**成本收益账**：解决什么问题、体积影响、许可证（必须 Apache-2.0/MIT/BSD 类宽松许可，*
  *GPL/AGPL/LGPL 一票否决**）、长期维护面。
- 重量级依赖（传递依赖树庞大、需外部进程/服务）默认拒绝。
- core 模块永不引入任何外部依赖（R1，无例外）。

### 12.4 单向门变更标注

以下三类变更是**单向门**（走过去难回头），其 PR 必须在描述中显式标注并说明影响与迁移方案：

1. **存储 schema 列变更**——interactions 是只追加历史，不存在的列无法回填到已录数据；
2. **core/SPI 公开 API 签名变更**——发布后每次变动都是破坏性变更；
3. **判定语义/指纹算法定义变更**——会静默重解释用户已 approve 的全部历史基线，属信任级变更。

### 12.5 风格与一致性

- 跟随现有代码风格：**标识符英文、注释中文**、模型类 POJO 风格（§六）。
- 不做大范围纯格式化提交（污染 blame、干扰评审）；格式调整与逻辑变更分开提交。
- 行为、配置、默认值变更须同步更新受影响文档（README / 本文件）。
- 提交前自检 §八（TODO 标注）与 §九（全限定类名）两条强制规范。

### 12.6 代码注释规范

注释只写**代码本身说不出来的约束与动机**，能删则删：

1. **禁写过程叙事**——开发历程、修复记录、评审编号（如"复审 H6"、"定稿文档 §3"）、阶段标签、内部方案术语（如"承重墙"、"单向门数据"
   ）。这些属于提交信息与设计文档，不属于代码；评审编号引用的缺陷修复后，其上下文只在 git 历史里。
2. **禁引用仓库外的文档**——贡献者无法核实的出处一律不写。可交叉引用的只有仓库内公开文件（如本文件的 R 编号原则）与同模块类名。
3. **行为变更必须同步注释**——改代码不改注释等于留下错误信息，比没注释更糟。
4. **Javadoc 只在签名不自明时写**——参数含义、返回值语义、抛出条件无法从命名看出的才写；getter/setter、一目了然的实现不写。
5. **禁写方法体外的分割注释**——`// ===== 分组名 =====` 式横幅一律不写，类内结构靠方法命名与排列表达；确需步骤分隔时只能写在方法体内。

自检方法：删掉这条注释，读者是否会失去仅凭代码得不到的信息？不会就删。

### 12.7 方案设计与优化决策规范（成本收益原则）

**核心原则：技术成本必须划得来**

讨论技术实现、方案细节、优化措施时，AI 协作代理应主动做两件事：

1. **基于需求反向提问**：当需求存在模糊点、边界不清、或不同理解会导致截然不同的实现时，主动向维护者澄清，不要默默假设。
2. **对不合理需求提出建议**：当某个需求/优化方向的技术成本明显超过收益时，**有义务**指出并建议调整，而不是闷头实现。维护者掌握产品全局，AI 掌握实现细节——实现层面「划不划算」的判断是 AI 的职责。

**优化决策思考框架（动手前必过）**：
- **先量化，再决定**：任何性能优化先回答「真实场景下的成本到底多少」，不停留在「理论上会慢」。虚构的极端场景不是真实负载——本项目的真实负载是「单 JVM 内数十 Skill、日均数千次交互」量级，先按此量级算账。
- **算清两本账再选方案**：A 层（固定开销）+ B 层（业务逻辑）。常见误判只看 B 层忽视 A 层——如新增一个 schema 列（B 层极便宜），连带的是永久契约承诺、序列化/反序列化两侧与测试面扩大（A 层）。
- **复杂度是负债**：本项目中每个 SPI 接口、每个存储列、每个配置项都是发布后的永久契约或单向门数据（§12.4）。新增复杂度必须换来真实场景下可度量的收益，不为「看起来更优」买单。
- **需求可以裁剪**：砍掉某功能若能让实现复杂度大幅下降，应建议裁剪而非硬扛。功能完整性是产品决策，技术成本是工程判断——后者先给数据，让前者有依据。

**落地表现**：方案讨论中主动做成本-收益分析、主动质疑过度工程、主动建议需求调整。最终决策权在维护者，但「这个优化划不划算」的判断必须先摆到桌面上。

### 12.8 契约驱动开发与审计规范（三层审计 + 六大铁律）

**核心认知：实现 ≠ 设计落地 ≠ 测试通过。** 代码能编译、测试全绿，不代表需求的语义真的实现了；单文件内「代码没问题」，不代表跨组件链路不断裂。

#### 三层审计法（每轮审查覆盖全部三层，缺一不可）

| 层 | 视角 | 审查内容 |
|----|------|---------|
| **L1 单元级** | 代码内部正确性 | 线程安全、并发安全、内存泄漏、资源管理、关闭时序、异常处理、SQL 安全、代码质量 |
| **L2 契约级** | 跨组件数据流与格式 | 捕获层写入的字段集 ↔ schema 列集 ↔ 读侧反序列化键三方逐一对齐；配置 JSON 键 ↔ 配置类字段；客户端解析的响应字段 ↔ 模型 setter；数据从哪来到哪去、中间有没有丢失/变形 |
| **L3 集成级** | 端到端与设计意图 | 录制→存储→分组→指纹→基线→对比全链路；跨会话/崩溃重放状态；边界场景（空录制/超时/RingBuffer 满/record_id 重放）；需求逐条对照验证设计意图落地 |

**每轮审查必须显式声明覆盖了三层**，不能只报「代码正确性结论」。

#### 审查视角清单（每轮审查逐项过，勿遗漏）

- **线程安全**：单例/共享实例的实例字段是否被并发写；volatile/final/不可变是否到位
- **并发安全**：Disruptor 生产消费两端对 RingBuffer 的读写窗口；批量 flush 与 `stop()` 的竞态；原子操作（CAS/AtomicLong）
- **内存泄漏**：无界集合只增不减；连接/线程未关闭；回调持有引用防 GC
- **资源管理**：SQLite 连接与刷新线程的生命周期；超时后底层 HTTP 调用是否取消
- **关闭时序**：`stop()` 是否排空在途批次；关停瞬间新交互到达的行为（丢弃计数 vs 阻塞生产者）
- **异常处理**：异常是否被静默吞掉；降级路径是否掩盖真实错误；exceptionally 是否区分异常类型
- **SQL 安全**：INSERT 列数与占位符个数逐一核对；读侧键名与写侧序列化键名一致；特殊字符 escape/unescape 往返
- **代码质量**：死代码/未使用 import/注释与代码不符/冗余方法
- **隐藏 BUG**：边界值（null/0/负数/超长/尾随空白）；格式容忍度；占位符与方法签名一致性
- **逻辑完整性**：循环终止条件；递归深度限制；竞态窗口；状态机全分支（基线三态流转）
- **注释规范符合性**：对照 §12.6 全条自查

#### 六大铁律（开发阶段强制）

1. **需求是规格，不是参考**——功能实现后对照需求逐条验证设计意图落地。文档说 X，代码做 Y → 是缺陷，不是实现选择。
2. **契约先于代码**——写跨组件代码前，先明确「生产者输出格式 + 消费者期望格式」，钉成测试或断言。客户端解析与 LLM 响应方言是契约两端，逐字段对齐验证；schema 列与序列化键同理。
3. **修复必须闭环**——修 BUG 后：①重新推理该路径完整数据流 ②定向测试覆盖该路径 ③检查相邻路径受影响面。测试绿 ≠ 修复正确，跨组件修复尤其如此。
4. **测试测行为契约，不是测代码不崩**——契约场景（方言归一/排序确定性/转义往返/重放幂等/并发丢弃）必须显式覆盖。happy path 全绿但契约断裂 = 白测。
5. **小步验证，问题前置**——大改动拆小步，每步完成做三层验证（编译 + 定向测试 + 契约对照）再进下一步。一次改 5 个模块再统一验证 = 问题堆积。
6. **审计带对抗性视角**——主动问：哪里会断？数据丢在哪？消费者会收到什么？这个修复破坏谁？边界枚举了吗？不要只确认「代码对不对」。

#### 审查报告格式

- **三层声明**：本报告覆盖 L1/L2/L3 哪些层（显式列出）
- **视角清单勾稽**：上述各项审查视角逐项给出「发现/无问题」结论
- **严重度分级**：HIGH（功能错误/数据丢失/不可逆风险）/ MEDIUM（降级路径错误/资源泄漏/边界缺陷）/ LOW（清理项）
- **修复闭环**：每个修复项标注「修复后定向验证」方式

---

## 十三、多开发者与 AI 代理协作规范

本仓库的现实工作流：单人维护者 + 多个 AI 编码代理（不同会话/不同产品）+ 未来社区贡献者。以下约定保证这种混合协作不出岔子：

### 13.1 会话启动自检（AI 代理每次会话开始时执行）

1. `git status`——工作区有未预期的脏文件时，先弄清来源再动手，不覆盖不明变更；
2. 确认当前分支——按 §十分支模型工作（注意其预发布阶段生效时间说明）；
3. 大范围改动前跑一次 `mvn -B test` 建立绿色基线，结束时再跑确认仍绿。

### 13.2 不重开既定结论

§〇防重开清单与既有审计决策已定案。AI 代理在任务中发现「看似更优的替代方案」时，默认动作是**开 issue 提案**
，不是在当前任务中顺手改架构——不同会话反复推翻彼此结论（翻烧饼）是本项目最需要防的协作风险。

### 13.3 范围纪律

只做任务范围内的事。范围外的发现（坏味道、可优化点、疑似 bug）记 TODO（§八）或开 issue 通报，不顺手修——顺手修破坏原子提交（§十一-2），也剥夺了维护者的评审权。

### 13.4 提交与推送权限

- AI 代理**不得直接 push main**（与人类贡献者同等约束，§十；预发布阶段例外见 §十生效时间说明）。
- AI 代理**不得自行 commit**：完成改动并自检通过后保持工作区状态；提交与否、何时提交、如何分阶段拆分均由维护者决定并手动执行，AI
  仅提供符合 §十一 的建议提交信息文本。也不主动 push 远端或开 PR，除非被明确要求。
- 破坏性操作（force push、分支删除、历史重写）仅维护者可为，且不作用于 main。

### 13.5 AI 参与的透明度

AI 代理参与生成的提交，footer 统一标注固定值 `Assisted-by: AI coding agent`。标明哪些提交含 AI
协作即可满足可追溯与回归排查需要；不写具体代理名称与邮箱——`Co-Authored-By` 会被 GitHub 解析为贡献者身份（计入 Contributors
列表），AI 不应占用贡献者席位。

### 13.6 规范文件本身的变更

对本文件（AGENTS.md）的修改走 `docs(...)` 类型提交 + PR 评审，不接受「顺手改」——规范是全体协作者的共同契约，变更必须显式、可评审、留痕。
