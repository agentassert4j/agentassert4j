# CLI 命令面规格（cli）

> 最近复核：364801f / 2026-09-03 · S8 成文（会话内对照统一引擎落地后的命令面终态：
> AgentAssert4jCli / ReplayCommand / AdjudicateCommand / VerifyCommand / CompletionCommand）
> 验证三档占比：【测试钉】8 条 ·【命令可证】4 条 ·【人工对账】1 条

## 职责与边界

**管**：命令注册与参数解析、选择器两档标准、输出通道契约（人类/--json/--dry-run）、报告
schema、退出码契约、help 终态。

**不管**：引擎编排语义（replay）、治理写（governance）、指纹与判定（judgment）、键文法
（identity）、存储承载（storage）。

## 心智模型与命令清单

**库是一切真源**：提示词内容只从应用→录制→库流入引擎；**bare 命令 = 全项目完整默认能力**，
参数只做缩域或开关。「必须指定目标」仅允许出现在操作宾语场景（verify 的 --pack、rollback
的 --version——不给宾语动作无定义）。

| 命令 | bare 语义 | 主要参数 |
|---|---|---|
| `status` | 全部画像巡检 | `--diff`（候选差异+模板原文渲染）、`--json`、`--db` |
| `baseline` | 全部调用点建档（幂等） | `--force`（判定语义重建恢复路径）、`--invocation` 缩域、`--json` |
| `replay` | 全项目漂移检测+逐任务对齐（零 LLM 调用） | `--task`/`--invocation` 复合缩域、`--ci`、`--re-drive`、`--full-chain`、`--max-total-calls`/`--max-total-tokens`、`--dry-run`、`--json` |
| `approve` / `reject` | 裁决全部待裁决候选 | `--invocation` 缩域、`--json` |
| `rollback` | 无缺省（--version 是操作宾语） | `--invocation`、`--version` |
| `verify` | 无缺省（--pack 是操作宾语） | `--pack`、`--task` 前缀、`--dry-run`、`--report`、`--json` |
| `rules` | 列内置行为目录与规则文件加载结果 | — |
| `graph show` | 现场重建依赖图 | — |
| `doctor` | 全库体检 | `--json` |
| `completion` | 生成 bash 补全脚本 | `--shell`（仅 bash 风格；动态值补全不做——既定裁决） |

**选择器两档标准**：目标选择器（approve/reject 的 --invocation、rollback/verify 的宾语）=
完整键精确 > 业务标签唯一 > 显示短形 > 唯一前缀，多命中报错列候选；缩域选择器（replay 的
--task/--invocation）= 前缀过滤，两档处理完全对称、不分叉。

## 契约

1. **输出通道**：人类模式全走 out；`--json` 模式 stdout 只产报告本体（可多行，消费方按行
   读取），进度静默，诊断与用法错误走 err。【测试钉】`CommandSmokeTest`（冷启动 stdout
   零污染）
2. **退出码 0/1/2**：全命令统一语义——0 无回归；1 行为差异或证据缺口（没跑够）；2 用法/
   数据/环境（被截断）。根 help 以 exitCodeList 呈现。【命令可证】`agentassert4j --help`
   的 Exit Codes 段；【测试钉】各引擎命令的退出码矩阵（replay 组/裁决组/验收组）
3. **replay help 终态**：三层模型参数面在场（--task/--invocation/--ci/--re-drive/--full-chain/
   --max-total-calls/--max-total-tokens/--dry-run），拆除参数不复活（--prompt/--old-prompt/
   --old-key/--affected/--max-cases/--selection/--no-establish）。【测试钉】
   `CommandSmokeTest.replayHelp_finalParamSurface`
4. **裁决面**：bare approve/reject = 裁决全部待裁决候选（无候选显式说明出 2）；拍板前渲染
   候选与基线逐维差异。【测试钉】`CommandSmokeTest.adjudicate_bare_reportsNoCandidates` +
   `ReplayFlowTest.BareAdjudicate`
5. **报告 schema**：status=agentassert4j.status/1（画像含 templateDrift 三态）；replay=
   agentassert4j.task-report/1（mode: drift-detection / task-align / task-dry-run /
   drift-disposition / task-re-drive）；裁决=agentassert4j.adjudication/1；验收=
   agentassert4j.verify-report/1（含 dry-run mode）；导出=acceptance-pack/1；doctor=doctor/1。
   开发期版本恒定，mode 扩展属开发期语义演进。【测试钉】`JsonContractTest` +
   `TaskReplayRunnerTest.JsonContract`（replay-report/1 已随统一引擎退役，禁止回归）
6. **status 漂移列**：画像行模板身份三态（● 一致/▲ 漂移/- 无身份）与 replay 共用同一检测器
   单一真源；模板原文随 --diff 渲染并限行。【测试钉】`StatusCommand` 经 `JsonContractTest`
   （templateDrift 字段）
7. **配置披露**：命令输出披露配置查找链命中结果。【命令可证】status 输出「配置：」行
8. **UTF-8 直写**：Windows 控制台经 UTF-8 直写 FileDescriptor，中文渲染不乱码。
   【测试钉】`CliSupportUtf8Test`
9. **错误带下一步指引**：选链歧义列候选、CI 拒绝列名单并给出建档指引、语义守卫给重建指引、
   覆盖缺口给因果提示。【人工对账】逐错误路径文案巡检（随各 spec 行为矩阵覆盖，无机械钉）

## 行为矩阵（replay 参数交互）

