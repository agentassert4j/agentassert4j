## AgentAssert4j 项目开发规范

---

### 一、Maven 坐标与包名规范

| 概念 | 值 | 说明 |
|------|-----|------|
| Maven groupId | `io.github.agentassert4j` | Maven Central 发布要求，用 GitHub 组织（agentassert4j）验证 |
| Java base package | `io.github.agentassert4j` | Java 包名不允许 `-`，用 `_` 替代 |
| 目录路径 | `io/github/axy_yxa/agentassert4j/` | 与 base package 对应 |
| artifactId 前缀 | `agentassert4j-` | 所有模块统一前缀 |
| 版本 | `1.0.0-SNAPSHOT` | 发布时改为正式版 |

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

### 二、模块组织架构

#### 2.1 目录结构（同类聚合）

```
agentassert4j/
│
│  ── 核心模块 ──
├── agentassert4j-core/                        ← 零依赖心脏（java.base ONLY, < 200KB）
├── agentassert4j-bom/                         ← 统一版本管理
├── agentassert4j-recorder/                    ← Disruptor 异步录制
├── agentassert4j-cli/                         ← Picocli 命令行工具
│
│  ── 存储插件（聚合在 agentassert4j-storage/ 下）──
├── agentassert4j-storage/                     ← 聚合 POM (packaging=pom)
│   ├── agentassert4j-storage-sqlite/          ← core + SQLite JDBC（默认）
│   ├── agentassert4j-storage-mysql/           ← core + MySQL JDBC
│   └── agentassert4j-storage-pg/              ← core + PostgreSQL JDBC
│
│  ── 框架适配 SDK（聚合在 agentassert4j-sdk/ 下）──
├── agentassert4j-sdk/                         ← 聚合 POM (packaging=pom)
│   ├── agentassert4j-sdk-spring/              ← core + recorder + Spring AI
│   ├── agentassert4j-sdk-lang/                ← core + recorder + LangChain4j
│   └── agentassert4j-spring-boot-starter/     ← 聚合 core + sdk-spring + storage-sqlite + 自动装配
│
│  ── 接入方式 ──
├── agentassert4j-proxy/                       ← core + recorder + Netty（HTTP 代理）
├── agentassert4j-agent/                       ← core + recorder + ByteBuddy（Java Agent）
│
│  ── 独立模块 ──
├── agentassert4j-dashboard/                   ← core + Spring Boot Actuator
├── agentassert4j-embedding/                   ← core + DJL + ONNX（可选增强）
```

**聚合规则**：
- 同类模块收在聚合 POM 下（如 storage-* 收在 agentassert4j-storage/ 下）
- 聚合 POM 设 `<packaging>pom</packaging>`，parent 指向根 POM
- 子模块的 parent 仍指向根 POM，通过 `<relativePath>../../pom.xml</relativePath>` 定位
- 每个 artifactId 保持不变，Maven Central 发布不受影响
- 聚合 POM 不产出 JAR，仅用于目录归类和批量构建

#### 2.2 模块分层与依赖方向（单向，上层依赖下层）

```
Layer 1: agentassert4j-core          ← 零外部依赖，纯 java.base
           │
Layer 2: agentassert4j-recorder      ← core + Disruptor + SLF4J API
           │
Layer 3: agentassert4j-cli           ← core + recorder + Picocli
         agentassert4j-sdk-spring    ← core + recorder + Spring AI
         agentassert4j-sdk-lang      ← core + recorder + LangChain4j
         agentassert4j-proxy         ← core + recorder + Netty
         agentassert4j-agent         ← core + recorder + ByteBuddy
           │
Layer 4: agentassert4j-spring-boot-starter ← 聚合 core + sdk-spring + storage-sqlite + 自动装配

存储插件（独立，只依赖 core）：
  agentassert4j-storage-sqlite       ← core + SQLite JDBC（默认）
  agentassert4j-storage-mysql        ← core + MySQL JDBC
  agentassert4j-storage-pg           ← core + PG JDBC

增强插件（独立，只依赖 core）：
  agentassert4j-embedding            ← core + DJL + ONNX

版本管理：
  agentassert4j-bom                  ← 统一版本管理
```

