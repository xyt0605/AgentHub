# Agenthub 项目交接文档

> 首次交接：2026-08-31 ｜ 更新：2026-09-14（追加 §9 ~ §11，覆盖动态配置体系、AI 配置面板与聊天增强；§1~§8 仍有效）

---

## 1. 项目是什么

Agenthub（原 Ragent AI）是一个面向 Agentic RAG 演进的生产级 Java AI 应用平台，覆盖从文档入库到智能问答的完整链路。核心能力：

- **混合检索**：向量 / 关键词 / 知识图谱 / 联网搜索并行召回，RRF 融合 + Rerank
- **双执行引擎**（本次交接重点，见 §4）：workflow（v1 编排管线）与 agent（v2 ReAct 架构），通过配置切换
- **问题理解**：查询改写、意图树路由、歧义澄清
- **模型容错**：多供应商档位路由（fast/standard/deep）、首包探测、熔断降级
- **MCP 工具**：工具发现、参数提取、调用与溯源
- **生产配套**：会话记忆、公平排队、幂等、全链路 Trace、管理后台

## 2. 技术栈与模块结构

| 层 | 技术 |
|---|---|
| 后端 | Java 17、Spring Boot 4.1、MyBatis-Plus、Redisson、RocketMQ 5.2、Sa-Token |
| AI 运行时 | 自研 infra-ai 模型路由 + AgentScope 2.0.2（仅 agent 引擎使用） |
| 存储 | PostgreSQL 16（pgvector 向量）、RustFS（S3 兼容对象存储）、Redis |
| 前端 | React 18 + Vite 5 + TailwindCSS + shadcn/ui（深色科技风主题） |

```
D:\workspace\ragent
├── framework/   通用基建：SSE 通道、任务取消、幂等、分布式 ID、trace 注解
├── infra-ai/    模型客户端：ChatClient 抽象、RoutingLLMService（档位/熔断/首包探测）
├── system/      用户/认证（Sa-Token）、审计日志
├── rag/         RAG 核心：检索引擎、意图、记忆、Prompt 槽位、编排管线、ChatEngine SPI
├── agent/       v2 ReAct 执行引擎（AgentScope），本次从空壳补齐
├── bootstrap/   启动模块（application.yaml 全部配置在此）
├── mcp-server/  示例 MCP Server（weather/ticket/sales/search，端口 9099）
├── frontend/    React 控制台
├── resources/
│   ├── database/          schema_pg.sql + init_data_pg.sql + upgrades/v1.1.0/（增量升级脚本）
│   ├── docker/            RocketMQ compose（rocketmq-stack-5.2.0.compose.yaml 及本地副本 -local）
│   └── initializer/       数据初始化器（独立 Java 工具）
└── docs/          版本说明与本交接文档
```

**包名说明**：Java 包名仍为 `com.nageoffer.ai.ragent`（品牌改名时刻意保留，见 §5.1），品牌层全部为 Agenthub。

## 3. 本地环境与启动

### 3.1 中间件（全部跑在 WSL Ubuntu-20.04 的 Docker 里）

Windows 本机没有 Docker，也没有原生 PG/Redis 服务。WSL 内 Docker 28 已装好：

```bash
# 查看容器状态
wsl -d Ubuntu-20.04 -e bash -c "docker ps --format '{{.Names}}: {{.Status}}'"

# 若 WSL 闲置回收导致容器被杀（都有 --restart unless-stopped，会随 WSL 重启自动拉起）
# 稳妥起见可开一个保活会话：
wsl -d Ubuntu-20.04 sleep infinity   # 放后台跑
```

| 容器 | 端口 | 凭据 | 备注 |
|---|---|---|---|
| postgres（pgvector/pgvector:pg16） | 5432 | postgres / postgres | 库名 `ragent`，数据在命名卷 `pgdata` |
| redis | 6379 | 密码 123456 | |
| rustfs | 9000 API / 9001 控制台 | rustfsadmin / rustfsadmin | 替代 MinIO |
| rmqnamesrv / rmqbroker / rocketmq-dashboard | 9876 等 | - | 用 `resources/docker/rocketmq-stack-local.compose.yaml`（见 §6.1） |

