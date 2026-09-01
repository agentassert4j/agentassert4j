# AgentAssert 框架全景导读

> **本文是什么**：框架的技术全景与学习路线，面向「懂 Java、懂基本概念，但没读过本仓库任何文档和一行代码」的
> 贡献者。全文用一个虚构客服机器人「小店通」的完整故事串起框架的所有功能：Part I 只讲故事建立直觉，
> Part II 把故事的每一幕拆开、逐一落回真实的类、方法和表结构，Part III 给出精通后的工作方法。
>
> **读者承诺**：顺序通读本文后，你应当能够——读懂框架任何一段代码的意图；把一个缺陷现象定位到负责的模块；
> 按检查单设计与评审新功能。
>
> **如何使用**：第一次请顺序读 Part I（只求直觉，不查代码）；之后按需深入 Part II 对应章节；
> 日常开发时把 Part III 当工作手册翻。
>
> **文档性质**：活文档，跟随代码演进。本文只写「当前代码是什么」，每个断言落到真实类名/方法名/列名——
> 发现文档与代码不符，以代码为准并立即修文档。与 AGENTS.md（协作契约）和 README / OPERATIONS
> （使用者上手与运维手册）分工明确、互不替代。

---

# Part I 故事串讲——小店通的三个月

## 开场白：小王的问题

小王是一家电商公司的后端工程师，负责「小店通」——客服机器人。用户在店里问订单、问物流、申请退款，
小店通理解意图、调用对应的工具（`get_order` 查订单、`refund` 退款）、组织话术回答。技术栈是最常见的
组合：Spring Boot 3.5 + Spring AI 1.x + DeepSeek。

团队三个人的日常是反复打磨系统提示词：运营说「退款话术太生硬」，产品说「开场白要更热情」。每次改完，
小王都心里没底——改的这段话，会不会让模型在该调退款工具的时候不去调了？会不会忘说订单号？会不会把
回答格式搞崩？唯一靠谱的办法是把测试对话全部人工过一遍。每次。更磨人的是那种一句话需求触发的多步
任务：「订单 1234 的物流太慢了，帮我退款」——模型自己跑出一条四步的调用链，改完提示词再跑一遍，两
条链哪里不一样，全靠人肉眼逐行对。

AgentAssert4j 解决的就是这个「心里没底」。它的思路用人话说一遍：在你**一行业务代码都不改**的前提
下，框架在旁边把每一次真实的大模型调用**原样记录**下来（这叫**旁路录制**）；对每类业务行为，用一张
结构化的「行为照片」描述它当前长什么样，把认可的照片定为标准（照片叫**指纹**，标准叫**基线**）；以后
每次改提示词，把当时的调用原样再来一遍（这叫**重放**），和基线逐项比对；改完再真实执行一遍时，框架
按调用点自动把新链和基线链配对对齐，出一张逐步差异报告。哪里变了精确列出来；变化是不是你想要的，
由人裁决。它刻意不评判「更好还是更坏」——「一样不一样」是程序可以确定回答的问题，「好不好」留给人。这套闭环的全貌一张图（后面十一幕就是把图上每个环节讲透）：

<img src="../assets/hero-loop.zh.png" alt="核心闭环：Agent 旁路录制进单文件 SQLite，baseline 建档，改提示词后 replay --task 出逐步差异报告，approve / reject 裁决，export → verify 交付验收" width="860"/>

除了嵌进应用的框架本体，随包还有一个**命令行工具**：以 standalone jar（`java -jar` 直接运行）或 Maven
依赖提供，获取方式见 README；命令名叫 `agentassert4j`，后面跟
子命令和参数——小王接下来的全部操作都靠它。故事里每条命令第一次出现时都会就地解释它是干什么的、
参数哪些必填哪些可省；Part I 末尾还附了一张「故事命令速查表」，读的时候忘了随时翻回去。

整个过程对业务代码零侵入：框架自己出了问题宁可少记数据，也绝不拖慢业务请求。还有一条贯穿全部设计
的铁律提前立在此处：**能框架内部消化的，绝不外溢为使用者的动作**——录制、归类、建档、选例、影响集、
任务链派生，框架能自己算的自己算；使用者只在两个地方出现：改提示词（本来就要做）和裁决方向（只有人
能做）。

下面从小王接入框架的那天讲起。

## 第 0 幕 · 两个坐标和一行配置

小王在 pom 里加了 `agentassert4j-spring-boot3-starter` 依赖，在 application.yml 里把数据库路径指到
`var/agentassert4j.db`——其实这一行也可以不写（有默认值），但他习惯把数据文件归置好。重启应用：
照常启动，日志没有任何新报错，接口时延看不出变化，业务功能一切照旧。唯一的变化是 var/ 目录下多了
一个 SQLite 文件。

「它在干什么？」小王的第一反应是警惕：会不会拖慢我的接口？会不会改我的调用？会不会哪天把它自己搞挂
了、连累我的应用？这三个担忧后来都有了明确答案——不会、不会、不会，而且都不是口头承诺，是写进装配
和管道代码里的结构保证。此刻小王只需要知道：**一个东西已经在他的应用旁边静静工作了。**

> **伏笔去向**：自动装配与包装原理 → 第 2 章；「零侵入」如何用代码结构保证 → 第 2、3 章。

## 第 1 幕 · 看不见的抄写员

三天测试流量加灰度真实流量。客服对话进来，小店通调工具、组织回答，一切如常。第四天小王用任意 SQLite
查看工具打开了那个 .db 文件：里面躺着几百条**调用记录**——每一次大模型调用对应一条。每一条都完整得
惊人：请求面有系统提示词（存的是内容代号——同一份提示词永远算出同一个代号）、用户输入、当时挂载
的工具清单、采样参数；响应面有回答原文、输入输出
token 数（连缓存命中和思考 token 都分列）、端到端时延、首字耗时、按价格表折算的费用。

他没有写过一行采集代码。

他注意到框架带**脱敏**能力：配置一组敏感字段名（比如手机号），落库前这些字段的值会被替换成 `***`——
客服场景迟早用得上，先记下。压测那天，应用日志里出现过几次「缓冲打满、丢弃记录」的告警：**丢的是
记录，业务接口毫发无损**——框架在「记录」和「不打扰」冲突时永远选后者，而且丢了多少条，账本上诚实
地记着。

默认配置下框架**全量录制**——包括没有工具调用的纯文本对话。这个默认是刻意的：一条任务链的终点往往
正是「组织最终答复」的纯文本调用，按「有没有调工具」筛掉它们，任务链就天生缺了终点。超大流量的团队
可以关掉它，被滤掉多少条，账本上单独记一笔（这叫 **filtered**，和「缓冲满被挤掉」的 dropped 是两
回事：后者是故障，前者是策略）。小王还顺手给小店通配了一行
`agentassert4j.invocation-id=tavern`，所有调用就以这个身份入账；将来小店通拆出多个调用点，再在代码里逐个
声明。

> **伏笔去向**：记录是怎么被抄走的 → 第 3 章；脱敏与采集门 → 第 3 章；丢弃与账本 → 第 3 章。

## 第 2 幕 · 给行为拍证件照

录了一堆原始调用，然后呢？小王敲了第一条子命令：

```
$ agentassert4j baseline --approver wang
```

`baseline` 就是「基线」——这条命令给每类行为建立标准。`--approver` 声明「本次盖章由谁负责」，审批人
名字会随基线一起落库留痕；这个参数可以不填，不填就取当前操作系统用户名。

命令内部依次做了三件事。第一件是**自动归类**：框架先看调用有没有**声明**业务身份——代码里给每次调用报上的名字，或应用配置
的默认名字；有声明就以声明为准（提示词一改模板指纹就变，声明是唯一能跨编辑保持身份稳定的锚）。没
声明的调用按模板哈希归组：同一份系统提示词调出来的记录归成一堆（「查订单的提示词」是一类，「退款话
术」是另一类）——没声明的组同样是完整公民，重放、裁决样样可用，框架不逼人表态。第二件是给每个调用点提取一张**指纹**：四个维度的结构化摘要——

1. **工具调用**：这次交互调用了哪些工具、参数是什么类型；
2. **输出结构**：回答是 JSON 还是文本、有哪些字段、纯文本的话量级多大；
3. **内容规则**：必须出现的关键词、禁止出现的词、要匹配的正则；
4. **约束行为**：声明过的行为约束（比如「必须中文回答」「出错时返回空」）是否守住。

前两维框架全自动提取，后两维来自团队在规则文件里的声明。这里只需要接受一件事：**同一类行为，永远
提取得出同一张指纹**——这正是「照片」可以拿来比对的原因（怎么做到的，第 6 章讲）。第三件是**盖章**：
把每个调用点当前的指纹直接定为 v1 号基线，审批人与时间戳随之落库；已建档的调用点跳过不覆盖（所以重复
执行也安全）。注意这里没有「先预览再批准」的中间态——基线的定义就是「对当前行为的盖章」，指纹细节
第一次真正登台，要等到第 3 幕出现差异、需要裁决的时候。

跑一遍 `agentassert4j status`（查状态）可以核验成果：每行一个调用点——身份代号、现行标准是第几版、
有没有待裁决的新照片、存档里有哪些旧版本、以及业务侧起的人读名字（没声明名字的调用点，只能领一串
代号）。真实输出长这样（虚构演示库，六个调用点全部 BASELINE）：

<img src="../assets/cli-status.png" alt="status 输出：调用点清单与基线状态" width="820"/>

这一幕收尾前，点破一件贯穿全书的事：证件照**不问能力的出身**。小店通现在的工具（`get_order`、
`refund`）是小王自己代码里写的；哪天从公司统一的 MCP 服务接入外部工具，或者换用带 skill 机制的
agent 框架（skill 触发时，宿主把指令文本注入对话、把附带脚本注册成工具），框架看到的仍然是同样
两样东西：请求工具清单里的一条定义、回答里的工具点名。**协议层无感、行为层全知**——MCP 工具发到
大模型那里时和本地函数毫无区别，请求里连「MCP」这个词都不存在；也因此，改 MCP 工具的名字或描述
文案，性质上就等于改了一次提示词，同样逃不过指纹的眼睛。

> **伏笔去向**：身份锚点与键文法 → 第 5 章；指纹怎么提取 → 第 6 章；基线的盖章/存档/回滚 → 第 7 章。

## 第 3 幕 · 改提示词的勇气

运营小林提需求：「退款话术太生硬，柔和一点。」小王改完系统提示词，导出为 prompt-v2.txt。真跑一遍要
花真金白银，所以他先用了重放命令的**预演模式**：

```
$ agentassert4j replay --prompt prompt-v2.txt --invocation refund --dry-run
```

`replay` 就是「重放」。`--prompt` 指定新提示词文件——在调用点范围（不带 `--task/--affected`）它是
**必填**的，它是整个实验里唯一的变量；`--invocation` 把重放限定在一个调用点上（填调用点键或业务标签
的唯一前缀），不加就全库选例；`--dry-run`
表示只预演：列出会重放哪些**用例**（用例 = 被选中重放的一条历史调用，每个调用点默认取最新的 3 条）、
预计多少次调用、多少 token、折多少钱，但不发起任何真实调用。这个参数可以不填——不填就是真跑。

预演确认无误后，小王去掉 `--dry-run` 真跑。每条用例是一次**真实的 LLM 调用**：同样的对话上下文、同样
的工具清单，只有系统提示词换成新版；回答回来，框架提取同样的四维指纹，和基线逐项比对，最后给一行
汇总：

`PASS 5 | CHANGED 2 | 失败 0（共 7）`

判定只有两个词：**PASS**（与基线无差异）、**CHANGED**（与基线有差异）。框架刻意不说「严重」——工具
A 换成工具 B 是变好还是变坏，程序无从判断，宣称「回归」就是越权。

两条 CHANGED，展开看差异清单：一条是回答比基线多了一个字段——预期内的话术演进；另一条是大事：
**模型不再调用 `refund` 工具**，改成查完订单后纯文本道歉。哪条严重框架不评价，差异摆在那里，人自己
读。小王把 v2 里「尽量先安抚用户」那段收紧，重放，不调工具的那条消失了；多字段那条是产品要的效果，
用裁决命令接受它：

```
$ agentassert4j approve --invocation <调用点键前缀>
```

`approve` 是裁决命令：把有差异的新指纹转正为新基线（v1 自动进存档，随时可以回滚）。`--invocation` 指定
裁决哪个调用点，填调用点键（或业务标签）的任意唯一前缀即可。不认可就用 `reject`——新指纹作废，老基线原地不动。

整条链路没有出现一句「这段回答更好/更差」。框架只陈述事实：哪里一样，哪里不一样，差异在第几维、哪个
字段。方向判断是小王做的。

> **伏笔去向**：重放请求怎么组装 → 第 9 章；判定规则 → 第 8 章；差异怎么渲染给人看 → 第 8 章；
> approve 之后数据怎么流转 → 第 7 章。

## 第 4 幕 · 一句话改动，整条链的回归

第 3 幕的重放是**显微镜**——盯着一个调用点问「你变了没有」。但小王改的这段退款话术，处在一个四步
任务的链条里：用户一句「订单 1234 的物流太慢了，帮我退款」，小店通自己跑出查订单 → 查物流 → 发起
退款 → 组织答复的完整链路。链条上每个决策点都被独立录了制、拍了照（退款那轮的记录里，前两轮的调用
与结果一字不差地续在上下文中）。改了其中一环，其余环节还站得住吗？——这是**望远镜**该回答的问题：

```
$ agentassert4j replay --task "订单 1234 的物流太慢" --prompt prompt-v2.txt
```

`--task` 按请求文本找到那条任务链（输入完整文本精确命中；只记得开头就给前缀，命中多个不同任务时会提示你写得更长些），把链上每一步按录制顺序重放一遍：同样的历史输入、同样的
上下文，只有系统提示词换成新版。报告逐步列出判定——查订单 PASS、查物流 PASS、退款 CHANGED、
组织答复标成「分歧后下游」。

最后这个标注是重点。框架在退款那步发现差异后**停止了后续步骤的真重放**：下游的组织答复拿到的上下文
里已经混进了新行为的结果，这时候比出来的「一致」或「不一致」都说不清归因——与其产出一个可疑的结论，
不如诚实地标一个**条件态**：这一步没验证，它的真实表现要等真实再执行后看。想让所有步骤都真跑一遍
（比如预算充足的深夜全量验证），加 `--full-chain`。

真实调用心疼钱的话，还有两个旋钮：`--old-prompt prompt-v1.txt` 告诉框架改动前的提示词是哪个文件，
链上**模板没变的步骤就不用真调模型**——它们不受这次改动影响，直接继承 PASS；`--max-total-calls` /
`--max-total-tokens` 给整次运行设一个全局预算池，花到线就收手，没跑的步骤如实标「跳过」。还有
一个降低试错成本的开关：`--dry-run` 只出执行计划与成本预估——哪些步骤会真重放、哪些继承，零调用、
零建档。证据不完整时退出码会说真话（第 7 幕）。干跑计划长这样：

<img src="../assets/cli-dry-run.png" alt="replay --task --dry-run：逐步执行计划与成本预估，未调用 LLM、未建档" width="820"/>

任务本身不用注册、不用声明——**一个任务链就是「同一个会话里，同一次用户请求触发的那串调用」**，框架
从录制数据现场推导；编排系统知道自己的场景编号的话，也可以在录制时显式声明任务键，配对更稳。改完
提示词先冻结重放一遍只是半程；等新版本真的上线、测试环境又真实执行了一遍同样的需求，小王敲下：

```
$ agentassert4j replay --task "订单 1234 的物流太慢"
```