**铁律**：下层绝对不感知上层。core 不 import recorder/sdk/proxy 的任何类。

#### 2.3 agentassert4j-core 内部包结构

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

### 三、核心设计原则（铁律）

| 编号 | 原则 | 说明 | 禁止做法 |
|------|------|------|----------|
| R1 | **核心零依赖** | agentassert4j-core 只能依赖 java.base | core 中 `import com.*` / `import org.*`（非 JDK） |
| R2 | **面向 SPI 编程** | 核心只定义接口，不 import 任何实现类 | `new SqliteStorageRepository()` 出现在 core |
| R3 | **插件平等** | 所有 SPI 实现地位平等，仅靠优先级区分 | 硬编码 `if (type == "sqlite")` |
| R4 | **配置驱动** | 行为差异靠配置切换，不靠代码分支 | 核心逻辑中判断 `storageType` |
| R5 | **单向依赖** | 上层依赖下层，下层不感知上层 | core 引用 proxy 的类 |
| R6 | **接口隔离** | 每个 SPI 接口 ≤ 5 个方法 | Repository 接口 20+ 个方法 |
| R7 | **图在内存** | 依赖图谱纯内存邻接表 + JSON 持久化 | 引入 Neo4j/图数据库 |
| R8 | **零侵入** | 框架不干预 Agent 正常运行 | 录制失败阻塞业务请求 |
| R9 | **确定性优先** | 核心判定逻辑 100% 确定性、可复现 | 核心链路依赖 LLM/概率模型 |
| R10 | **退化不中断** | 异常时退化到安全默认值，不抛异常 | RecursiveJsonParser 失败抛异常中断流程 |

---

### 四、错误处理三层策略

| 层级 | 范围 | 策略 |
|------|------|------|
| L1 | core | 防御性校验：null 输入返回安全默认值，解析失败退化为空集合，从不中断流程 |
| L2 | recorder | 异步容错：RingBuffer 满则丢弃、批量写入失败记计数器不重试、DB 连接失败本地文件备份 |
| L3 | 接入层 | 连接健壮性：上游超时透传错误、非标准格式尽力提取、版本不兼容静默退出 |

**通用规则**：框架的任何故障都不应影响 Agent 主流程。宁可丢失录制数据，不可阻塞业务请求。

---

### 五、日志策略

| 模块 | 日志框架 | 级别规则 |
|------|---------|----------|
| agentassert4j-core | java.util.logging（JDK 内置） | 只记 SEVERE（不可恢复错误），不记 INFO/WARNING |
| 上层模块（recorder/proxy/sdk-*） | SLF4J API（不绑定实现） | INFO：启停/统计/摘要；DEBUG：交互细节/算法中间结果；ERROR：DB/API 失败 |

---

### 六、模型类编写规范

1. 所有模型类使用 POJO 风格：private 字段 + getter/setter，无业务逻辑
2. 工具类以 `Util` 结尾，构造方法 private（如 `TypeInferUtil`）
3. 枚举类以含义命名（如 `BaselineStatus`、`SkillType`），注释说明每个值的语义
4. 跨包引用必须显式 import（即使是同 base package 的子包）
5. 有工厂方法的模型（如 `AnalysisResult.noBaseline()`）放在 model 包

---

### 七、SPI 接口编写规范

1. 所有 SPI 接口定义在 `spi/` 包，实现在独立模块
2. 接口方法 ≤ 5 个（接口隔离原则）
3. 异常用专用异常类（如 `LlmApiException`、`LlmTimeoutException`），不抛通用 Exception
4. 插件发现：CLI 显式配置 > Spring Boot AutoConfig > ServiceLoader > 默认 SQLite

---

### 八、临时代码标注规范（强制）

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

### 九、全限定类名使用规范（强制）

**规则**：除以下明确允许的场景外，**禁止**在代码中使用全限定类名（如 `java.util.Map`、`com.example.SomeClass`），必须通过 `import` 语句导入后使用简单类名。

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