### 3.2 数据库

本地库是**旧 schema** 建的，2026-08-31 已按序补齐 `resources/database/upgrades/v1.1.0/` 全部 9 个增量脚本（缺列会报 `BadSqlGrammarException`，如 `t_message.message_status`）。新环境初始化：

```bash
# 顺序不能反：先 schema 后数据
docker exec -i postgres psql -U postgres -d ragent < resources/database/schema_pg.sql
docker exec -i postgres psql -U postgres -d ragent < resources/database/init_data_pg.sql
# 旧库升级则按文件名顺序执行 upgrades/v1.1.0/*.sql
```

默认账号：**admin / admin**（t_user 表）。

### 3.3 启动应用

```bash
# 后端（打包前必须停掉旧进程，否则 Windows 下 jar 被锁导致 repackage 失败）
./mvnw clean package -DskipTests
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar                          # workflow 模式
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar --agenthub.engine.type=agent   # agent 模式

# 前端
cd frontend && npm install && npm run dev    # http://localhost:5173，/api 代理到 9090
```

- 后端：`http://localhost:9090/api/agenthub`（context-path 已改为 /api/agenthub）
- 模型调用需要环境变量：`BAILIAN_API_KEY` / `SILICONFLOW_API_KEY` / `AIHUBMIX_API_KEY`（至少配一个，供应商定义在 application.yaml `ai.providers`）
- 不配 Key 的行为：workflow 空检索时走兜底文案可正常流式返回；agent 模式模型调用报 401

### 3.4 已验证状态（2026-08-31）

- [x] 双模式启动、登录、SSE 流式对话、消息落库、会话记忆
- [x] agent 模式：knowledge_search 工具注册、模型经 bailian→qwen3-max 正确发起调用（401 为无 Key 预期行为）
- [x] workflow 模式回归：记忆→改写→意图→多通道检索→兜底流式，零破坏
- [x] 深色科技风 UI 全站生效

## 4. 双引擎架构（workflow / agent）

### 4.1 切换与分发

`agenthub.engine.type: workflow | agent`（application.yaml:41，部署级配置，切换需重启）。链路：

```
RAGChatController (/rag/v3/chat)
  └─ resolveEngine()：按 OrchestrationProperties.getMode() 从 List<ChatEngine> 中选引擎
       ├─ RAGChatServiceImpl      mode()=WORKFLOW  → StreamChatPipeline（编排管线）
       └─ AgentChatEngine (agent) mode()=AGENT    → ReActAgentRunner（ReAct 循环）
```

`ChatEngine` SPI 定义在 rag（`rag/service/engine/ChatEngine.java`），实现在 rag 与 agent，避免循环依赖。幂等、排队、trace、SSE 通道、持久化、任务取消全部两引擎共享。引擎缺位自动回落 workflow。

### 4.2 agent 引擎关键类（`agent` 模块）

| 类 | 职责 |
|---|---|
| `config/AgentProperties` | 绑定 `agent.chat.provider/model`、`max-iters`、`max-retries`、`kb-top-k` |
| `runtime/AgentModelFactory` | `agent.chat.provider` → 引用 `ai.providers` 的 url/api-key/endpoints.chat → AgentScope `OpenAIChatModel`（缓存） |
| `runtime/KnowledgeSearchTool` | rag 向量检索门面 → `knowledge_search` 工具（检索默认 Collection，结果带 [编号] 与文档名） |
| `runtime/McpToolBridge` | rag `McpToolRegistry` 的工具 → AgentScope `AgentTool`（**禁用** AgentScope 自带 MCP 客户端，根 pom 有 SDK 版本冲突约定） |
| `runtime/ReActAgentRunner` | 核心执行器：加载记忆→用户消息落库→人设→建 ReActAgent→事件桥接（思考 delta→onThinking、正文 delta→onContent、工具调用→think 通知）→interrupt 对接取消 |
| `service/AgentChatEngine` | 引擎门面：排队、trace、SSE 回调工厂复用 |