这次**不带 `--prompt`**——框架不再重演，而是把库里**最新的链**和**次新的链**按调用点自动配对对齐：
基线链执行过而新链没有的步骤，标「缺步骤」；新链多出来的，标「新增步骤」；两边都有的逐对比指纹。上
个月小王优化了查订单的提示词后真实重跑，报告直接指出：新链第一步换了模板（模板版本更替的表达）、
物流那步的参数结构变了——**这就是人工肉眼对比两条链这个最大痛点的直接替代**。文本措辞的差异会单独
列出并标注「低置信」——模型两遍说话的措辞本来就会波动，结构才是可信的比对面。真实对齐报告长这样（虚构演示库——缺一步、新增一步、一个结构变化，逐条点名，exit 1）：

<img src="../assets/cli-align-report.png" alt="replay --task 真实对齐报告：PASS 3 | CHANGED 1 | 缺步骤 1 | 新增步骤 1" width="880"/>

> **伏笔去向**：任务链怎么从录制数据推导 → 第 11 章；对齐与缺/新增判定 → 第 11 章；
> 冻结重放的受影响裁剪 → 第 11 章。

## 第 5 幕 · 这次改动波及谁

下一次改动不再是退款技能的局部话术，而是**所有技能共用的开场白**。这次小王给重放命令多给了一个
参数：

```
$ agentassert4j replay --prompt prompt-v3.txt --old-prompt prompt-v2.txt --affected
```

`--old-prompt` 告诉框架「改动前的提示词是哪个文件」；`--affected` 把回放单元从单条任务链升级为
**全部受影响的任务链**：框架先翻两本账——**谁在用旧版提示词**（录制数据里现成的模板哈希反查），以及
**依赖图**——哪些调用点在同一个会话里发生过工具接力（查完订单接着退款的，行为是连带的）——然后找出
含这些调用点的所有任务链，逐链冻结重放。开场白被十几个调用点共享——超过框架的「全局提示词」线（共
享调用点数达到 10 个），自动触发抽样：每个受影响调用点最多取 3 条；只被个别调用点使用的局部提示词则
全量重放。重放只跑影响面，成本立省一大半。

值得强调的是：这张图不是某个独立组件精心维护的资产——它是录制数据的**派生品**，每次重放前从录制数据
现场重建，写一份快照。永远和录制数据一致，永不撒谎。

> **伏笔去向**：边从哪来 → 第 10 章；图的生命周期（谁读谁写） → 第 10 章；采样策略 → 第 10 章。

## 第 6 幕 · 后悔药

上周小王手一抖，把一个不该接受的差异 approve 转正了。补救是一行命令：

```
$ agentassert4j rollback --invocation refund --version v3
```

`rollback` = 回滚：把指定调用点的标准恢复到某个历史版本。两个参数**都必须填**——`--invocation` 指定调用点，
`--version` 指定想回到的版本号，可选值在 `status` 清单的「存档版本」一列能看到。存档里的 v3 原样恢复
成现行标准——当初每次转正都自动把老标准存档，就是为了今天。

另一次，框架升级了指纹提取算法——每份基线身上都记着「它是用哪个版本的比对算法盖章的」，算法升级后
新账旧账对不上。`replay` 直接**拒绝判定**（退出码 2）：「这批基线由旧算法版本批准，拒绝用新算法重新
解释，请显式重建。」小王执行 `baseline --force`：`--force` 是建档命令的「强制重盖」开关（不加它时
建档命令绝不覆盖已有基线），按当前算法重新提取指纹、盖成新版本号，旧的自动存档。为什么是拒绝而不是
警告？因为拿新尺子重新丈量旧照片，得出的一切结论都是悄悄不可信的——宁可停下来让人显式表态。

> **伏笔去向**：归档表与版本号分配 → 第 7 章；语义版本戳契约 → 第 8 章。

## 第 7 幕 · 门禁

小王把重放接进了 CI。变更流水线里跑：

```
$ agentassert4j replay --prompt prompt-latest.txt --ci --json
```

`--ci` 是给流水线的专用模式：不为没有基线的调用点自动建档——宁可拒绝判定，也不产「自建自比」的绿灯
（不带它时，重放开头会顺手给新调用点自动建档）。`--json` 让结果变成**一行机器可读的报告**写到标准
输出，人看的进度和诊断改走错误输出——程序和人各看各的，互不干扰。这两个参数都可以不填：不填就是
人看的默认形态。

退出码替他说真话：**0** 无差异，**1** 存在差异（人去裁决），**2** 用法或基础设施故障——比如全部用例
超时，那是环境问题不是行为回归，不能让 CI 误读成红灯。任务域的两个模式共用同一契约，并且多一条：
缺步骤、新增步骤也算差异（exit 1）；只跑了半截（预算耗尽、有跳过）时证据不完整，算 2——**跳过的
部分不允许冒充绿灯**。

某天同事的 PR 让 CI 亮了 2，原因一栏写着：「调用点 X 有录制交互但无基线，`--ci` 模式拒绝判定。」这是
专门防一种经典事故的：CI 里自动给没基线的调用点建档、自建自比，产出一堆**无人审过的绿灯**。基线必须
经过人，门禁才可信。

> **伏笔去向**：退出码契约 → 第 9 章；`--json` 通道契约 → 第 9 章；任务域报告 → 第 11 章。

## 第 8 幕 · 两条支线

同事老陈的两个系统也想接入，但它们都不是 Spring Boot + Spring AI。

一个是 **JDK 8 的老服务**。它不引 starter（那需要 Boot 3），而是手动装配 core + recorder + storage
三个 jar，在自己封装的 LLM 调用出口处把记录交给录制器——三行代码。框架的内核对 JDK 版本的最低要求
就是 8，这是发布卖点之一。

另一个是**自研栈机器人**：工具调用不走协议层的 toolCalls 参数，而是让模型输出约定格式的 JSON、业务
代码解析后自行分发。协议层看不见工具调用，框架自然也看不见——所以老陈在解析出「这次要调 get_order」
的那一行旁边，把工具名写进了记录的身份声明字段；意图记录靠规则文件里的正则钉住「该选哪个工具」，回答
记录四维照常。接入姿势不同，落到数据库里的是**同一种记录、同一套基线语义**——这正是分层架构的回报。

> **伏笔去向**：手动装配路径 → 第 2 章；规则与正则 → 第 6 章；身份声明 → 第 5 章。

## 第 9 幕 · 把行为标准装进一个文件

小店通要交付了。客户内网部署，验收方问了一个不好回答的问题：「你们演示时跑通的这条链，到我们环境里
（模型版本还不一样）还是这个行为吗？拿证据来。」

证据不能是开发侧的数据库——那里面有用户的原始对话，而且客户环境也不会拿你的库当标准。框架给答案是
一个**文件**：

```
$ agentassert4j baseline export --out acceptance-pack.json
```

`baseline export` 把当前全部基线打包成一个 JSON：每个任务链、链上每一步的调用点键和**结构指纹**——
工具名、参数类型、输出字段路径与类型、规则关键词。注意这个包**天然不带敏感内容**：没有用户输入输出
原文、没有模板原文、没有规则文件——结构指纹本身就是脱敏后的「行为形状」。包里同时带上了开发侧用的
模型名、判定语义版本和文件指纹（SHA-256），双方对账用的是后者。想附上人读的样本也行——`--include-samples`
会附加每步的输入输出样例，且**强制**套上最严的脱敏（敏感字段一律 `***`），判定不消费样本，它们只是
给验收人翻看的。流水线里导出加 `--json`：stdout 给一行 `agentassert4j.export-report/1` 元数据报告
（输出路径/任务链数/步骤数/SHA-256/被排除链），CI 拿它自动对账。整个交付验收的两侧动线：

<img src="../assets/acceptance-flow.zh.png" alt="交付验收流程：开发侧导出验收包 → SHA-256 对账搬运 → 验收侧真实执行 → verify 出报告" width="860"/>

验收方拿到包，在客户环境真实执行一遍验收请求（这是验收人自己操作的——框架不驱动产品入口），然后：

```
$ agentassert4j verify --pack acceptance-pack.json --report verify-report.md
```

`verify` 是第 9 个顶层命令，只读——导入包、比对、出报告，不往本地库写任何东西。比对逻辑和第 4 幕的
真实对比是同一台机器：包里的指纹当基线侧，本机刚录制的任务链现场提取指纹当当前侧，按调用点逐步对齐。
报告直说三件事：每步一致还是偏差（结构类偏差是真问题）；开发侧与本地模型不同时，标注**跨模型验收**
——文本措辞差异属预期内，结构判定依然有效；包里有而本地没执行的任务，列成**覆盖缺口**（证据不完整，
不允许冒充通过）。这份 markdown 报告就是交付证据本身。

> **伏笔去向**：包格式与版本守卫 → 第 12 章；verify 的匹配与退出码 → 第 12 章。

## 第 10 幕 · 看得更远

默认配置下，Spring AI 在框架看不见的模型内部跑完整条工具回路：模型决定查订单、拿到结果、再决定查
物流——这些中间决策被回路整个消费掉，装饰器只见最终一句「已发货」。录制的记录里没有工具调用，占
指纹四成的工具维成了盲区。第 2 幕提过框架的一声警告；现在它直接把眼睛装了进去：框架在**工具回调层**
（业务注册的工具与其执行之间的必经之路）挂上纯观察的旁路——工具被真实执行的同一时刻，调了什么、
带了什么参数、拿到什么结果，按序誊进**同一条记录**。业务一行代码不改，回路照常跑，工具维满血。
一条记录承载一次完整编排（查订单 → 查物流 = 一个回归单元），比分轮记录更贴近「技能行为」的本体。

重放这类编排记录时，框架用的是**链式半重放**：第 2 轮的「当时输入」里含第 1 轮的工具结果，而结果
只存在于业务手里——框架拼不出它，但基线里**录了它**。于是重放拿基线录制的旧结果当道具：第 1 轮重问
模型「查订单」，决策与基线一致 → 把录制的结果帧递回去，续问第 2 轮「查物流」；某轮决策与基线对不上
——**当场停下**（分歧即停）。旧结果配新决策是虚构的演进，链条停在真相失效处恰是最诚实的呈现：

```
  第 1 轮决策  get_order(SO-1)      与基线一致
  第 2 轮决策  get_logistics(SO-1)  与基线一致
  末轮答复     四维比对 PASS
```

分歧时报告直接指到轮：**「第 2 轮工具决策分歧：基线为 get_logistics(SO-1)，实际为 cancel_order(SO-1)」**
——回归定位从「某个调用点变了」细化到「编排的第几步、原本要做什么、现在改做了什么」。第 4 幕任务域
的「分歧即停、下游标条件态」，正是这条规则在整链尺度上的推广。

> **伏笔去向**：观察装饰的落地形态（两代 SDK 的接入面差异） → 第 2 章；链式半重放的轮次装配与
> 分歧语义 → 第 9 章；梯 1 × 梯 2 两种姿势的取舍 → OPERATIONS.md（运维手册）。

## Part I 命令速查

故事里出现过的全部命令，供读故事时回查。这里只列**故事用到**的参数——每个命令的完整参数面见第 13 章，
或直接对命令执行 `--help`。

| 命令 | 干什么 | 故事里用到的参数 | 不填会怎样 |
|------|--------|----------------|-----------|
| `agentassert4j baseline` | 给每个调用点提取指纹、盖章建立基线 | `--approver <名字>`：审批留痕 | 审批人取操作系统用户名；已建档的调用点不覆盖 |
| `agentassert4j baseline --force` | 按当前比对算法重建基线（旧基线自动存档） | 同上 | 不加 `--force` 时绝不覆盖已有基线 |
| `agentassert4j baseline export` | 导出验收基线包（交付证据载体） | `--task <前缀>`（缩域）；`--include-samples`（脱敏样本）；`--out <文件>`（默认 `./acceptance-pack.json`）；`--json`（export-report/1：out/taskCount/stepCount/sha256/excluded 元数据报告） | 内容天然脱敏（结构指纹+键）；打印 SHA-256 供对账；存在未建档步骤的链排除并警告 |
| `agentassert4j status` | 查看调用点清单与基线状态 | `--diff`：展示待裁决的差异；`--json`（status/1） | 只看清单本体 |
| `agentassert4j replay` | 重放比对：用新提示词重跑历史用例并判定（调用点域） | `--prompt <文件>`（此范围**必填**）；`--invocation <前缀>`（限域）；`--dry-run`（只预演不调用）；`--old-prompt <文件>`（启用波及面裁剪）；`--max-cases`（默认 3）；`--selection`(newest/oldest)；`--ci`（流水线模式：不为无基线调用点自动建档）；`--json` | `--prompt` 缺失直接报错；其余缺省 = 真跑、全部调用点选例、人看输出 |
| `agentassert4j replay --task` | 任务域：整链回归（望远镜） | `--task <文本前缀>`；`--prompt` 可选（缺省=真实对比模式，最新链 vs 次新链按调用点对齐，零 LLM 调用）；`--old-prompt`（仅受影响步骤真重放，其余继承 PASS）；`--dry-run`（只出执行计划与成本预估，零调用零建档）；`--full-chain`（取消裁剪与分歧即停）；`--max-total-calls/--max-total-tokens`（全局预算池）；`--json`（task-report/1） | 精确命中优先；短前缀命中多个不同任务时报错列候选（exit 2）；单链=自建基线（exit 0） |
| `agentassert4j replay --affected` | 任务选择器：含受影响调用点的全部任务链逐链冻结重放 | 要求同时给 `--prompt` 与 `--old-prompt` | 与 `--task`/`--invocation` 互斥 |
| `agentassert4j approve` | 裁决：把有差异的新指纹转正为新基线 | `--invocation <目标>`；`--approver <名字>`；`--all` | `--invocation` 与 `--all` 二选一；审批人缺省取系统用户名 |
| `agentassert4j reject` | 裁决：丢弃新指纹，保留旧基线 | 同 approve | 同上 |
| `agentassert4j rollback` | 把基线回滚到指定历史版本 | `--invocation <目标>` 与 `--version <版本号>`（**均必填**） | 缺任一参数直接报错 |
| `agentassert4j verify` | 交付验收：验收包核对本机真实执行链（只读不落库） | `--pack <文件>`（**必填**）；`--task <前缀>`；`--report <md>`（交付证据）；`--json`（verify-report/1） | 版本守卫拒绝异语义包；覆盖缺口 exit 2；跨模型标注结构判定有效 |

---
---

# Part II 代码回归——把每一幕拆回代码

> Part II 共 13 章，每章固定七段式结构，保证可检索、可对照：
>
> 1. **本幕回顾**——对应 Part I 哪一幕的哪个情节（一段话）；
> 2. **设计问题**——如果没有这个组件，会发生什么（设计动机优先于实现细节）；
> 3. **概念与术语**——本章引入的术语就地定义，全文术语表收拢在 Part III；
> 4. **代码地图**——涉及的类逐个讲：职责、关键方法的真实签名、为什么这样设计；
> 5. **表结构**——涉及的表逐列解读（列名、类型、为什么存在、谁写谁读）；
> 6. **生命周期与并发契约**——对象/数据的完整生命线、线程安全承诺、失败语义；
> 7. **测试怎么钉住它**——列出守护本章行为的测试类与关键用例，缺陷定位从这里反查。
>
> 写作铁律：每个断言落到真实类名/方法名/列名；只写现状；「为什么」与「是什么」同段出现。

## 第 1 章 全景鸟瞰

**本幕回顾**：Part I 全部十一幕。本章是地图——先建立整体，再进细节。

**设计问题**：在一个 AI 应用里，系统提示词是变更最频繁的「代码」，却没有一套回归纪律护着它——改一句话术，可能让模型在该调工具时不调了、在结构化输出里丢字段、忘说必须说的话。传统单元测试覆盖不了这个问题，因为被测对象是模型的概率行为；人工回归覆盖得了但不可持续。框架的答案：**把「行为」变成可确定比对的结构化对象（指纹），把「变更」变成一次真实调用的重放，把「一条用户任务」变成可对齐的回放单元，把「裁决权」留给人，把「记录权」交给旁路。**

**概念与术语（是什么 / 刻意不是什么）**：