| 场景 | 行为 |
|---|---|
| bare | 全项目检测+对齐+处置，零调用 |
| --task X / --invocation K / 两者复合 | 缩域 AND；交集为空出 2 |
| --ci 且缩域内未建档 | 拒绝判定出 2 + 名单 |
| --re-drive | 第三层真重驱（花调用）；PASS 漂移仍不收编？否——开发态收编照常，重驱为复核证据 |
| --full-chain 无 --re-drive | 用法错误出 2 |
| 预算参数无 --re-drive | 用法错误出 2（反静默清零） |
| --dry-run | 只读（检测+对齐计划+重驱报价），恒 0 |
| --json | stdout 纯报告，诊断走 stderr |

## 域间边界

- **下游引擎**：ReplayCommand 只做解析与委派，零业务逻辑；比较器/客户端/规则由单一工厂
  构造（口径不分叉）。
- **上游各域 spec**：本文件是命令面的形；语义以各域 spec 为准（冲突时修败方并留痕）。

## 文案风格规范（英文原生）

英文输出按英文工具母语习惯书写，**禁止镜像翻译中文句式**。六条规则 + 正反例：

| 规则 | 镜像翻译（禁止） | 原生英文（目标） |
|---|---|---|
| 报告与汇总用名词短语或 git 式计数 | Template drift detection: same-key drift 1 · label splits 0 | `Drift: 1 same-key, 0 label splits, 0 downstream` |
| 汇总计数 | Alignment summary: PASS 2 \| CHANGED 1 \| missing steps 0 | `Aligned 3 steps: 2 pass, 1 changed (0 missing, 0 added)` |
| 指引句动词开头、命令反引号 | First run agentassert4j baseline locally for manual confirmation and then retry | `Run \`agentassert4j baseline\` locally first, then retry.` |
| 错误 = 现象 + 下一步 | All re-drives failed, no comparison results — suspected configuration problem, please check | `All re-drive calls failed (no comparisons). Check llm config, then retry.` |
| 缺失用 no X found | Did not find the task chain matching the request text | `No task chain matching '<prefix>'. Check prefixes with \`status\`.` |
| 就地标注用短标签 | [served model X ≠ recorded Y] | `(served: X, recorded: Y)` |

术语与 JSON 枚举同词：invocation/baseline/candidate/drift (same-key / label split)/collect/hung/
re-drive/missing/added。句式 sentence case；全角标点与「」不出现在输出；新短语一经测试钉住即
冻结（改动属文案变更，同步断言）。质量门槛：git/gh 作者视角抽查，「这是翻译」即返工。

## 变更纪律

- 退出码语义、输出通道契约、help 终态 = 冻结契约；参数删除/新增须同批更新本文件与
  help snapshot 钉。
- **人类通道输出语言 = 英文单语**（2026-09-05 裁决，1.0.0 翻转前完成三批迁移 E1–E3；核心
  summary/detail 值随批切换；不做运行时多语言；installUtf8Console 保留——服务中文数据渲染
  而非 UI 语言）。迁移基准与逐文件盘点见 docs/阶段性 英文单语输出迁移专项调研。
- 新增子命令先补本文件再补码；报告 schema 演进按版本纪律（开发期恒定）。

## 复核台账

| 日期 | 方式 | 发现 |
|---|---|---|
| 2026-09-05 | E2+E3 英文迁移收口（E1 后同日连续实施，12 模块全绿） | E2 巡检治理域生产串清零（Status/CliSupport/Baseline×3/Verify×2/Adjudicate/Rollback/Doctor/Rules/GraphShow/FingerprintDiffRenderer/Approve/Reject/Completion + core parseNotes/PackCodec/ConfigLoader）；E3 help 面 55 处 description 与根命令面英文态；11 命令短别名落地（s/b/a/g/v/d/c + rp/rj/rb/ru，完整名保留，不做前缀匹配）；断言等义迁移累计 ~110 处 + 别名新测；README×2 样例块换英文实跑形态 + `aa` 别名姿势、OPERATIONS/导读引用片段同步；完成度门禁达成=cli/core 主码非注释 CJK 串 0，JSON 键集零变化 |
| 2026-09-05 | E1 引擎域英文迁移（第一批，全量绿 897+6skip） | 引擎域生产串清零：TaskReplayRunner 45 输出点 + ReplayCommand 运行时错误 6 处 + OpenAiCompatibleClient 4 处 + core 28 串（comparator summary / rule violation detail / 链式分歧 summary / 预估文案 / diff 摘要）；断言等义迁移 49 处（cli 33 + core 16），JSON 键集零变化；CJK 门禁 cli 主码 293→222（存量为 E2/E3 域）；盘点实证校正与遗漏文件补录见专项调研 §1.2/§1.3/§7 注记 |
| 2026-09-05 | 英文单语裁决同步（迁移未实施，先补 spec） | 决策=英文单语（重开并关闭 09-02 双语悬项）；迁移面实测=CLI 表示层 ~160 输出点/20 文件 + core 人读诊断串（ComparisonResult.summary、TaskRuleViolation.detail——JSON 值语言随批切换，键不变）+ 180 断言/13 文件；命令缩写调研=aa 启动器别名 + 前缀匹配（与选择器哲学同款）+ r* 四族显式别名，总改动 ≈10 行；详见 docs/阶段性 专项调研 |
| 2026-09-03 | S8 成文：命令面终态全量对账（C2-C4 落地后） | ①replay-report/1 随调用点域引擎退役，task-report/1 承接（退役事实已入测试钉）；②approve/reject 的 --all 已被 bare 语义吸收删除；③根 help 新增 exitCodeList 与心智模型描述，类头 Javadoc 典型流程同步刷新（旧 --prompt 时代示例清除） |