### 4.3 人设与提示词

- 人设槽位体系在 `rag/core/prompt/AgentPromptSlot.java`，槽位按 `effectiveModes` 标注（`AGENT_MAIN` 仅 agent 模式生效，管理后台可编辑）
- `AGENT_MAIN` 未配置时空白回落 `ReActAgentRunner.DEFAULT_SYSTEM_PROMPT`（内置的中文工具使用约定）
- 修改人设：管理后台 → 智能体 → AGENT 架构槽位，实时生效（有缓存，AgentPromptCacheManager）

### 4.4 已知行为（两引擎一致，非 bug）

- 模型调用失败走 `SseEmitterSender.fail()` → `completeWithError` 直接断连，**不发** `reject` SSE 事件；前端 `useStreamResponse` 的流中断重试逻辑兜底
- MCP Server（9099）未启动时工具桥接为 0，不影响运行
- 深度思考开关在 agent 引擎当前为预留透传（思考深度由模型自主决定）

## 5. 本次改造记录（2026-08-30 ~ 08-31）

### 5.1 品牌改名 Ragent → Agenthub（仅品牌与配置层）

改了：6 个 `Ragent*` 类名、Maven artifactId、配置前缀 `ragent.*`→`agenthub.*`（yaml 键 + @ConfigurationProperties + @Value + Redis key 前缀）、context-path `/api/agenthub`（前端 .env 已同步）、localStorage key `agenthub_*`、全部品牌文案与 README/docs。
**刻意保留**：Java 包名 `com.nageoffer.ai.ragent`、PostgreSQL 库名 `ragent`、OSS 桶 `ragent-sources`/`ragent-assets`（改这些需要重建本地/线上资源，且失去合并上游 nageoffer/ragent 的能力）。

### 5.2 深色科技风 UI

- token 层：`frontend/src/styles/globals.css :root`（背景 #070b14~#101a2e，主色紫 #8b5cf6 + 青 #22d3ee，玻璃边框 white/10）+ `tailwind.config.cjs` 辉光阴影
- `<html class="dark">` 激活了 MarkdownRenderer 等处休眠的 `dark:` 变体
- 品牌标：`frontend/src/components/common/BrandMark.tsx`（hub+卫星，favicon 同款）
- 新增 UI 一律走语义变量/深色 token，不要引入浅色硬编码

### 5.3 双引擎落地

见 §4。此前 `agenthub.engine.type` 只有 3 个展示类消费、执行链路零分支、agent 模块为空、`agent.chat.*` 为死配置。

### 5.4 数据库升级

补齐 upgrades/v1.1.0 全部 9 个脚本（§3.2），修复 `t_message.message_status` 等缺列导致的持久化失败。

## 6. 已知坑与规避（重要）

1. **Windows 下 jar 被锁**：后端运行中执行 `mvnw package` 会在 repackage 阶段失败（Unable to rename .jar.original）。先停进程再打包。
2. **增量编译陈旧类**：改类名后直接 package 会把旧 .class 一起打进 jar（本次真实踩坑：Spring 报 NoUniqueBeanDefinitionException 双 bean）。改类名必须 `clean package`。
3. **RocketMQ 8082 端口冲突**：Windows 的 QQ.exe 占用 8082（dashboard 端口）。已建本地副本 `resources/docker/rocketmq-stack-local.compose.yaml`（去掉 8082 映射），原 compose 未动。代价：本地用不了 MQ 面板，不影响功能。
4. **WSL 空闲回收杀容器**：容器均带 `--restart unless-stopped`，随 WSL 重启自动拉起；需要绝对稳定就跑 `wsl sleep infinity` 保活。
5. **本地 curl 被系统代理劫持**：本机 Clash 代理会让 `curl localhost` 返回 502，本地测试一律加 `--noproxy "*"`。
6. **Windows Git Bash 路径**：`grep -rl | xargs sed` 会因反斜杠路径失败，用 `find -exec` 或 Python。
7. **后端日志是 GBK 编码**：管道里 grep 中文前先 `iconv -f GBK -t UTF-8`。