- **是**：JVM 原生的 AI Agent 行为回归测试框架。旁路录制真实 LLM 交互 → 确定性四维指纹与基线 → prompt 变更按依赖图裁剪影响集 → 真实调用重放判定差异 → 按调用点对齐任务链 → 人工裁决；基线可导出为验收包做跨环境交付验收。
- **刻意不是**：①不是监控/观测平台（落库是为了回归，不是为了看板）；②不是提示词管理器（不管理 prompt 内容，只管行为）；③不评判好坏——只陈述「与基线有无差异」，方向判断留给人；④判定链路 100% 确定性，永不引入 LLM-as-judge；⑤不是代理/网关——业务流量从不过它转发；⑥不驱动产品执行——录制靠旁路、验收靠验收人真实操作。

**代码地图（模块分层）**：Maven reactor 共 11 个构建节点，产出 8 个 jar：

```
Layer 4   spring-boot3-starter        spring-boot4-starter      （自动装配聚合）
               │  聚合 core+recorder+        │  聚合 core+recorder+
               │  sdk-ai1+storage-sqlite     │  sdk-ai2+storage-sqlite
Layer 3   sdk-spring-ai1         sdk-spring-ai2            （框架适配）
          cli（组合根：core+recorder+storage-sqlite+picocli）
               │
Layer 2   recorder（core + Disruptor + SLF4J API）          （异步旁路管道）
               │
Layer 1   core（纯 java.base，零外部依赖——发布卖点）         （模型/SPI/算法/判定）
          storage-sqlite（只依赖 core + SQLite JDBC，独立插件分支）（唯一存储后端）
```

铁律：**依赖单向，下层不感知上层**——core 里出现任何 `com.*`/`org.*` import 都是缺陷（CI 可 grep 验证）。

**代码地图（core 内部六包）**：`model/` 数据模型（InteractionRecord、DeterministicFingerprint、TaskChain、AcceptancePack、InvocationProfile…）、`spi/` 全部 SPI 接口、`algorithm/` 纯算法（分组、指纹、对比、基线、影响分析、重放执行、任务链派生与对齐）、`result/` 判定结果（Verdict、ComparisonResult、TaskAlignment 等）、`util/` 工具（RecursiveJsonParser 是全框架唯一的 JSON 解析/序列化真源）、`config/` 配置加载。

**表结构（五表总览，逐列解读见第 4 章）**：

| 表 | 角色 | 一句话 |
|----|------|--------|
| `interactions` | 账本 | 只追加的交互历史，38 列 + 5 索引，一切分析的原始数据 |
| `invocations` | 档案 | 每个调用点一行：现役基线指纹、待裁决候选、治理三列（审批人/时间/算法版本） |
| `invocation_template_versions` | 历史 | 每次转正/重建时被替换的旧基线按模板版本归档，rollback 的数据来源 |
| `prompt_texts` | 原文库 | template_hash → 提示词原文（hash 不可逆，原文只能落这里） |
| `graph_snapshot` | 快照 | 整图单行 JSON（`id='current'`），派生数据、随时可重建 |

任务与验收都没有自己的表：任务链是从 `interactions` 现场派生的视图（第 11 章），验收包是一个 JSON 文件（第 12 章）——派生事实不建实体表是全库的统一哲学。

**生命周期（两条主线，细节散见各章、总图在 Part III 第 14 章）**：

```
一次交互的一生：
业务线程: chatClient.call() ──→ SDK mapper 映射为 InteractionRecord
              │（业务调用照常返回，全程零等待）      │
              ↓                                    ↓
        录制管道: 脱敏 → 兜底 → RingBuffer 入队（满则丢弃计数）
              → 消费线程缓冲 →(满批/5s 定时)→ enrich 补派生字段
              → SQLite interactions 落库
              → CLI: status 画像 / baseline 建档 / replay 重放比对 / verify 验收比对
              → 差异落为候选 → approve/reject → 基线转正/作废，旧基线归档
```

**测试怎么钉住它**：全量回归当前 797 条（core / recorder / storage / cli 含 6 条私有 e2e 门控跳过 / 两代 SDK / 两 starter，随演进浮动），仓库根 `mvn -B test` 必须全绿。测试文化三条：测契约不测实现（跨组件边界逐字段对齐）、确定性契约必测（排序稳定、转义往返、计数闭合）、错误路径必测（专用异常精确断言）。

**设计原则速查**（各章现场展开）：R1 core 零依赖 / R2 面向 SPI / R3 插件平等 / R4 配置驱动 / R5 单向依赖 / R6 每接口 ≤5 方法 / R7 图纯内存 / R8 零侵入 / R9 确定性优先 / R10 退化不中断。

---

## 第 2 章 接入面

**本幕回顾**：第 0 幕（两个坐标一行配置）与第 8 幕（两条支线）。

**设计问题**：回归防护的价值与接入成本成反比——接入要改业务代码，大多数团队第一天就会放弃。所以接入面的目标是：**Spring Boot 用户改零行业务代码；非 Boot 用户改三行；全路径共用同一套存储与判定语义。**

**概念与术语**：自动装配（classpath 探测 + 条件退出）、装饰器（包住 ChatModel 而非替换它）、录制上下文（线程绑定的声明作用域）、最小配置面（配置项是发布后的永久契约，按需最小开放）。

**代码地图**：

- `AgentAssert4jProperties`（starter 配置，前缀 `agentassert4j`）：`enabled`（默认 true，false 时自动装配整体退出、不建任何 Bean）+ `database`（默认 `agentassert4j.db`）+ `invocationId`（默认空串，应用级默认调用点标签）。刻意只有这三项——配置项是永久契约。
- `AgentAssert4jAutoConfiguration`（boot3 在 `io.github.agentassert4j.springboot`，boot4 在 `io.github.agentassert4j.springboot4`，结构同构）：装配条件三个——classpath 有 `ChatModel`（无则静默退出）、`agentassert4j.enabled=true`（缺省视为 true）。产出三个 Bean：
  1. `SqliteStorageRepository`（`destroyMethod="close"`，建库后立即 `initialize()` 建表）；
  2. `InteractionRecorder`（`destroyMethod="stop"`，构造后立即 `start()` 启动管道）；
  3. `static` 的 `RecordingChatModelPostProcessor`——**BeanPostProcessor，把容器内所有 ChatModel 包上 `RecordingChatModel`**，已包装的不重复包。static 的原因：BPP 必须在本配置类实例化之前注册，避免容器启动顺序告警；recorder 通过 `ObjectProvider` 延迟解析，保持启动顺序干净。
  - 用户自带 `StorageRepository`/`InteractionRecorder` Bean 时 `@ConditionalOnMissingBean` 让位——自带录制器需自行 start。
  - **启动期失败语义（有意决策）**：存储初始化失败会中断宿主启动——「录制框架静默失效（用户以为在录实际没录）」比启动失败更危险；不接受此语义的环境用 `enabled=false` 显式关闭。
- `RecordingChatModel`（两代 SDK 各有一个，包 `springai1`/`springai2`）：装饰器，`call()` 计时捕获上下文后透传；`stream()` 在**调用线程**捕获 `RecordingContext` 闭包（聚合回调发生在异步完成信号线程，ThreadLocal 不可达），用 `MessageAggregator` 聚合完整响应后录制，TTFT 取首个 chunk。录制失败只 WARN 不抛——业务调用永远不被录制问题打断。1.x 的粒度说明：默认在 ChatModel 内部执行完整工具回路，装饰器视角一次 call = 完整工具回合（初始请求 + 最终聚合响应）。
- `RecordingContext`（`AutoCloseable`）：`start(sessionId)` 开启作用域，`withInvocationId/withTemplateId/withMetadata` 链式声明，`close()` 恢复外层（可嵌套）。本质是栈式 ThreadLocal——**仅在声明线程内可见**，Reactor 链中从异步线程发起的调用取不到上下文，流式标注需在发起 stream() 的线程作用域内完成。`withMetadata` 也能携带任务键声明（第 11 章的 `taskKey`）。
- 两代 SDK 差异：包名各自隔离（`springai1`/`springai2`、`springboot`/`springboot4`，两代坐标同名互斥必分模块）；缓存 token 在 ai1 走反射尽力提取、ai2 走 `Usage` 接口直读；Spring AI 2.x 的工具循环移到 ChatClient 的 Advisor 链（ChatModel 之上），装饰器天然逐轮可见。
- **JDK8 手动路径**（第 8 幕老陈的系统）：不引 starter，手动装配 core + recorder + storage-sqlite 三个 jar，在自己的 LLM 调用出口组装 `InteractionRecord` 后调用 `recorder.intercept(record)`，并自行 `start()/stop()`。core 是全框架唯一零依赖模块，这是 JDK8 客户能接入的原因。

**表结构（接入面写进记录的标记）**：`recorder_version` 列写 SDK 版本串（如 `agentassert4j-sdk-spring-ai1`），`api_protocol` 固定 `openai-chat`——描述落库数据的协议形状而非上游供应商；`provider` 由模型名前缀启发推断（deepseek→deepseek、gpt/o1/o3/o4→openai、claude→anthropic、qwen/qwq→qwen、gemini→gemini、llama→ollama、其余归 custom）。

**生命周期与并发契约**：Bean 关停顺序由 Spring destroy 方法保证（close 存储在 stop 录制器之后——录制器 stop 会先 flush 剩余数据再关 Disruptor，超时 10 秒强制关闭）。用户自备录制器注册 Bean 时必须显式设 destroy 方法名为 `stop`（Spring 的 destroy 推断只认 close/shutdown，否则关停后 flush 线程在 Windows 上锁住库文件）。

**测试怎么钉住它**：每个 starter 7 条 `ApplicationContextRunner` 装配测试（包装生效、真管道落库、enabled=false 退出、无 spring-ai 静默退出、不双重包装、自定义路径建库、用户 Bean 优先）；SDK 层有异步上下文传播测试（`publishOn` 切线程后仍能取到闭包捕获的上下文）。

> **编排观察（已落地）**：录制装饰器在请求的 options 副本上为每个工具回调换装纯观察装饰器——
> 内部回路每次真实执行工具的同一时刻，名称/参数原文/结果原文按序记入缓冲并合并进该条记录的 toolCalls
> （参数经 RecursiveJsonParser 解析后用 ArgTypeUtil 同词表派生类型，与 native 路径可比）。100% 委托透传、
> 装饰失败静默退回原请求、业务对象零触碰。1.x 覆盖默认内部执行姿势（2.x 直调 ChatModel 的内耗姿势同享）；
> ChatClient 驱动的逐轮姿势响应自带 toolCalls，观察缓冲自动让位不双计。覆盖面诚实标注：注入到 options
> 的回调（Spring bean/provider 惯用法）全部可见；未经 options 的私有执行通路不可见。HTTP 层拦截
> 为预留终局，无排期。

---

## 第 3 章 录制管道

**本幕回顾**：第 1 幕（看不见的抄写员）。

**设计问题**：旁路录制有三难——不丢、不阻塞、不 OOM。三者不可兼得：要完全不丢就得阻塞业务或无限缓冲。框架的取舍是**不阻塞 + 不 OOM，放弃不丢，但每一笔丢失都记账**（丢弃不阻塞业务请求是最高原则）。

**概念与术语**：RingBuffer（定长环形缓冲，入队纳秒级）、批量落库、计数闭合（每个到达的记录去向可审计）、脱敏（内容改写）与深拷贝（线程边界隔离）的分离。

**代码地图**：

- `InteractionRecorder`（实现 `RecordingInterceptor` SPI）——管道入口：
  - `intercept(record)`：**先兜底再入队**——`recordId` 缺失时生成 UUID（存储层防重放语义依赖其唯一性）、`sessionId` 缺失时退化为独立会话（取 recordId，保住 NOT NULL 约束不炸批）；**到达即计数**；深拷贝脱敏；透传进程内单调 `seq`（丢弃造成的空洞合法，`(session_id, seq)` 是确定性排序键）；`tryNext()` 非阻塞入 RingBuffer，满时（`InsufficientCapacityException`）与发布异常均丢弃并计数 + WARN。整个方法 `synchronized`——与 `stop()` 互斥，否则无锁窗口内关停会把事件发布进已停摆的 RingBuffer（记录永久滞留且计数不闭合）。
  - `start()`：构建 Disruptor（`ProducerType.MULTI` 多生产者、`SleepingWaitStrategy`、守护线程工厂）+ 单线程定时 flush 调度器（默认 5 秒）；全局开关 `enabled=false` 时直接 no-op，不启动管道（生产打包形态）。
  - `stop()`：先 flush 剩余数据、停调度器，再 `disruptor.shutdown(10, SECONDS)`。
- `BatchWriteHandler`（Disruptor `EventHandler`）——消费侧：
  - `onEvent`：`synchronized(buffer)` 内判断——缓冲达到 `maxBufferSize`（默认 500）时**丢弃新记录** + WARN（OOM 保护），否则入缓冲；缓冲达到 `batchSize`（默认 100）或本批次结束时触发 flush。
  - `flush`：交换出缓冲快照后清空原缓冲 → `enrich` → `saveInteractions` → 成功 `written` 计数；失败 `failed` 计数 + ERROR 日志，**不重试**（重试会阻塞消费线程，违背零侵入）。
  - `enrich`：落库前补全派生字段——`invocation_key` 列有 NOT NULL 约束，缺失时用解析器派生值回填（否则整批 INSERT 失败）；**已有值不覆盖**（上游显式设置的调用点键优先）；单条补全失败不拦截落库（原始交互数据是真源）。指纹提取含响应体 JSON 解析，因此在消费线程执行，不占用业务线程。
- `DataSanitizer`——脱敏器：
  - **无条件深拷贝**：脱敏配置只决定内容是否改写，不决定是否拷贝——消费线程的 enrich/序列化与上游对原对象的任何后续读写之间不得共享可变状态。`copyRecord` 复制全部 30 余个字段，toolCalls 逐个深拷贝（arguments 值树递归）、previousTurns 元素级深拷贝。
  - 脱敏范围：`toolCalls.arguments`（Map 任意深度递归，按键名忽略大小写匹配——嵌套结构里的敏感键是主阵地）；`toolCalls.result`（对含 JSON 的字符串做逐字符扫描的键值替换，DROP 策略回溯删除键名前空白与逗号，保证产物恒为合法 JSON）；`userInput`/`modelResponse` 可配置，**默认不脱敏**（改写会破坏回归重放的输入保真）。
  - `SanitizeStrategy` 三策略：`MASK`（默认，替换为 `***`）、`HASH`（SHA-256，保持唯一性不可逆）、`DROP`（整键删除）。
- `RecorderConfig`（不可变，builder 构建）：`enabled=true`（总开关）、`ringBufferSize=16384`（向上取 2 的幂，上限 2^30）、`batchSize=100`、`flushIntervalMs=5000`、`maxBufferSize=500`（下限均钳 1）、`defaultInvocationId`（应用级默认声明）、采集门开关、脱敏四项（`sensitiveFields` 默认空、`sanitizeStrategy=MASK`、`sanitizeUserInput=false`、`sanitizeModelResponse=false`）。`batchSize` 大于 `maxBufferSize` 时钳位自愈（错配不丢数据）。

**表结构（写侧与 NOT NULL 的契约）**：`interactions` 六个 NOT NULL 列各有兜底链——`record_id`→UUID 兜底、`session_id`→recordId 兜底、`invocation_id`（声明位，可空串）→捕获侧落列、`invocation_key`→enrich 派生兜底、`tool_calls`/`has_tool_calls` 恒写（空表 JSON 与 0）。兜底的意义：任何一条记录都不会因为上游缺字段而炸掉整批 INSERT。

**生命周期与并发契约——计数闭合公式**：

```
recorded（到达即计数） = written（批量写成功）
                      + dropped（生产侧 RingBuffer 满/发布异常 + 消费侧缓冲超限）
                      + failed（批量写失败，不重试即丢）
```