## 7. 待办与建议路线

按优先级：

1. **配置真实模型 Key**（`BAILIAN_API_KEY` 等）并做一轮带模型的端到端问答验收（含 agent 引擎的 knowledge_search 实际召回 + 引用编号展示）。
2. **agent 引擎回答的来源面板**：当前 agent 模式 `CompletionPayload.sources` 为空。可在 KnowledgeSearchTool 里收集 RetrievedChunk → SourceRef 并经 `callback.onSources()` 推送，前端来源面板即可复用。
3. **深度思考透传**：把 `deepThinking` 映射到 AgentScope `GenerateOptions`（enable_thinking 风格参数），与 workflow 行为对齐。
4. **AGENT_MAIN 内置人设入库**：目前代码内置兜底，建议把默认人设加进 init_data 的 t_agent_prompt（builtin agent），后台可见可改。
5. **多 agent 方向**（长期）：会话绑定 agentId（ConversationDO 加字段 + AgentPromptResolver 按会话解析）→ 编排 DAG（升级 IngestionEngine 的单链模型）→ 树形 Trace。此前已有分析。
6. **文档图片**：assets/ 下旧品牌截图（ragent-*.png）未重制，README 里仍引用旧文件名（文件名不含品牌语义，可接受）。

## 8. 关键入口速查

| 事项 | 位置 |
|---|---|
| 全部后端配置 | `bootstrap/src/main/resources/application.yaml` |
| 引擎切换/分发 | `agenthub.engine.type`；`rag/.../controller/RAGChatController.java` |
| agent 执行核心 | `agent/.../runtime/ReActAgentRunner.java` |
| 工作流管线 | `rag/.../service/pipeline/StreamChatPipeline.java` |
| SSE 事件协议 | `rag/.../enums/SSEEventType.java` + `rag/.../service/handler/StreamChatEventHandler.java` |
| 提示词槽位 | `rag/.../rag/core/prompt/AgentPromptSlot.java`（管理后台可编辑） |
| 深色主题 token | `frontend/src/styles/globals.css` |
| 品牌标 | `frontend/src/components/common/BrandMark.tsx` |
| 默认账号 | admin / admin |

---

## 9. 2026-09-13 ~ 09-14 改造记录：动态配置体系与 AI 配置面板

### 9.1 动态配置体系（DB 覆盖 yaml，运行时热生效）

- 新表 `t_dynamic_config`（升级脚本 `resources/database/upgrades/v1.1.1/260913_dynamic_config.sql`，已入库）：按命名空间存 JSON 覆盖，**yaml 永远是兜底默认**，DB 有行则启动时覆盖
- 核心代码 `rag/config/dynamic/`：
  - `DynamicConfigService`：聚合 Contributor；保存流程 = 信封解包 → 密钥合并 → 校验 → 热应用 → 持久化 → 发 `DynamicConfigUpdatedEvent`
  - `DynamicConfigContributor` 接口，rag 贡献 `ai`（AIModelProperties）/`pipeline`（检索管线四组 bean），agent 模块贡献 `agent`（AgentProperties）——避免循环依赖
  - `ModelCapabilityMatrix`：从容器实际注册的 Chat/Embedding/Rerank 客户端推导能力矩阵（chat/vlm: 全部供应商；embedding: siliconflow/aihubmix/ollama；rerank: bailian/noop），**代码加客户端矩阵自动更新**
  - `ProviderConnectivityTester`：连通性测试（GET /models 零 token 消耗）+ 模型列表拉取，支持未保存草稿；**密钥解析链**：明文草稿值 → 已生效配置真实密钥 → 草稿原值，掩码值绝不外发
- 热生效原理：所有配置消费方每次调用实时读 @Data 配置 bean，保存即改写 bean；`AgentModelFactory` 监听变更事件清空模型实例缓存
- **启动时应用 DB 覆盖不走 validate**（防止坏配置卡死启动），只有面板保存才校验

### 9.2 管理后台「AI 配置」面板（/admin/ai-config）

- 三个命名空间区块（AI 模型服务 / Agent 引擎 / 检索管线），每块独立保存 / 恢复默认，覆盖状态徽标
- 供应商卡片含一键"测试连通"；**模型名输入全部是可搜索下拉**（ChatModelPicker 复用思想同款，后端 `GET /admin/configs/ai/models` 拉取，会话级缓存）
- Provider 下拉按能力矩阵过滤（DeepSeek 不会出现在 Embedding 的可选项里）；后端 validate 双重兜底（能力客户端 + endpoint 配置）
- REST：`GET/PUT/DELETE /admin/configs/{ns}`、`GET /admin/configs/ai/capabilities`、`GET /admin/configs/ai/models`、`POST /admin/configs/ai/test-provider`

### 9.3 聊天增强

- **模型选择 + 思考强度**（输入框左下角，`ChatModelPicker` 组件，聊天页与欢迎页共用）：`/rag/v3/chat` 新增 `modelId` + `thinkingLevel`（fast/standard/deep）参数，`deepThinking=true` 兼容等价 deep；全链路透传 Controller → ChatEngine 新签名（旧签名 default 转发）→ StreamChatContext → **两个回答分支都要透传**（streamLLMResponse 与 streamSystemResponse，漏一个就是"指定模型不生效"）→ `ChatRequest.preferredModelId/tierKey` → `RoutingLLMService.resolveTierOverride` → ModelSelector（preferred 置队首，失败回退档位其余候选）。agent 引擎为单模型架构，忽略该参数并记日志
- 助手人设改「小羊驼」：`t_agent_prompt.SYSTEM_CHAT`（init_data_pg.sql 与 260803 升级脚本已同步）
- 用户消息气泡：`.user-message` 曾是浅色主题时代的 #333 深灰字（深色背景不可见），已改紫色系底 + 亮字
- 头像：admin 头像改用 `frontend/public/avatar.jpg`（DB avatar='/avatar.jpg'）；main.tsx 增加窗口聚焦时静默 `fetchCurrentUser()`，头像/昵称改动切回页面即生效
- 聊天侧 admin 菜单移除官方文档/哔哩哔哩链接；管理后台移除 GitHub Star 徽标
- 新增 DeepSeekChatClient（ModelProvider.DEEPSEEK）：DeepSeek 官方仅 chat 模型（deepseek-flash / deepseek-v4-pro），**无 embedding/reranker**，能力矩阵因此限定其只能配 chat

### 9.4 前端路由错误边界

- `RouteErrorBoundary`（components/common/）挂载于 router 全部顶层路由的 errorElement：任何页面组件抛错渲染统一的深色错误页（错误详情 + 刷新/返回首页），不再白屏打穿整个应用

## 10. 新增已知坑（重要，均在真实事故中验证）