每个到达记录的去向必然落在四类之一：写入、丢弃（生产侧/消费侧）、失败、采集门过滤（filtered，策略预期——dropped 是故障、filtered 是决策）；计数器由录制器持有注入 handler（stop→restart 换新 handler 不破裂闭合）；`getDroppedCount()` 聚合生产侧与消费侧两个线程域的计数。**关闭时序**：存储的 `initialize/close` 与写路径共用实例监视器——flush 进行中不得关闭或置换连接。

**测试怎么钉住它**：recorder 模块全套（计数闭合、采集门、intercept 与 stop 并发、批写失败不丢账、脱敏往返、错配钳位、深拷贝隔离等）。代表性契约：计数闭合（阻塞仓库 + 大批量突发验证 written+dropped+failed==recorded，filtered 另列）。

**采集门**：默认全量录制（`recordUndeclaredChat=true`——任务链完整性优先于流量卫生，链条终点的最终回答组装往往正是纯文本调用）。设为 `false` 时（超大流量场景的量级卫生选项）只放行「声明了 invocationId 或 templateId」或「响应含可见工具调用」的记录，滤了多少条计入独立的 `filtered` 计数器，与 dropped 严格分列（dropped 是故障，filtered 是策略决策），且首条被滤记录与每满 100 条各发一次 WARN——静默丢数据比丢数据本身更危险。配置了应用级默认调用点标签（`RecorderConfig.defaultInvocationId`，starter 属性 `agentassert4j.invocation-id`）时，未声明记录先落到默认声明位（不受门状态影响）。总到达闭合为 `recorded + filtered`。另有录制总开关 `enabled=false`：录制器不启动管道、不消费记录（生产打包形态；starter 侧 `agentassert4j.enabled` 条件装配同语义）。

---

## 第 4 章 存储层

**本幕回顾**：第 1 幕（落库）、第 2 幕（画像与建档）、第 6 幕（归档与回滚）。

**设计问题**：一个单文件 SQLite 要同时扮演四个角色——只追加的录制账本、治理档案（现役基线+候选+审批留痕）、历史库（归档回滚）、派生数据缓存（图快照、提示词原文）——而且要跨进程（录制在应用进程、裁决在 CLI 进程）、跨语言可读。v1 的既定决策：**SQLite 是唯一存储后端**（零基础设施部署叙事），mysql/pg 是双向门延迟项。

**概念与术语**：SPI 六域拆分（写/查/调用点/模板原文/图/归档，按读写职责分接口）；契约版本（`PRAGMA user_version`）；三层列结构（概念层=跨协议稳定的概念数据 / 原文层=`*_raw` 逐字保留 / 吸收层=metadata JSON 承接未预见扩展）。

**代码地图**：

- **SPI 接口面**（`core` 的 `spi/` 包，每接口 ≤5 方法）：

| 接口 | 方法 | 数 |
|------|------|----|
| `InteractionWriteStore` | `saveInteraction` / `saveInteractions` | 2 |
| `InteractionQueryStore` | `findByInvocationId` / `findByInvocationKey` / `findByTemplateHash` / `findInvocationKeysByTemplateHash` / `findBySessionId` / `findAllSessionIds` | 6 |
| `InvocationStore` | `saveInvocationProfile` / `findInvocationByKey` / `findAllInvocations` | 3 |
| `TemplateTextStore` | `saveTemplateText` / `findTemplateText` | 2 |
| `GraphStore` | `saveGraph(json)` / `loadGraph()` | 2 |
| `TemplateVersionArchiveStore` | `archiveTemplateVersion` / `findArchivedVersion(invocationKey, versionTag)` / `findArchivedVersions(invocationKey)` | 3 |

  `StorageRepository` 是聚合门面：`type()` / `initialize()` / `close()` 加上继承全部六域。录制管道只依赖 `InteractionWriteStore`（最小知识面）。插件平等：任何实现这六个接口的存储都可接入（R3），优先级链路里没有 `if (type=="sqlite")` 之类的硬编码（R4）。

- `SqliteStorageRepository`（包 `io.github.agentassert4j.storage.sqlite`）：
  - **全部公开方法 `synchronized`**：单连接策略下，多个 flush 源（批量/定时/手动/stop）并发进入会在事务层面互相交织吞批次——串行化是正确性前提，SQLite 本地写也无并发收益。
  - `initialize()`：建父目录 → `DriverManager.getConnection("jdbc:sqlite:"+dbPath)` → `setAutoCommit(true)` → `SchemaMigrator.migrate()`；失败时清理已开连接并抛 `StorageException`（starter 装配下即中断启动，见第 2 章的有意决策）。
  - `saveInteractions(list)`（录制管道主入口）：事务序列 = 关 autocommit → 逐条 INSERT → commit；异常时**先 `rollback` 再恢复 autocommit**——顺序不能反，因为对 sqlite-jdbc 的未决事务执行 `setAutoCommit(true)` 是一次隐式 COMMIT，会把半批脏数据悄悄落库。
  - `saveInteraction(单条)`：语义化单条入口，顺带 `persistTemplateTextQuietly`——把系统提示词原文写进 `prompt_texts`（`INSERT OR IGNORE` 首写为准；hash 不可逆，原文不落即永久丢失）。
  - 查询：全部 `ORDER BY timestamp, seq, record_id`（确定性排序键，平局可决胜）；`findInvocationKeysByTemplateHash` 支撑影响分析的「谁在用旧提示词」反查（返回调用点键），任务域冻结重放的受影响裁剪同用此查询。
- `JsonMapper`（同包包私有类）：`toolCalls/turns/fingerprint/invocationProfile/archivedTemplateVersion` 与 JSON 的双向映射，构建在 `RecursiveJsonParser`（全框架唯一 JSON 真源）之上，容器一律 `LinkedHashMap/LinkedHashSet` 保插入序——**序列化字节可复现、可 diff**。指纹的 `fingerprint` 列 NOT NULL，空指纹与 null 的约定是 `"{}"`↔null 对写读对称。
- `SchemaMigrator`（三段式）：库版本 **高于** 支持值 → 拒开（旧代码不得静默误读新语义）；**等于** → 直接返回；**低于** → 执行 `Schema.ALL_DDL` 建表并 `PRAGMA user_version = 1`。当前契约版本固定为 1：预发布阶段零兼容——schema 变更 = 删库重建，不存在任何「旧版迁移」代码。

**表结构（五表逐列）**：

- `interactions`（38 列，只追加）——按组读：
  - **身份**：`record_id`(PK)、`session_id`、`timestamp`、`seq`（进程内单调，与 timestamp 组成确定性排序键）、`invocation_id`（声明标签位，可空串）/`invocation_key`（调用点键，NOT NULL，enrich 兜底）；
  - **提示词三元组**：`template_id`/`template_hash`（可空——无 system prompt 的调用点由解析器回退到请求锚点）/`variables_fingerprint`（预留未接线）；
  - **模型与部署**：`api_protocol`/`provider`/`model`/`served_model`/`endpoint`（预留未接线）——基线跨模型/部署不可比，必须落列；
  - **请求保真**：`user_input`、`turn_index`、`tools_definition`、`sampling_params`、`model_request_raw`（预留：ChatModel 抽象层不可得，HTTP 层方向无排期）、`multimodal_input`/`multimodal_content`；
  - **响应保真**：`finish_reason`、`model_response`（可空——纯工具调用响应无文本）、`model_response_raw`（同上预留）、`tool_calls`/`has_tool_calls`；
  - **遥测**：`input_tokens`/`output_tokens`/`cache_read_tokens`/`cache_write_tokens`/`reasoning_tokens`/`usage_raw`（供应商原始 usage 逐字保留）/`latency_ms`/`ttft_ms`/`cost_usd`（价格快照查得到才计价，查不到保持 null 不编造）；
  - **多轮与吸收**：`previous_turns`（JSON）、`metadata`（扩展属性池——任务键声明 `taskKey` 住这里，见第 11 章）、`recorder_version`。
  - 5 个索引：`idx_session_seq(session_id, seq)`（复合前缀同时覆盖单列查询）、`idx_invocation_id`、`idx_template_hash`、`idx_invocation_key`、`idx_timestamp`（第六个索引 `idx_archived_invocation` 在 `invocation_template_versions` 表上）。
- `invocations`（15 列）：`invocation_key`(PK)、`label`（声明标签，可空）、`template_hash`（建档时模板哈希）、`invocation_name`、`invocation_type`（`TOOL`/`PURE_CHAT` 视图分类）、`fingerprint`(NOT NULL 现役基线)、`candidate_fingerprint`（可空候选）、`baseline_status`（默认 `BASELINE`）、`version_tag`、`algo_version`、`param_signature`、`approved_by`/`approved_at`（治理留痕）、`total_records`、`updated_at`。
- `invocation_template_versions`（9 列）：自增 `id`（同调用点同 tag 重复归档时「最近归档者胜」的 tiebreaker）+ `invocation_key`、`template_hash`（版本↔模板文本经 prompt_texts 可反查）+ 指纹与治理三列快照 + `archived_at`；索引 `idx_archived_invocation`。
- `prompt_texts`（3 列）：`prompt_hash`(PK)/`prompt_text`/`created_at`，首写为准。
- `graph_snapshot`（3 列）：`id`(DEFAULT `'current'`，整图单行)/`graph_json`/`updated_at`——图是派生数据，快照仅为巡检，可随时重建。

**生命周期与并发契约**：库的一生 = `initialize`（建表/迁移）→ 读写（全程单连接串行）→ `close`。关停顺序由持有方保证（starter 的 destroy 链、CLI 的 finally）。事务只出现在 `saveInteractions`；单条写走 autocommit。

**测试怎么钉住它**：storage 全套。代表性契约：38 列与占位符逐一核对、特殊字符与敌对内容（NUL/控制符/深嵌套）写读往返逐字保真、并发 flush 全量落库、失败注入后 autocommit 恢复且无半批提交、迁移三段（高版本拒开/同版跳过/低版本盖戳）、归档 tiebreaker。

---

## 第 5 章 数据模型与分组

**本幕回顾**：第 1 幕那几百条他看不懂的记录、第 2 幕的自动归类与代号。

**设计问题**：重放比对的基本单位是什么？答案不能是「单次 HTTP 调用」（同一段业务流程里有多次调用），也不能是「业务系统」（太粗）。框架的三分模型：**用例（case）= 一条录制交互 = 回归最小单元**，期望永远现场重提、用例之间无等价关系；**调用点（invocation）= 产生调用的模板/代码位置 = 变更单元与治理主体**（治理对象 = 调用点的模板版本史）；**视图（view）= 无身份语义的索引**（形状、标签、时间都是视图维度，视图粗细不影响判定对错）。身份不能靠人工登记（第一天就会被放弃），必须从交互自身确定性推导。

**概念与术语**：invocationKey（调用点键，从记录确定性派生的唯一身份）、invocationId（调用点声明标签——业务身份，可空，不参与判定）、templateHash（模板哈希=系统提示词 SHA-256）、paramSignature（参数类型签名，视图列）。

**代码地图**：

- `InteractionRecord`（`model/` 包 POJO，30 余个字段与 `interactions` 表 38 列一一对应，见第 4 章列组）——全框架的数据交换货币：SDK 捕获产出它、录制管道搬运它、CLI 分析消费它。`previousTurns` 是 `TurnContext` 列表（role/content/toolCallId/toolName/toolArguments，多轮历史与工具结果帧）；`ToolCall` 持有 toolName/toolCallId/arguments(Map)/argTypes(Map)/result/success。
- `InvocationResolver`（纯静态函数，无状态）——调用点身份的唯一真源，`resolve(record)` 派生逻辑（优先级从高到低，任一命中即停）：
  - **锚点 1 声明锚点**：记录声明了 invocationId → invocationKey = `invocation:<标签>`，有模板时以 `:<templateHash>` 细分同一标签内的多模板调用位置。形状不参与身份——同一声明下多形状分支的差异交给指纹暴露（这正是回归要抓的对象）。
  - **锚点 2 模板锚点**：未声明但 templateHash 非空 → invocationKey = `template:<templateHash>`。**工具调用与纯对话同分支**——形状（工具名/参数签名）已退出身份、降级为视图维度（`TOOL`/`PURE_CHAT` 分类与 paramSignature 列仅作展示与选例）。
  - **锚点 3 请求锚点兜底**：无声明无模板（无 system prompt 的应用）→ `adhoc:<sha256(modelRequestRaw)>`，退而 `adhoc:<sha256(userInput)>`，双缺失 `adhoc:no-anchor`（程序化构造防御）。键是溯源身份不是判定输入，因此输入派生键合法。
  - **键文法单射**：标签/模板 hash/请求哈希全部经百分号编码（`% : + [ ] ,` 转义）后入键，前缀命名空间（`invocation:`/`template:`/`adhoc:`）互相隔离——用户可控字符串永不与文法结构字符混淆，任何团队的命名规范零约束零碰撞（黄金键测试钉住字面值，含冒号注入对抗用例）。
  - **invocationKey 永不进指纹**：指纹维度保持输出侧，输入侧（键、变量、历史）不参与判定——判定正确性与声明质量解耦，零声明应用（agent loop 主形态）是一等公民路径。
- `InvocationProfile`（调用点画像，对应 `invocations` 行）：身份列（invocationKey 主键、label、templateHash）+ 视图列（invocationName、invocationType、paramSignature）+ 治理列（fingerprint 现役基线、candidateFingerprint 候选、baselineStatus、versionTag、algoVersion、approvedBy/approvedAt、totalRecords）。
- **统一身份空间**：声明与否共用同一派生文法、同一存储列（`invocation_key`）、同一图节点空间——影响分析、依赖图、治理三命令不再区分「声明/派生」双轨。标签只是视图：一个标签可覆盖多个调用点键（同标签多模板步骤），CLI 的 `--invocation` 三写法（业务标签 / 完整调用点键 / 唯一前缀）等价解析。画像属于可从 interactions 全量重建的派生数据（BaselineService 重复执行安全）。

**表结构**：`invocations` 16 列见第 4 章；`interactions` 的 `invocation_id`（声明位）与 `invocation_key`（派生键，NOT NULL，enrich 兜底）两列是身份落库点。

**生命周期与并发契约**：解析是纯函数（无状态、无 IO），可在任何线程调用；同一记录永远得到同一 invocationKey——这是「派生规则冻结为身份契约」的前置性质。派生规则一经发布即冻结：任何变更 = 身份纪元事件（历史基线全部失配），必须走显式设计。

**测试怎么钉住它**：黄金键测试（`InvocationResolverTest`）钉住派生规则的字面键值（声明/模板/adhoc 三锚点、命名空间隔离、冒号注入对抗、全缺失 no-anchor 兜底）、视图列归一化（大小写）、双工具接力会话产 HIGH 边（图测试复用同一派生）。

---

## 第 6 章 四维指纹

**本幕回顾**：第 2 幕的四维概念介绍、第 3 幕 CHANGED 的「工具集变了」、第 8 幕规则正则钉住工具选择。

**设计问题**：LLM 输出是概率性的，两个「语义相同」的回答逐字必然不同——怎么让「行为有没有变」成为一个程序可以确定回答的问题？框架的答案：不比内容，比**结构**——把一次交互投影为四个维度的结构化摘要，投影规则是纯函数，同样输入永远得到同样指纹（R9）。

**概念与术语**：四维指纹（`DeterministicFingerprint`）；声明式规则（维度 3/4 靠用户声明，框架不自动推断语义）；类型词表（参数类型的封闭词汇表）。

**代码地图**：