1. **提示词缓存在 Redis**：改 `t_agent_prompt` 后**重启后端无效**，必须 `DEL agenthub:agent:resolved-prompts`（1 小时过期）。管理后台编辑走 AgentPromptCacheManager 失效逻辑则无此问题
2. **curl 测动态配置接口禁止回传信封**：把 `GET /admin/configs/ai` 的完整 Result 信封 `{"code":"0","data":{...}}` 原样 PUT 回去会污染 t_dynamic_config 并清空运行时配置（2026-09-13 真实事故，用户密钥被掩码覆盖丢失）。已加双层防御：保存时 `unwrapResultEnvelope` 自动解包；三个 Contributor 的 apply 均"空段不覆盖"。**测试必须用程序化解包 data**
3. **@Configuration 配置 bean 不能被 Jackson 直接序列化**：CGLIB 代理带合成字段（$$beanFactory），必须浅拷贝纯净实例再序列化（`DynamicConfigService.exportPlain`）
4. **Spring Boot 4 MVC 对 Jackson2 的 JsonNode 做 POJO 序列化**（输出节点元数据而非原始 JSON）：REST 入参出参一律用 Map/String（见 DynamicConfigController）
5. **Radix Select 的 SelectItem 禁止空字符串 value**（保留给清空语义），"自动"类选项用哨兵值
6. **RustFS 9000 端口**：曾因本机 SheepMusic 应用占用 9000 导致 rustfs 起不来（临时方案 rustfs-temp@29100 + `--rag.storage.s3.endpoint` 覆盖已退役删除）；SheepMusic 已退出，现恢复标准配置直连 9000。若再遇端口冲突照此模式处理
7. **WSL 空闲回收**（§6.4 补充）：保活会话 `wsl sleep infinity` 需随开发会话重启；容器均有 `--restart unless-stopped` 可自愈
8. **前端 tsc 不报的雷**：新建 .tsx 用 `React.*` 忘 import（UMD 全局类型不报错）、运行时 undefined 引用——**新建 UI 组件必须浏览器冒烟后再交付**；Python/脚本 patch 代码时每个替换必须 assert 匹配次数
9. **trace 异步落库**：`t_rag_trace_node` 在对话结束后延迟写入，curl 完立即查会读到上一次的 run；验证模型路由选"默认路由绝不会用的供应商"（如 deepseek）区分度最高

## 11. 关键入口速查（增量）

| 事项 | 位置 |
|---|---|
| 动态配置核心 | `rag/.../config/dynamic/`（Service/Contributor/能力矩阵/连通性测试） |
| 动态配置 REST | `rag/.../controller/DynamicConfigController.java` |
| AI 配置面板 | `frontend/src/pages/admin/settings/AiConfigPage.tsx` |
| 聊天模型/思考强度选择器 | `frontend/src/components/chat/ChatModelPicker.tsx`（ChatInput 与 WelcomeScreen 共用） |
| 模型选择后端链路 | `RAGChatController(modelId/thinkingLevel)` → `ChatEngine` 新签名 → `ChatRequest.preferredModelId/tierKey` → `RoutingLLMService.resolveTierOverride` |
| 路由错误边界 | `frontend/src/components/common/RouteErrorBoundary.tsx` |
| 助手人设 | `t_agent_prompt.SYSTEM_CHAT`（改后须清 Redis key，见 §10.1） |

## 12. 待办与建议路线（更新）

原 §7 待办状态：①真实 Key 端到端验收——已完成（bailian/siliconflow/deepseek 均实测连通与对话）；②agent 引擎来源面板——**未做**；③深度思考透传 agent——**未做**；④AGENT_MAIN 内置人设入库——**未做**；⑤多 agent 方向——**未做**；⑥品牌截图重制——**未做**。

新增待办（按优先级）：

1. **AI 配置页简化**：低频区块（Embedding/Rerank/VLM、检索管线）默认折叠，首屏聚焦供应商密钥与模型选择（用户反馈"眼花缭乱"）
2. **模型/思考强度选择持久化**：当前刷新回"自动"，记 localStorage（按用户维度）
3. **关键路径自动化测试**：密钥掩码回退、信封解包、能力校验、漏斗校验（本次全靠手工 curl，曾出信封事故）
4. **一键启动脚本**：容器健康检查 → WSL 保活 → 后端 → 前端 → 探活，把 §3/§10 的启动知识固化
5. **AiConfigPage.tsx 拆分**（约 1200 行）：按命名空间拆组件文件