- `FingerprintExtractor`（静态纯函数）两个入口：`extract(record)`（维度 3/4 空集口径）与 `extract(record, rules, invocationId)`（注入声明规则——建档与重放判定**必须**走同一入口，规则口径才同源）。逐维提取算法：
  - **维度 1 工具调用**：`toolCallSet`（Set，忽略调用顺序）；`toolParamTypes`（多次调用的参数类型合并，键与值统一小写——与归一化策略对齐）。
  - **维度 2 输出结构**：响应为空 → `text/plain` + 空集 + 数量级 0；能解析为 JSON（Map/List）→ `application/json` + 字段路径集 + 字段类型映射（`RecursiveJsonParser.extractFieldPaths/extractFieldTypeMap`）；纯文本 → 退化为长度数量级 `log10(len)+1`（1–9 字→1、10–99→2、100–999→3）——纯文本的逐字对比必然假红，数量级只抓「量级跳变」。
  - **维度 3/4**：从 `InvocationRulesConfig` 按调用点标签加载（`requiredKeywords`/`forbiddenKeywords`/`regexPatterns`/`behaviors`），外加 `hasError`（工具调用存在 `success=false` 时为真）。
- `ArgTypeUtil.derive(arguments)`：参数类型六词表 `string / number / boolean / object / array / null`（键小写），按**值的运行时形态**派生。捕获侧（SDK 填充）与重放侧（执行器从响应派生）共用此唯一实现——两侧词表一旦漂移，参数类型维就是无休止的假阳性。
- `InvocationRulesConfig`（`agentassert4j-rules.json`）：`invocations.<invocationId>.{requiredKeywords, forbiddenKeywords, regexPatterns[{pattern,description}], behaviors}`；解析失败安全退化为空配置；`InvocationRule` 不可变，未声明调用点返回共享 `EMPTY`。优先级：rules.json > 主配置内联 > 默认空。
- `RegexPattern.matches`：`Pattern.compile(pattern).find()`；**非法正则按不匹配处理**（fail-closed）——坏规则在每条重放里都表现为可见的不匹配信号，而不是静默放行或崩溃。
- `BehaviorChecker`：内置 8 个行为校验（`mustUseChinese`/`mustUseEnglish`/`returnsEmptyOnError`/`returnsErrorCode`/`noError`/`jsonOutput`/`nonEmptyOutput`/`containsCjk`），语言类判定用码点扫描而非正则（`.` 默认不匹配换行，多行中文输出会被正则误判）。**未知 behavior 默认通过**（不误报比漏报好），但 CLI 加载规则时对未知行为名显式告警并列出合法名单——笔误不再静默放行。

**关键语义——维度 3/4 的判定方向**：声明的规则**随基线指纹存档**，判定时取出基线声明的规则、对**当前输出的文本**做校验（关键词 `contains`、禁词、正则 `find`）。也就是说维度 3/4 是「基线声明、当前答卷」，不是两侧集合比对。第 12 章的验收包不携带规则文件，正是靠这个非对称语义：包内指纹自带声明集，验收侧无需 rules.json 也能校验维度 3/4。

**为什么模板不在四维里**：模板（系统提示词）与注入的 skill 文案是**输入变量**——回归测试里被替换、被修改的正是它，拿它参与比对等于「比谁改过」，永远不同、没有信息量。框架测量的是行为后果：还调不调同样的工具、输出结构变没变、声明规则守不守。模板的真实角色有两个：身份锚点（同一份提示词归同一组，见第 5 章）与请求重建素材（重放时原样带回历史上下文，见第 9 章）。由此有个推论：MCP 工具的 name / description / schema 就是它的「提示词工程面」——修改工具描述与修改提示词是同一性质的变化，都会经由行为维度的变化被检出（能力来源无关性的故事版见第 2 幕收尾）。

**表结构**：四维指纹以 JSON 形态存于 `invocations.fingerprint` / `candidate_fingerprint` / `invocation_template_versions.fingerprint`（`JsonMapper` 序列化，LinkedHashMap 保序）；存档指纹只作展示与审计，**任何对比一律现场重提**，不消费存档值。

**生命周期与并发契约**：提取是纯函数；规则配置进程内加载一次，运行期不变——指纹的确定性依赖「同一规则文件 + 同一提取代码」。

**测试怎么钉住它**：提取的确定性（同记录同指纹、JSON/文本分岔、数量级边界）、规则注入对称（建档与重放同口径）、六词表两侧对称、非法正则 fail-closed、未知行为告警。

**权重与阈值**：四维以 40/25/20/15 加权（无声明维度动态升权）折算成 0–1 的展示分——它只用于多差异场景的排序辅助，不参与判定；判定是二值的（第 8 章）。

---

## 第 7 章 基线治理

**本幕回顾**：第 2 幕（建档盖章）、第 3 幕（approve/reject 裁决）、第 6 幕（回滚与重建）。

**设计问题**：基线是「团队认可的正确行为」的载体——它一旦可以被悄悄改写，整个门禁就不可信。所以治理层要回答：谁能让它变？每次变化留不留痕？改错了能不能回去？算法升级了旧基线怎么办？

**概念与术语**：画像三态（BASELINE 现役 / CANDIDATE 待裁决 / ARCHIVED 归档——归档不在画像状态里，而在归档表中）；盖章（审批人 + 时间 + 判定语义版本三件套随基线落库）；版本标签（v1, v2, v3…，与指纹一一对应）。

**代码地图**：

- `BaselineManager`（core，全部生命周期方法 `synchronized`——同一 JVM 内并发安全；跨进程并发写同一存储需调用方自行排他）。设计姿态写进类 Javadoc：**框架只报告差异（侦探），接受与否由开发者裁决（法官）**。
  - `approve(invocationKey, approver)`：候选转正（验收调用点的候选模板版本）。顺序是**旧基线先归档、候选再提升**——归档行快照的是旧基线自身的指纹与治理事实（审批人/语义版本），必须先于新审批信息写入。归档与保存是两步独立写入、无跨表事务：保存失败向上可见，重试时 `archiveIfAbsent` 去重守卫（同 tag 已在归档即跳过）保证不产生重复归档行，approve 可安全重放。版本标签经 `nextAvailableVersionTag` 递增并**跳过归档已占用的 tag**——任一 tag 在归档与活跃态之间始终只对应一个指纹，rollback 不产生歧义。
  - `reject(invocationKey)`：丢弃候选、保留旧基线；无候选抛 `IllegalStateException`（与 approve 对称）。**提示词的回滚是 git 的职责，不是测试框架的职责**。
  - `rollback(invocationKey, versionTag)`：从归档恢复——当前基线也先归档（若其 tag 未曾归档），然后恢复目标版本的**指纹与治理三列**（审批人与语义版本随基线一起回退：活跃行的治理事实必须始终描述当前基线自身的获批历史）。
  - `recordCandidate(baselineRecord, candidateFingerprint)`：重放对比非 PASS 时落候选。invocationKey 由解析器从基线记录**现场重算**；候选必须经持久层落库——重放与裁决通常不在同一进程，内存候选会让 approve 不可达。
  - `autoEstablishBaseline`（幂等建档：已有基线不覆盖）/ `reestablishBaseline`（`--force` 重建：被替换基线先归档留痕，版本按归档占用顺延；恢复出的旧语义基线会被重放入口的版本校验拒绝判定——属预期，再次重建即可）。建档以解析器产出为基底（invocation_name/invocation_type 等展示列来自解析派生，label/template_hash 自记录落列，裸画像会违反存储层 NOT NULL 契约）；指纹用三参提取（规则口径与重放同源）。
  - `stampApproval`（三条成为基线的路径共用）：`algoVersion = JudgmentSemantics.VERSION`；空白审批人归一为 **null**——`approvedBy=null` 是「未经审批链盖章」的异常信号，空白串落库会稀释该信号。
- `BaselineService`（cli，`baseline` 命令与重放前置共用）：遍历 `recordedInvocationIds`（存储规范序），幂等建档；`--force` 重建时打印破坏性警告（既有基线版本与审批人点名）且**只取一条可解析记录作重建材料**（逐条调用会让版本标签随记录数连跳）；单条记录解析失败跳过不拦截；建档后回填 `totalRecords` 为真实录制数。建档后还会对声明了规则的调用点做**种子断言**：用声明规则校验种子记录的响应文本，违例（缺必需关键词/禁词出现/正则不命中）逐条打印告警——不阻断，只是让「基线自身就不满足规则」在建档现场可见。
- CLI 命令面：`baseline --db --invocation --approver --force`；`approve/reject --invocation <目标> --all --approver`（**裁决前用 `FingerprintDiffRenderer` 渲染候选与基线的逐维差异**——replay 的 summary 是易失的进程输出，裁决常发生在另一进程另一时刻，渲染器把持久化的两份指纹摆到裁决者面前，补上「法官开庭时手里没有卷宗」的断档）；`rollback --invocation --version`（均必选）。

**表结构**：`invocation_template_versions` 9 列（自增 id 是「同调用点同 tag 重复归档时最近者胜」的 tiebreaker）——归档行是基线按模板版本的完整快照：调用点键、模板哈希、指纹、版本、语义版本、审批人、审批时间。

**生命周期与并发契约**：approve/reject/rollback/recordCandidate/establish 全部 `synchronized`；SDK 多线程接入都落在这一契约内。`JudgmentSemantics.VERSION`（当前 `det-v1`）在建立/批准/重建时盖章，重放入口校验版本一致，不一致（含未标记的历史行）拒绝判定。

**测试怎么钉住它**：三态流转全路径（approve 转 tag、reject 保基线、rollback 恢复治理三列、force→rollback 链路自洽）、归档去重与 tag 跳占、并发版本数不变量、空审批人归一、候选落库跨进程可达、破坏性操作留痕文案、种子断言（违例告警/合规不误报/禁用时关闭）。

**身份契约（已落地）**：派生规则冻结为身份契约——黄金键测试钉住 invocationKey 字面值（含冒号注入对抗用例），键文法对任意输入单射；建档/重建/守卫统一以落库 invocation_key 优先、现算兜底，存储键与现算键不分叉。`JudgmentSemantics.VERSION` 公开发布前恒定，判定语义变更在开发期以删库重建承接；发布后派生规则变更 = 身份纪元事件，走版本递增与专项设计。

---

## 第 8 章 对比与判定

**本幕回顾**：第 3 幕的 PASS/CHANGED 汇总行与「工具集合变了」。

**设计问题**：两个指纹摆在一起，「有没有差异、差在哪、算不算回归」必须是一个纯函数的确定输出——同一对指纹在任何机器任何时间得到同一判定（R9）。本章就是那个纯函数。

**概念与术语**：二值判定（PASS/CHANGED——任一维存在可行动差异即 CHANGED）、可行动差异（ignorableFields 归一化后仍不同的维度事实）、展示分（多维差异折算的 0–1 分，仅排序辅助、不参与判定）、可忽略字段（`ignorableFields`，已知噪声字段的白名单——归一化覆盖一切字段，含 error 类叶子名）、判定语义版本（裁决口径的版本戳，公开发布前恒定）。

**代码地图**：

- `DeterministicComparator.compare(baseline, current, currentOutput)`——判定 = 在 ignorableFields 归一化下逐维比对，**任一维度存在可行动差异即 CHANGED，否则 PASS**：

1. **工具调用维**：工具集（排序后集合相等）与参数类型映射逐一相等；
2. **输出结构维**：contentType 相同、纯文本长度数量级相同、字段集无增删（可忽略字段过滤后）、字段类型逐一相等——任一不满足即差异；
3. **内容规则/约束行为维**：基线声明、当前答卷（关键词 contains、禁词、正则 find；内置行为校验）。

历史上的「直判规则」（error 字段、字段删除、工具集变化直接判最高级）与 error 类叶子名词表随三态判定一并退役：二值语义下它们没有存在意义——error 字段就是普通的可行动差异，用户把它配置为可忽略即声明「它的出现不构成行为差异」，归一化覆盖一切字段。加权评分保留为展示分（无声明维度动态升权的四组权重不变），不参与任何分支判断。

- 四维计分（权重随声明情况**动态分配**——没声明的维度不参与摊分，避免「没配规则就天然丢 35 分」）：

| 维度 | 基础权重 | 计分 |
|------|---------|------|
| 1 工具调用 | 0.40 | 工具集匹配 0.7 + 参数类型匹配 0.3 |
| 2 输出结构 | 0.25 | 文本↔文本：数量级相等 1.0 / 差一档 0.7 / 更多 0.2；JSON↔JSON 同类型：contentType 0.2 + 字段集无增删 0.5 + 类型全对 0.3；contentType 不同：0 |
| 3 内容规则 | 0.20 | 基线有声明：必含词 0.4 + 禁词 0.3 + 正则 0.3（对当前输出校验）；无声明：1.0 不扣分 |
| 4 约束行为 | 0.15 | 基线有声明：`BehaviorChecker.checkAll` 全过 1.0 否则 0；无声明：1.0 |

  动态权重：两维都没声明 → 0.60/0.40/0/0；只有行为 → 0.50/0.30/0/0.20；只有规则 → 0.48/0.30/0.22/0。`ComparatorConfig` 当前只有 `ignorableFields` 一项（阈值硬编码在矩阵里，外部化由调用方组装注入）。

- `ComparisonResult`（`result/` 包）：逐维布尔/集合字段（toolCallMatch、paramTypeMatch、structureMatch、addedFields、removedFields、fieldTypeMatch、keywordMatch、regexMatch、behaviorMatch）+ score + verdict + summary（一句话人读摘要，逐项列出「工具集变化 / 新增字段 / 内容规则不匹配…」）。
- `FingerprintDiffRenderer`（cli 包私有）：把**持久化的**基线与候选指纹逐维渲染成差异行——工具集（新增/删除点名）、参数类型（逐键 值→值）、内容类型、字段集、字段类型、长度数量级、必含/禁含关键词、正则条数、约束行为、错误标记；只输出有差异的维度，全一致给一行确认。排序后输出（TreeSet/TreeMap），同样输入渲染结果字节一致。
- `JudgmentSemantics`（`VERSION = "det-v1"`）：判定语义的版本戳。**何时必须递增**：任何改变「同样差异得出什么判定」的变更——指纹维度口径（FingerprintExtractor）、分组键派生规则（InvocationResolver）、裁决矩阵与权重（DeterministicComparator）；仅增强捕获保真（新增遥测列、转义修正）或纯性能优化**不**递增。版本值一经发布不可重定义。

**表结构**：判定本身不落列——对比是纯函数；落库的是其后果（候选指纹入 `invocations.candidate_fingerprint`，状态转 CANDIDATE，见第 7 章）。

**生命周期与并发契约**：compare 无状态，可任意并发；判定输入必须来自同一次提取代码与同一规则文件（版本戳守卫保证跨版本输入不会悄悄混入）。

**测试怎么钉住它**：二值判定逐维（工具/参数/结构/规则/行为任一差异即 CHANGED、全同即 PASS）、枚举面两值契约、新增字段即 CHANGED、ignorableFields 覆盖一切字段（含 error 类叶子名）、动态权重只影响展示分、比较器异常隔离（对比抛异常不炸批量）、summary 与 diff 渲染的确定性。

---

## 第 9 章 重放执行（调用点域）

**本幕回顾**：第 3 幕（改提示词的勇气）与第 7 幕（门禁）的全部技术内容。

**设计问题**：重放本质是一个**控制变量实验**——被测变量只有新系统提示词，其余一切（历史输入、多轮上下文、工具定义、采样参数）必须与录制时一致，否则差异无法归因。围绕这个实验有三个工程难题：LLM 调用会超时/失败/被限流（批量回归的健壮性）；请求体必须满足 OpenAI 协议的严苛校验（一个缺失的 `tool_call_id` 会 400 拒掉整个请求）；CI 需要机器可消费的退出契约。

**概念与术语**：重放请求（新 prompt + 历史上下文的合成）、单次尝试预算（超时语义）、可重试集合（传输层白名单）、方言裁剪（数据驱动的参数省略）、证据报告（单行 JSON）。

**代码地图**：

- `LlmClient`（SPI，3 方法）：`chat(request, timeoutMs)` / `name()` / `isAvailable()`。Javadoc 钉死的超时契约：`timeoutMs` 是**单次尝试**的预算（连接与读取各自的上限）；任一次尝试超时立即抛 `LlmTimeoutException`，**不得重试**（预算已耗尽，重试只翻倍证据成本）；可重试失败仅限 HTTP 429/5xx 与连接被拒。core 只定义契约，实现在上层（cli 的 `OpenAiCompatibleClient`）。
- `RegressionTestExecutor`（core，4 构造参数：llmClient/comparator/baselineManager/rules）——`execute(baselineRecord, newSystemPrompt, userInput, config)` 单条流程：
  1. dryRun → 直接返回 SKIP 结果，不调 LLM；
  2. `buildReplayRequest`：新 prompt 设为 systemPrompt；历史用户输入、多轮上下文（**完整复制**——tool 轮的 toolCallId/toolName 是与原对话对齐的关联键，丢弃会被服务端拒绝；system 帧不注入，模板域由 systemPrompt 承载）、工具定义（从录制 JSON 数组原样拆装——**重放不带工具，模型无法发起工具调用，工具维指纹必然假阳性**；损坏定义跳过宁可不带）；
  3. **链式半重放（编排记录的专用路径）**：观察装饰产出的记录带完整编排与每轮结果，`execute` 在入口路由——结果道具齐备即走链式：拿基线录制的旧结果当道具，逐轮重建「当时输入」并比对响应的 tool_calls 与基线编排的下一片段（工具名 + 参数解析后严格相等；tool_call id 是关联键不参与），全部轮次匹配后末轮四维比对收口；分歧即停并输出「第 k 轮工具决策分歧」定位。合成帧（assistant 发起调用 + tool 结果）以合成 tool_call_id 关联、参数与结果内容无损——`TurnContext.toolArguments` 承载真实参数，历史录制帧仍以 "{}" 占位（协议校验只看结构）。结果道具缺失（录制时工具失败）退回单发重放；
  4. `llmClient.chat` 异常三分类：`LlmTimeoutException` → TIMEOUT 结果、`LlmApiException` → API_ERROR、其余 RuntimeException → ERROR（客户端编程错误也转为单条结果，批量不中断）；
  5. 成功后 `buildCurrentRecord` 构造当前记录（不持久化）：重放路径的 `ToolCall.argTypes` 由 `ArgTypeUtil.derive` 按同一词表补齐（与捕获侧对称，否则参数类型维必失配）；调用时刻遥测（served/finishReason/usage/latency）就地落位；
  6. **双侧都用三参提取**（基线记录与当前记录各自带规则提指纹）→ `comparator.compare`（第二参数是当前输出文本，供内容规则校验）；
  7. 非 PASS → `recordCandidate` 落候选（落库失败 SEVERE 留痕不中断批量）；`replayOutput` 透传候选原文（只存活于重放现场，裁决侧没有原文）。
- `OpenAiCompatibleClient`（cli，`llm/` 子包）：基于 JDK `HttpURLConnection`（Java 8 可用、零 SDK 依赖），兼容 OpenAI/DeepSeek/通义等 chat 格式。要点：
  - 端点尾斜杠归一；请求路径 `/v1/chat/completions`；重试 `DEFAULT_MAX_RETRIES=2`，指数退避 1s/2s；可重试集合 = 429/5xx/ConnectException，**其余 IO 故障直接抛 `LlmApiException`**（重试洗白只会放大耗时并让故障形态失真）；`SocketTimeoutException` → `LlmTimeoutException` 不重试。
  - 请求体手拼（转义统一走 `RecursiveJsonParser.escape`）：消息序列 system → previousTurns → user（多模态时 content 是原样注入的 JSON 数组）；**tool 消息前若缺「assistant 发起调用」帧则按已知 id/toolName 合成最小合法帧**（历史录制没有该轮的独立载体，arguments 以空对象占位）；缺失 callId 的 tool 帧跳过该轮并告警（保住其余用例）；`temperature` 为 null/非 finite 时不携带该成员（推理模型方言：发送 0.0 会被 400 拒绝）；`extraBodyFields` 作为顶层成员原样追加（DeepSeek 思考态等方言逃生舱）。
  - 响应解析统一走 `RecursiveJsonParser` 导航（choices[0].message.content / tool_calls / usage 子树 / 顶层 model / finish_reason）；usage 子树原文逐字存 `usageRaw`；缓存 token 取 `prompt_tokens_details.cached_tokens`、思考 token 取 `completion_tokens_details.reasoning_tokens`（**input_tokens 语义钉死为总处理输入 token**）；`finish_reason` 归一为枚举词表 stop/tool_calls/max_tokens/content_filter/other。
  - `ProviderDialects`（数据注册表，资源文件 `provider-dialects.json`）：规则 = `matchModelPrefix` + `dropParams`，当前仅收录「发送即报错」的方言（o1/o3/o4/gpt-5 → drop temperature）；命中时显式配置的参数被裁掉并**一次性 WARN**（点名 extraBody 逃生舱，防静默丢配置的排障黑洞）；快照损坏等同缺席，退化不中断。
- `ReplayRunner`（cli，8 构造参数含注入的 out/err/jsonMode）——`run()` 全流程顺序：
  1. `executionConfig.validate()` 钳位（timeoutMs 下限 1000——0 在 HttpURLConnection 语义里是无限等待；温度钳 0–2、非 finite 置 null）；
  2. `warnIfModelDiffers`：重放模型与录制模型不一致时告警（配置未指定模型时以 `llmClient.name()` 实际生效值比对，堵住「默认模型 ≠ 录制模型」的盲区）；
  3. 非 CI 且非 dry-run：`BaselineService.establishMissing` 自动建档（JSON 模式下用丢弃流吞输出）；
  4. `--invocation` 过滤解析（业务标签 / 完整调用点键 / 唯一前缀三写法，见第 13 章）；
  5. **图现场重建** + 快照留档（dry-run 只读不落盘），打印节点/边统计；
  6. 选例两模式：提供 `--old-prompt` 时走影响裁剪（`ImpactAnalyzer`，第 10 章；分析错误或冷启动 → exit 2 并打印分析消息），否则默认选例（全库按调用点分桶，每调用点取规范序尾部 N 条——最新，`--selection oldest` 取头部，`--max-cases` 定 N，默认 3）；
  7. 空用例 → exit 2；**CI 守卫**：用例集中有无基线的调用点 → 点名拒绝，exit 2；**判定语义守卫**：基线 `algoVersion` ≠ 当前 `JudgmentSemantics.VERSION`（含未标记历史行）→ 拒绝并指引 `baseline --force`，exit 2；不可归组记录剔除出判定集并告警（证据不完整不允许出结论）；
  8. `CostEstimator.estimate` 打印成本预估；dry-run → 打印选例清单，exit 0；
  9. 逐例执行、汇总行 `汇总: PASS x | CHANGED y | 失败 n（共 m），tokens 输入 a / 输出 b`；每条用例行带 `score`、summary、`〔served 模型 X ≠ 录制 Y〕` 注记、`[tokens in/out]`、以及非 PASS 用例的**文本差异注记**（`TextDiffUtils.diff` 取前 3 条 +/~/- 证据行，总预算 300 字符截断——结构指纹说明「哪里不同」，注记补充「说了什么不同的话」；判定只看指纹，注记是展示层）；
  10. 全部失败且无比对结果 → exit 2（基础设施故障不得被 CI 误读成回归）；有差异 → 打印待裁决 invocationKeys 与 approve/reject 指引；最终退出码：`changed + failed == 0 ? 0 : 1`。
  - **输出通道契约**：JSON 模式（`--json`）stdout 只产出单行证据报告 `{"schema":"agentassert4j.replay-report/1","mode":"replay|dry-run","judgmentSemantics":...,"summary":{...},"cases":[...],"pendingInvocationKeys":[...]}`（白名单字段、null 缺省即契约；summary 计数键 pass/changed/failed，用例对象含 dims 五维结论与 replayOutput 原文——仅 CHANGED 用例填充，64KB 截断并置标志），进度静默、诊断走 stderr；消费方按退出码分流——0/1 解析 stdout，2 只读 stderr。同一条通道纪律是**全命令统一契约**：9 个顶层命令加 `baseline export` 各自产出单行报告（schema 标签见第 13 章命令全景表），失败路径 stdout 零产出、配置披露与告警改走 stderr。
- `CostEstimator`（core）：价格真源是随 jar 分发的精选快照 `model_prices.json`（LiteLLM MIT 库裁剪，发布前再生成；`_meta` 前缀键是元信息非价格行），查找 = 精确命中后按最长包含匹配归入模型族。两个入口同一张表：`estimate`（执行前预估文案，固定 1000 输入/500 输出口径；**模型无价格时只报调用次数、不编造货币数**）与 `estimateCallCostUsd`（捕获时刻按实际 token 计价，查不到返回 null）；快照缺席/损坏等同无价格表。

**表结构**：重放不新增表——它的持久化后果只有候选指纹写入 `invocations.candidate_fingerprint`（第 7 章）。

**生命周期与并发契约——退出码契约**（CI gating 的全部语义）：

| 退出码 | 语义 | 触发条件 |
|--------|------|---------|
| 0 | 全部无差异（或 dry-run 完成） | 所有用例 PASS |
| 1 | 存在行为差异或证据不完整 | 有 CHANGED/TIMEOUT/API_ERROR/ERROR——证据不完整同样不允许绿 |
| 2 | 用法或基础设施故障 | 无用例、`--ci` 无基线拒绝、语义版本不一致、影响分析失败、**全部用例失败且无比对结果**（超时风暴/凭据失效——环境问题不是回归） |

**测试怎么钉住它**：`ReplayFlowTest`（临时库 + 桩客户端走通建档→重放→候选→approve/reject 全链，含 CI 拒绝/dry-run 只读/全失败 exit 2、previousTurns 字段级保真）；客户端协议契约测试（500→200 重试成功、429 耗尽上抛、400 单次不重试、超时不重试、合成 assistant 帧、tool_call_id 携带、历史 system 轮跳过）；`DeepSeekIntegrationTest`（真机，密钥环境变量门控跳过）。

---

## 第 10 章 影响分析与依赖图

**本幕回顾**：第 5 幕（这次改动波及谁）。

**设计问题**：全量重放又贵又慢，而「改了共享提示词会波及谁」是一个**数据问题**——答案在录制数据里，不在任何声明文件里。框架的解法：提示词指纹（templateHash）反查谁在用它 + 调用点依赖图补上传递波及。图用纯内存邻接表（既定结论：图数据库永久不引入——本框架规模是数十调用点/数百边，与图数据库的门槛差几个数量级，BFS 遍历微秒级、快照 JSON 小于 5KB）。

**概念与术语**：直接影响（使用旧 prompt hash 的调用点）与传递波及（图上下游）；HIGH/LOW 置信度边；全局提示词与局部提示词（按共享调用点数划分，决定采样还是全量）。

**代码地图**：

- `ParameterValueTracer`——图怎么从录制数据长出来：按 `sessionId` 分组，会话内按 `timestamp` 排序（平局按 recordId 决胜——同毫秒交互的边方向必须可复现）；相邻两条记录属于**不同调用点**时做两层匹配建边：
  - 第 1 层：前序记录的值来源（**工具返回优先**——遍历 `ToolCall.result` 经 JSON 解析收叶子值；无工具结果时降级 `modelResponse`，它是无工具调用记录的唯一值源）与当前记录工具参数值**精确 equals → HIGH**（叶子值深度限 3、总量限 500、长度 2–1000；有意义值过滤：长度 <3、布尔字面量、纯数字排除——几乎所有工具链都流经 true/false，不排就是假依赖）；
  - 第 2 层：字段名前缀匹配（驼峰/下划线/连字符取首段，最短 3 字符——`orderId`→`order`）→ **LOW**。
  - 调用点身份：记录已富化用存储 invocationKey，否则解析器现算。
- `InMemoryDependencyGraph`——图本身：正向邻接表（`LinkedHashMap`，**插入序保证快照字节可复现**）+ 反向邻接表。`addEdge` 四参：同边重复添加保最高置信度、合并 throughNodes；`traverseDownstream` BFS（visited 防环）；`detectCycles` DFS 三色染色 + 显式栈——只有「回边目标到栈顶」的区段才算环，环外尾部祖先不算；`fromJson` **fail-closed**——source/target 缺失或 confidence 非法的边整条跳过（派生数据宁缺勿错，不造幽灵拓扑污染影响集）。
- `ImpactAnalyzer.analyzeChange(oldPromptHash, newPromptHash)`——`newPromptHash` 当前未消费（预留新增调用点检测）。流程：`findInvocationKeysByTemplateHash` 查直接影响（统一键空间，声明与否同路）→ 空则区分冷启动两种文案（库里完全无数据 vs hash 不匹配）→ 图遍历下游合并全受影响集 → **自适应密度**：直接受影响调用点数 ≥ `GLOBAL_PROMPT_THRESHOLD`（10）视为全局提示词，全部受影响调用点每个按规范序（timestamp + recordId 排序）取前 `GLOBAL_SAMPLE_PER_INVOCATION`（3）条；局部提示词全量。查询失败返回 `AnalysisResult.error`（与冷启动的合法空数据严格区分，不吞成空集误导诊断）。
- **图的生命周期（谁读谁写，全部已接线）**：`replay` = 唯一写者（每次现场重建，快照 `saveGraphQuietly` 留档供巡检，写失败只告警）；`graph show` = 只读现场重建（永远最新，不落盘）；`status` = 读快照展示（标注「最近一次 replay 生成」）；录制管道**永不**建图。任务域的 `--affected` 复用同一套「模板哈希 → 调用点」查询定位受影响任务链（第 11 章），但冻结重放的受影响裁剪刻意**只取直接命中**、不做图下游传播——冻结重放喂的是录制原输入，下游节点的模板又没变，真重放只会复现原行为、不产生验证信号（下游的真实影响由真实再执行 + 对齐收口）。

**表结构**：`graph_snapshot`（id='current' 单行，`graph_json` 含 nodeCount/edgeCount/edges[source,target,confidence,throughNodes]）——快照只是巡检留档，分析永远用重建后的内存图。

**测试怎么钉住它**：损坏边跳过、带尾巴的环只报环段、快照字节复现（同数据重建 JSON 完全一致）、图往返保序、多轮会话产 HIGH 边的端到端渲染、空图提示（无边即无节点）、工具结果优先的值提取（含降级路径）。

---

## 第 11 章 任务回归

**本幕回顾**：第 4 幕（一句话改动，整条链的回归）与第 5 幕的 `--affected`。

**设计问题**：单点重放回答「这个调用点稳不稳」，回答不了小王最大的痛点——「一次用户请求触发的整条链路，改完之后还成立吗」。人工对比两条真实链是 agent 开发里最磨人的活：两条链长度都可能不一样、步骤顺序可能交错、措辞必然不同。框架的答案分三层：**把「一次用户请求」从录制数据里确定性地划出来（任务链）**、**把两条链按调用点配对对齐（不按序号——模板版本更替会让步骤错位）**、**把冻结重放与真实对比统一成一个回放单元**。任务不是新实体：任务链是录制数据的派生视图（与依赖图同哲学——派生事实不建表），零 schema 变更、可随时全量重建。

**概念与术语**：任务链（task chain，会话内一次请求触发的全部记录）、任务键（`(session_id, 请求文本)`——会话内键）、声明任务键（`metadata.taskKey`，声明优先于派生）、对齐键（invocationKey）、冻结重放（--prompt，重演历史输入）/真实对比（无 --prompt，最新链 vs 次新链）、分歧即停的 task 级推广（CHANGED 后停止真重放，下游标条件态）、预算池（本次运行全局封顶）。

**代码地图**：

- `TaskChainView`（core `algorithm/`，纯静态函数）——任务链派生的唯一真源：
  - `resolveSession(sessionId, records)`：传入记录不必有序，内部按规范序（timestamp→seq→recordId）重排后行走。记录 `userInput` 非 blank → 当前请求文本更新为该原文；记录归属「当前链」；会话开头没有请求文本的记录（纯 tool 起始）不属于任何任务链。同会话同文本重复提问**并入同一链**（键的字面定义，链内按规范序自然含两次执行；声明态不同的同名文本另开链）。
  - **声明优先**：记录 `metadata` JSON 含非 blank `"taskKey"`（公开常量 `DECLARED_TASK_KEY`）→ 以声明值为请求文本；供编排并发/改问法等派生失真场景逃生——声明来自录制 API 既有入口 `withMetadata`，零新增面。metadata 解析失败按未声明处理（R10）。
  - `resolveAll(repository)`：所有会话的链合并，按链首时间升序——跨会话配对与「取最新链」共用本口径。
- `TaskAligner`（core `algorithm/`，纯比较，零 LLM 调用）：
  - `align(baseline, newChain, comparator, rules)`：两侧各按 invocation_key 分组（组内规范序），三方分类——matched（两侧都有）/ missing（基线有新链无 = 缺步骤）/ added（新链有基线无 = 新增步骤）。matched 组内 1:1 规范序配对（按较少侧配对，**富余计数进报告不判差异**）；每对**两侧指纹现场重提**（同 rules 口径）→ 既有 `DeterministicComparator` 判定；步骤 verdict 取首个 CHANGED 配对（差异明细随之，停止后续配对）。缺步骤/新增步骤与配对 CHANGED 同归入链级 CHANGED。
  - `align(baselineSteps, newChain, comparator, rules)`：基线侧改由调用方给定 `Map<invocationKey, List<BaselineStep>>`（指纹步骤）——交付验收（第 12 章）用同一对齐核消费包内指纹，不做第二台差分引擎。
  - `prefixDependent` 标注：链内任一记录 `turnIndex>0` 或 `previousTurns` 非空 = 该链携带会话前缀 → 报告提示「真实再执行对照必须重演到该问为止的整个会话前缀，否则差异源于上下文缺失而非回归」（防误报，不阻断）。
- `TaskReplayRunner`（cli）——任务域两个互斥模式的编排：
  - **冻结重放**（提供 `--prompt`）：`--task` 精确命中的全部链**逐链回放**（同文本多链 = 同一任务的多轮执行实例，每轮都验证；`--dry-run` 先出执行计划与成本预估，零调用零建档）——选链器语义：请求文本精确相等优先，精确未命中时前缀匹配、命中多个不同任务文本属歧义即报错列候选（与 `--invocation` 目标选择器同款标准）。按规范序逐记录复用 `RegressionTestExecutor`（历史输入原样）。提供 `--old-prompt` 时影响裁剪：`findInvocationKeysByTemplateHash(sha256(oldPrompt))` 直查受影响调用点（**不做图下游传播**——第 10 章的边界理由），非受影响记录**继承 PASS**（标注「未受影响」）；受影响无命中 → exit 2。真重放按序推进，**遇 CHANGED 即停止后续真重放**，后续标注「分歧后下游（条件态：基线行为在此之后是否仍成立需真实重跑收口）」；`--full-chain` 取消裁剪与分歧即停，全部真重放。开头自动建档（与单点重放同款语义——候选落库以画像存在为前提；干跑不建档）。
  - **真实对比**（无 `--prompt`）：前缀命中同名链，最新 vs 次新走 `TaskAligner`，零 LLM 调用；仅一条链 = 首录即基线，报告标注自建基线，exit 0。
  - **预算池**：`--max-total-calls/--max-total-tokens` 对本次运行全部真重放**合计**封顶；耗尽 → 剩余待重放记录 skipped（原因 budget_exhausted）；继承 PASS 不计 skipped。超时/API 错误同样落 skipped 口径（证据缺口不允许冒充绿）。
  - **报告**：人类可读逐步行（序号、调用点短键、判定、摘要；CHANGED 步附工具/参数/结果摘要与费用——首次验收的链视图要素；文本差异注记低置信呈现，与单点重放同口径）+ `agentassert4j.task-report/1` 单行 JSON（mode=`task-frozen-replay|task-align|task-dry-run`、task{request,sessionId}、summary{total,pass,changed,inherited,postDivergence,skipped,missing,added；dry-run 模式为 chains/total/plannedReplay/inherited}、steps[]{recordId,invocationKey,action:replayed|inherited|post-divergence|skipped|aligned|missing|added|planned-replay,verdict,dims 五维,summary,replayOutput 仅 CHANGED 64KB 截断}、baselineTime/newChainTime、prefixDependent）。
- `replay` 命令的旗标校验（`ReplayCommand.call()`）：`--task`/`--invocation`/`--affected` 三范围互斥；`--full-chain` 与预算池旗标仅任务域有效；`--affected` 要求同时给 `--prompt` 与 `--old-prompt`；调用点范围要求 `--prompt`（任务域对比模式可省）；预算值必须 ≥1。

**表结构**：无新表、无新列——任务键声明住在 `interactions.metadata` JSON（吸收层），链是读侧派生。

**生命周期与并发契约——任务域退出码**（两模式统一）：

| 退出码 | 触发条件 |
|--------|---------|
| 1 | 任一配对 CHANGED / 缺步骤 / 新增步骤（缺与新增是行为差异，属回归信号） |
| 2 | 仅 skipped>0（预算耗尽或调用失败——证据不完整不允许冒充绿）或无匹配链等用法问题 |
| 0 | 全部 PASS / 继承；真实对比自建基线 |

**测试怎么钉住它**：`TaskChainViewTest`（7 条：派生/前推/声明优先/同文本并链/会话开头无请求排除/损坏 metadata 退化）、`TaskAlignerTest`（8 条：matched/missing/added/富余不判差异/前缀标注/指纹基线重载）、`TaskReplayRunnerTest`（23 条：冻结重放全链、影响裁剪继承 PASS、异模板步骤继承 PASS、分歧即停恰发 1 次、预算恰发 N 次、CHANGED 压过 skipped、真实对比配对、自建基线 exit 0、链式编排分歧即停、选择器歧义与控制字符可见化、未命中诊断、干跑零调用零建档/裁剪计划/JSON 契约/对齐模式拒绝）。

---

## 第 12 章 交付验收

**本幕回顾**：第 9 幕（把行为标准装进一个文件）。

**设计问题**：验收方在客户内网，手里没有开发侧的库；开发侧的库也不能给——里面有用户原始对话。验收需要的是一份**可搬运、天然脱敏、带版本守卫**的行为标准，加一个在本机比对并出报告的命令。设计裁决：验收就是第 11 章对齐核的**包装层**——包内指纹当基线侧、本机真实执行链现场重提当当前侧，喂同一个 `TaskAligner`，不做第二台差分引擎；跨模型验收的主判据是**结构指纹**（确定性跨模型成立，LLM-as-judge 无法交付此场景）；完整性靠 SHA-256 对账而非加密（加密进后备池）。

**概念与术语**：验收包（`agentassert4j.acceptance-pack/1`，单 JSON，版本自出生冻结）、版本守卫（判定语义不一致拒绝判定）、覆盖缺口（包任务未在本地执行——证据缺口）、范围外链（本地有而包无——只列出）、跨模型标注（开发侧/本地 servedModel 不一致——结构判定有效、文本差异属措辞预期内）、样本强制脱敏（--include-samples 的样本强制 MASK 双侧）。

**代码地图**：

- `AcceptancePack`（core `model/`）：`SCHEMA = "agentassert4j.acceptance-pack/1"`；`PackMeta`（exportedAt / exportedBy / judgmentSemantics / storageSchemaVersion / frameworkVersion / servedModel=开发侧去重并集）；`PackTask`（taskKey / requestText / declared / baselineTime / steps[]）；步骤 = `BaselineStep`（order / invocationKey / recordId / fingerprint=存储层同款指纹 JSON / 可空 sampleInput / sampleOutput）。
- `PackCodec`（core `util/`）：包 ↔ JSON 双向序列化（构建在 RecursiveJsonParser 上）；`fromJson` 对 schema 不符抛 `IllegalArgumentException`（verify 转 exit 2）。
- `BaselineExportCommand`（cli，`baseline` 的子命令）：
  - 选择器：缺省全量（`TaskChainView.resolveAll` 的已建档任务链，**同任务键只保留链首时间最新的链**——与 verify 侧「取最新为对照」对称），`--task <前缀>` 缩域；`--out` 默认 `./acceptance-pack.json`。
  - 每步指纹**逐记录现场提取**（与 verify 重提侧、库内任务对齐同口径——画像指纹是建档种子记录的单份快照，同键多记录时冒充其他步骤必然假差异）；调用点无画像或无指纹 → 该链**排除并警告列出**（导出前置纪律：先把基线建好）；全部被排除 → exit 2。
  - 常驻内容天然脱敏：只有结构指纹与调用点键——不带用户输入输出、不带 rules.json、不带模板原文（维度 3/4 声明集已随指纹序列化，「基线声明、当前答卷」的非对称语义让验收侧无需规则文件，见第 6 章）。
  - `--include-samples`：附加每步样本，用**强制脱敏器**（`DataSanitizer` + MASK 策略 + 输入/输出双侧开启，不受导出环境 recorder 配置影响——写入包内前完成脱敏）；样本仅供人读，判定不消费。
  - 导出打印文件 SHA-256（对账凭据）与任务链/步骤计数。
  - **任务键即请求原文**（配对语义的根基）会随包出境——敏感业务的任务应在录制时声明 `taskKey`（如场景 id）作为任务键，避免原文入包。
- `VerifyCommand` / `VerifyRunner`（cli，第 9 个顶层命令）：
  - 读取包文件并**重算 SHA-256**（digest 随报告回显供双方对账）；`PackCodec.fromJson` 失败 → exit 2；**版本守卫**：包无 meta 或 `judgmentSemantics` ≠ 当前引擎 → exit 2 拒绝判定（防静默重解释）。
  - 匹配：本地任务链（`TaskChainView.resolveAll` 派生）按「本地 requestText 与**包 taskKey 精确相等**」匹配，多链取最新；`--task` 缩域核对范围。前缀同名的本地链不是证据（防误配对）——包任务无精确链=覆盖缺口，本地多余链=范围外，两侧对称列出。
  - 对齐：包内指纹分组为基线侧步骤 → `TaskAligner.align(baselineSteps, localChain, ...)`——本地链指纹现场重提且 **rules 传 null**（验收侧无需规则文件；维度 3/4 由包内指纹携带的声明集对本地输出校验）。
  - 任务级与全局级双处跨模型判定：本地链 servedModel 集合 ≠ 包 servedModel → 标注「跨模型验收：结构判定有效，文本差异属措辞预期内」。
  - 汇总与两类缺口：包任务无本地链 = **覆盖缺口**（uncovered）；本地链不匹配任何包任务 = **范围外链**（unmatchedLocal，只列出）。退出码：任一 CHANGED/缺步骤/新增步骤 → 1；否则覆盖缺口 → 2；否则 0（偏差是最强信号，优先于覆盖缺口）。
  - 报告：`--report <path>` 产出 markdown 交付证据（包头信息表：包 schema/SHA-256/判定语义/两侧 servedModel/跨模型标注；判定汇总；覆盖缺口与范围外清单；逐任务逐步骤明细，文本差异标低置信与跨模型预期）；`--json` 输出单行 `agentassert4j.verify-report/1`（pack{digest,servedModel}、summary{tasks,pass,changed,missing,added,uncovered,unmatchedLocal,crossModel}、tasks[]、uncoveredTaskKeys[]、hints[]——范围外链非空时的因果提示（新录制未建档/未入包，先 baseline 补档重导））。
  - verify 全程**只读**：不落库、不改本地基线与候选状态。

**表结构**：无——包是文件不是库状态；verify 不写任何表。

**生命周期与并发契约**：包的一生 = 导出（开发侧，快照当前基线）→ 搬运（对账 SHA-256）→ verify（验收侧，只读消费）。包内 judgmentSemantics/storageSchemaVersion 双版本字段让「旧包配新引擎」在入口就被拒绝，而不是比出不可信的结论。

**测试怎么钉住它**：`VerifyExportTest`（11 条：包格式 golden、export→verify 同环境往返 PASS、跨模型标注与结构判定、版本守卫拒绝、覆盖缺口 exit 2、样本强制掩码、参照等价——包指纹与库内派生基线喂同一对齐核结果一致、范围外链因果提示）。

---

## 第 13 章 CLI 与配置

**本幕回顾**：全部十一幕里出现过的每一次命令行交互。

**设计问题**：CLI 是框架的**组合根**（把 core+recorder+storage 装配成可独立运行的工具）和**裁决工作台**（status 巡检、approve/reject 裁决、rollback 兜底）。命令输出的本质是产品界面而非日志（git/mvn 同款定位），可注入的 PrintStream 是它的测试通道。

**概念与术语**：三写法等价（业务标签 / 完整 invocationKey / invocationKey 唯一前缀）、配置查找链与来源披露、输出通道契约。

**代码地图**：

- **命令全景**（picocli，根命令 `agentassert4j`，全部子命令带 `mixinStandardHelpOptions`）：

| 命令 | 关键选项 | 职责 |
|------|---------|------|
| `baseline` | `--db --invocation --approver --force --json`(baseline-report/1) | 从录制建档（幂等）/按当前语义重建；子命令 `export`（第 12 章，--json 出 export-report/1 元数据报告） |
| `status` | `--db --diff --json`(status/1) | 画像巡检（invocationKey/状态/版本/候选/归档版本/业务标签）+ 候选差异 + 依赖图快照行 |
| `replay` | `--prompt --old-prompt --invocation --task --affected --full-chain --max-total-calls --max-total-tokens --max-cases --selection --ci --no-establish --dry-run --json` | 调用点域与任务域重放（第 9/11 章） |
| `approve` / `reject` | `--invocation <目标> --all --approver --json`(adjudication/1，action 字段区分) | 裁决：渲染候选差异 → 转正/丢弃 |
| `rollback` | `--invocation --version`(均必选) `--json`(rollback/1) | 从归档恢复 |
| `rules` | `--json`(rules/1) | 内置行为目录 + 规则配置示例 |
| `graph show` | `--db --json`(graph/1) | 依赖图只读视图（节点/边/置信度/穿透节点/环） |
| `verify` | `--pack(必选) --task --db --report --json`(verify-report/1) | 交付验收（第 12 章） |

  JSON 输出通道是**全命令统一契约**：9 个顶层命令加 `baseline export` 各自产出单行报告，字段为完整领域字段集（baseline-report/1 含逐调用点建档结果、adjudication/1 以 action 字段区分 approve/reject、export-report/1 含 out/taskCount/stepCount/sha256/excluded、rollback/1 含 invocationKey/versionTag 等），由 `JsonContractTest` 逐命令钉住——单行性、schema 前缀、关键字段存在、失败路径 stdout 零产出、UTF-8 严格可解码。

- `CliSupport`（包私有，命令间共用逻辑）：
  - `installUtf8Console`：主入口统一 UTF-8 直写标准流（绕过 Windows 控制台默认编码，中文报告不乱码）。
  - `openRepository`：加载配置后**先打印配置来源一行**（命中路径或「未找到用默认」——错误目录下的旧配置静默生效是最难查的排障黑洞），`~` 前缀展开后建库初始化。
  - `recordedInvocationIds`：走「session 全量 → 逐记录提取」通道收集业务标签（TreeSet 字典序稳定）。
  - `resolveInvocationFilter`（选例类命令 replay/baseline 用）：与业务 invocationId 精确相等按原义；否则 invocationKey **唯一**前缀匹配并换算回业务标签（歧义前缀显式报错；对应调用点覆盖多个业务标签也报错并列出）。
  - `resolveInvocationKeyTarget`（画像操作类 approve/reject/rollback 用，返回唯一 invocationKey）：完整调用点键精确命中（即使它是别的 key 的前缀）> 业务标签（覆盖多调用点时点名报错）> 唯一前缀；无命中/多命中抛 `IllegalStateException`，命令层转译为退出码 2。
  - `taskChains` / `invocationKeyOfRecord`：任务链派生与记录键解析的共用入口。
  - `currentActor`：`user.name` → 缺失记 `unknown`（不留无主审批记录）。
- `ConfigLoader`（core）——查找链五级（主配置与规则配置各一套，键分别为 `agentassert4j.config.path`/`agentassert4j.rules.path`，文件名 `agentassert4j.json`/`agentassert4j-rules.json`）：
  1. 系统属性显式路径——**不可读时抛 `IllegalStateException` 而非静默换源**（fail-fast：用户会以为配置已生效）；
  2. 当前工作目录；3. `~/.agentassert4j/`；4. classpath；5. 都没有 → 安全默认值。
  `${ENV_VAR}` 引用在读取后统一替换（未设置的变量替换为空串）。`describeMainConfigSource` 返回实际命中的来源供命令披露。
- 主配置五段（`AgentAssert4jConfig`，全部字段带安全默认值）：`storage.url`（默认 `~/.agentassert4j/agentassert4j.db`）、`recorder.{batchSize, flushIntervalMs}`、`regression.ignorableFields`、`llm.{apiKey(${ENV} 引用), endpoint, model, extraBody, timeoutMs, temperature}`、`tools.excludeFromGraph`。

**表结构**：无——CLI 是无状态的进程，一切状态在库里。

**生命周期与并发契约**：单进程命令生命周期 = 打开库（含迁移）→ 业务 → `finally` 关库。JSON 模式的输出通道约定与 replay 一致（0/1 → stdout 单行，2 → stderr）。

**测试怎么钉住它**：`CommandSmokeTest`（picocli 全链冒烟与退出码）、`JsonContractTest`（12 条全命令 JSON 契约）、`ReplayFlowTest`、解析器多态测试（三写法各态与全部报错分支）、`--help` 输出、未知 behavior 告警、UTF-8 直写助手。

---

# Part III 精通篇——像维护者一样工作

## 第 14 章 生命周期总图

四张图收拢全文。排查任何问题时，先在图上定位「它死在哪一段」，再进对应章节。

**图一：一条交互记录的一生**

```
 业务线程（Spring AI 应用）
 │  chatClient.prompt()...call()
 ▼
RecordingChatModel.call()                      ← 第 2 章 装饰器（业务零感知）
 │ 计时 + RecordingContext 闭包捕获
 ▼
SpringAiRecordMapper.toRecord()                ← 第 2 章 请求/响应 → InteractionRecord
 │  （templateHash=sha256(系统提示)，工具定义/采样参数/usage/费用落位）
 ▼
InteractionRecorder.intercept()                ← 第 3 章 管道入口（与 stop 互斥）
 │  recordId/sessionId 兜底 → 到达即计数 → 深拷贝脱敏 → 单调 seq
 ├─ RingBuffer 满 ──→ dropped 计数 + WARN（记录终止）
 ▼
RingBuffer → BatchWriteHandler.onEvent         ← 第 3 章 消费线程
 │  缓冲（超限丢弃计数）→ 满批/5s 定时 → flush
 ▼
enrich 补派生字段                            ← 第 3/5 章 invocationKey 已有值不覆盖
 ▼
SqliteStorageRepository.saveInteractions       ← 第 4 章 事务批量落库
 │                                              （失败 → failed 计数，记录终止）
 ▼
interactions 表（只追加历史）──────────────────┬──→ status 画像巡检            第 13 章
                                               ├──→ baseline 建档（指纹提取）  第 6/7 章
                                               ├──→ replay 选例重放           第 9 章
                                               ├──→ 任务链派生（读侧，不落库）  第 11 章
                                               └──→ 依赖图重建                第 10 章
```

**图二：一个基线的一生**

```
录制交互（规范序首条可分组记录）
 ▼
BaselineService.establishMissing               ← 第 7 章 baseline 命令
 │  FingerprintExtractor.extract(record, rules, invocationId) ← 第 6 章 三参提取
 ▼
invocations: fingerprint + versionTag=v1 + 盖章(algoVersion/approver/time)
 ▼
replay 重放比对（版本戳守卫 → 控制变量重放 → 四维对比）      ← 第 8/9 章
 │
 ├─ PASS ──→ 无事发生
 └─ 非 PASS ──→ recordCandidate 落候选（CANDIDATE 态）      ← 第 7 章 跨进程可达
                 ▼
        approve（旧基线先归档→候选提升→tag 顺延→重新盖章）   ← 第 7 章
         │                └─ reject（丢候选，保基线；prompt 回滚是 git 的事）
         ▼
  旧基线 → invocation_template_versions（快照含模板哈希与治理三列）
         ├─ rollback --version → 恢复（当前基线也先归档）     ← 第 7 章
         └─ 判定语义升级 → replay 拒判 → baseline --force 重建 ← 第 8 章 版本戳
```

**图三：一次任务回归的一生**

```
同一会话的真实执行（N 次调用）
 ▼
TaskChainView.resolveSession                   ← 第 11 章 派生视图（读侧，零 schema）
 │  规范序行走：userInput 非 blank 开链/blank 归当前链
 │  metadata.taskKey 声明优先；会话开头无请求不入链
 ▼
replay --task <前缀> ──┬── 带 --prompt：冻结重放（RegressionTestExecutor 复用）
 │                     │    --old-prompt → 模板哈希直查受影响 → 其余继承 PASS
 │                     │    CHANGED → 分歧即停 → 下游标条件态；--full-chain 全跑
 │                     │    --max-total-calls/tokens → 全局预算池，耗尽 skipped
 │                     └── 无 --prompt：真实对比（TaskAligner，零调用）
 ▼
对齐键 = invocationKey：matched（逐对现场重提对比）/
missing（缺步骤）/ added（新增步骤）──→ 链级 PASS/CHANGED
 ▼
task-report/1（--json）+ 退出码 0/1/2           ← 第 11 章
```

**图四：一个验收包的一生**

```
开发侧                                       验收侧
invocations 基线指纹                          客户环境真实执行验收请求
 │                                             │（框架不驱动产品入口）
baseline export                               TaskChainView.resolveAll（本地链）
 │  结构指纹+调用点键（天然脱敏）                  │
 │  --include-samples → 强制 MASK 样本            │
 ▼                                             ▼
acceptance-pack.json  ──搬运（SHA-256 对账）──→  verify --pack
                                               │  版本守卫（语义不一致 exit 2）
                                               │  本地 requestText 与包 taskKey 精确相等取最新
                                               │  TaskAligner：包指纹 × 本地现场重提（rules=null）
                                               ▼
                                         markdown 报告（交付证据）
                                         + verify-report/1
                                         退出码：偏差 1 / 覆盖缺口 2 / 通过 0
```

**图五：一个数据库文件的一生**（补充）：`initialize`（建目录→连接→autoCommit→迁移三段）→ 单连接串行读写 → `close`；schema 契约版本 1，预发布删库重建，发布后只增不改（第 4 章）。

## 第 15 章 缺陷定位套路

**第一层：症状路由**（先定位到章，再进代码）：

| 症状 | 先查 | 常见根因 |
|------|------|---------|
| 数据没落库 / recorded 与 written 对不上 | 第 3 章 | 计数闭合公式三分：dropped（缓冲满/发布异常）、failed（批量写失败看 ERROR 日志）、还是在缓冲/管道里（等 5 秒 flush） |
| 落库了但 status 看不到画像 | 第 7/13 章 | 画像按 invocationKey 建档，记录不可解析会被守卫剔除——看命令告警行 |
| 调用点归组不符合预期 / 出现代号组 | 第 5 章 | 锚点优先级：声明 > 模板哈希 > adhoc 请求锚点；没声明的调用点领 `template:`/`adhoc:` 代号键是正常形态，`--invocation` 三写法都能选到 |
| 指纹差异看起来是误报 | 第 6/8 章 | 先看 summary 定位维度：参数类型→词表两侧是否同源；内容规则→规则是否随基线存档；文本不同≠差异（判定只看结构指纹） |
| 重放请求被端点 400 | 第 9 章 | tool 帧缺 callId（会跳过并告警）、历史 system 帧混入（已跳过）、o 系模型 temperature（方言表裁剪+WARN）、extraBody 片段非法 |
| 每次重放全红 | 第 9 章 | served 模型 ≠ 录制模型注记、判定语义版本不一致（exit 2 有指引）、换模型实验（有告警） |
| 影响集为空 | 第 10 章 | 冷启动两种文案区分：库空 vs hash 不匹配（新 prompt 无录制） |
| 任务链没有配对上 / 配对错链 | 第 11 章 | 配对键=请求文本 trim 精确前缀（不做模糊归一）；改了问法请录制时声明 metadata.taskKey；同键多链取最新为对照 |
| `--task` 报「未找到任务链」 | 第 11 章 | 前缀不匹配（注意空白）；或该会话开头无请求文本的记录不属任何链 |
| 验收覆盖缺口（exit 2） | 第 12 章 | 包内任务在本地没执行——验收人先跑齐验收请求；或本地问法与包 taskKey 不构成前缀关系 |
| CI 全是 exit 2 | 第 9 章 | --ci 无基线拒绝（先本地 baseline）、语义版本守卫、全失败=基础设施故障 |

**第二层：三层审计视角**（定位到层后按序自问）：

1. **单元正确性**——这段代码本身对不对？（并发、边界、异常、资源）
2. **跨组件契约**——数据流经的每一跳字段对齐了吗？（捕获写入列 ↔ schema 列 ↔ 读侧反序列化键；配置键 ↔ 配置字段；两侧词表；包内指纹序列化 ↔ 验收侧重提口径）本框架的历史缺陷大多死在这一层（假阳性回归 = 词表漂移；整批失败 = NOT NULL 与兜底链断裂）；
3. **端到端意图**——设计意图真的落地了吗？（「重放不带工具必然假阳性」「冻结重放下游节点无验证信号」这类需求级断言逐条对照）。

**第三层：工具箱**：`status --diff`（画像与候选差异）、`replay --dry-run`（选例与成本，只读）、`--json`（机器可读证据）、`graph show`（依赖视图）、SQLite 工具直查五表、recorder 四计数器闭合审计。

## 第 16 章 改动检查单与术语表

**改动前先判定：这是不是单向门？** 三类变更走过去难回头，PR 必须显式标注影响与迁移方案：

1. **存储 schema 列变更**——interactions 是只追加历史，不存在的列无法回填已录数据（预发布期：删库重建；发布后：只增不改 + 从 raw 回填）；
2. **core/SPI 公开 API 签名变更**——发布后即破坏性变更；
3. **判定语义/指纹定义变更**——会静默重解释用户已 approve 的全部历史基线（递增 `JudgmentSemantics.VERSION`，走 `--force` 重建路径；键派生规则变更另见第 7 章的纪元契约；验收包 schema 冻结同理）。

**提交前硬门槛**（全部可机械验证）：

- 仓库根 `mvn -B test` 全绿（当前 11 个 reactor 构建、797 条，随演进浮动）；
- core 零依赖自检：`grep -rn "^import \(com\|org\)\." agentassert4j-core/src/main/java/` 输出必须为空；
- 注释自检：不写过程叙事与编号锚点、注释与代码同步、顶层类型带 `@author` + `@since`（首次入库日期，写定不改）；
- 临时代码必须带三要素 TODO（临时方案 / 详细说明 / 由谁完善）；
- 全限定类名只允许白名单场景（反射/SPI 配置文件/条件装配等），其余一律 import；
- 新公开 API 必须同变更附带契约测试；bug 修复先红后绿同一提交；
- 外部新依赖必须给出成本收益账（解决什么、体积、许可证必须宽松——GPL/AGPL/LGPL 一票否决）。

**新功能设计三问**（动手前）：真实场景下的成本到底多少（先量化，10–50 个调用点/数千交互是本框架的真实量级）？固定开销账算了吗（每个 SPI 接口、每个存储列、每个配置项都是发布后的永久契约）？砍掉它实现复杂度降多少（需求可以裁剪，功能完整性是产品决策、技术成本是工程判断——后者先给数据）？

**术语表**（按出现顺序）：

| 术语 | 定义 | 详见 |
|------|------|------|
| 交互记录 | 一次 LLM 调用的完整快照（请求面+响应面+遥测），`InteractionRecord` | 第 4/5 章 |
| 旁路录制 | 业务调用照常返回、框架异步抄写的捕获方式 | 第 2/3 章 |
| invocationKey / invocationId | 调用点键（派生的唯一身份，黄金键冻结）/ 调用点声明标签（业务身份，可空，不参与判定） | 第 5 章 |
| 调用点（invocation） | 变更单元与治理主体；治理对象=调用点的模板版本史 | 第 5/7 章 |
| 四维指纹 | 工具调用 + 输出结构 + 内容规则 + 约束行为的结构化摘要，`DeterministicFingerprint` | 第 6 章 |
| 类型词表 | 参数类型六词表 string/number/boolean/object/array/null，捕获与重放共用 | 第 6 章 |
| 基线 / 候选 / 归档 | 现役行为标准 / 待裁决差异 / 可回滚的历史版本 | 第 7 章 |
| 盖章 | 审批人 + 时间 + 判定语义版本三件套随基线落库 | 第 7 章 |
| 重放 | 新 prompt + 历史上下文的控制变量实验 | 第 9 章 |
| 链式半重放 | 拿基线录制的工具结果当道具逐轮重建上下文重问模型，决策分歧即停并定位到轮 | 第 9 章 |
| 判定语义版本 | 裁决矩阵的版本戳（当前 `det-v1`），不一致拒判 | 第 8 章 |
| 影响集 | 旧 prompt hash 直接命中 + 依赖图下游，按全局/局部阈值采样 | 第 10 章 |
| 任务链 | 会话内一次用户请求触发的全部记录；派生视图零 schema，键=(会话, 请求文本) | 第 11 章 |
| 声明任务键 | metadata 的 `taskKey` 字段，声明优先于派生——改问法仍可配对 | 第 11 章 |
| 冻结重放 / 真实对比 | replay --task 带 --prompt（重演历史输入）/ 不带（最新链 vs 次新链按调用点对齐，零调用） | 第 11 章 |
| 缺步骤 / 新增步骤 | 对齐键 invocationKey 三方分类中的 missing/added，与 CHANGED 同属行为差异 | 第 11 章 |
| 分歧即停 | 真重放遇 CHANGED 停止后续（轮级与任务级同则），下游标条件态 | 第 9/11 章 |
| 预算池 | --max-total-calls/--max-total-tokens 对本次运行全部真重放合计封顶 | 第 11 章 |
| 验收包 | `agentassert4j.acceptance-pack/1`：结构指纹+调用点键的单 JSON 交付载体，SHA-256 对账 | 第 12 章 |
| 覆盖缺口 | 包内任务未在验收侧执行——证据缺口，exit 2 | 第 12 章 |
| 跨模型验收 | 开发侧与本地 servedModel 不一致——结构判定有效，文本差异属措辞预期内 | 第 12 章 |
| 退出码契约 | 0 无差异 / 1 有差异或证据不完整 / 2 用法或基础设施故障 | 第 9/11 章 |
| 证据报告 | `--json` 的单行机器可读输出（replay-report/1、task-report/1、verify-report/1） | 第 9/11/12 章 |
| 三写法等价 | 业务标签 = 完整 invocationKey = 唯一前缀，CLI 统一解析 | 第 13 章 |
| 计数闭合 | recorded = written + dropped + failed，filtered 另列（总到达 = recorded + filtered） | 第 3 章 |
| 采集门 | 默认全量录制；recordUndeclaredChat=false 时未声明且无可见工具调用的交互被过滤（filtered 与 dropped 分列，首条与每满 100 条告警） | 第 3 章 |
| 判定二值化 | 判定只有 PASS/CHANGED 两值，权重与直判规则退役出判定链路 | 第 8 章 |
| 锚点收敛 | 显式声明 > 模板锚点 > 请求锚点兜底，键文法对任意输入单射 | 第 5 章 |

---

> **文档维护提醒**：本导读是活文档，只写当前代码的现状。行为、配置或默认值发生变更时，同步更新受影响章节与术语表；发现文档与代码不符，以代码为准并立即修文档。
