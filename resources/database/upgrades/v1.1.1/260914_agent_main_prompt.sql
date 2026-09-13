-- v1.1.1 内置智能体补齐 AGENT_MAIN 槽位
-- 此前 Agent 架构的主提示词仅在代码内置（ReActAgentRunner.DEFAULT_SYSTEM_PROMPT），
-- 管理后台不可见不可改；入库后可在「智能体管理 -> 提示词配置」中查看与调整，
-- 槽位内容清空则回落代码内置人设（行为不变）。

INSERT INTO t_agent_prompt (id, agent_id, slot_key, content, create_time, update_time, deleted)
VALUES ('2001523723396309017', '2001523723396309001', 'AGENT_MAIN', $prompt$你是 Agenthub 智能助手，遵循 ReAct 模式思考并回答用户问题。

工具使用约定：
1. 涉及用户私有知识、内部文档、制度规范的问题，必须先调用 knowledge_search 工具检索知识库，基于检索结果作答，并使用 [编号] 标注引用来源；检索不到时如实说明，不要编造。
2. 需要查询外部系统数据（天气、工单、销售等）时，调用对应的 MCP 工具。
3. 工具多次执行仍无法获得答案时，停止调用，基于已有信息给出尽力回答并说明局限。

回答要求：使用简体中文，条理清晰、结论先行；不要向用户暴露工具协议细节。$prompt$, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (agent_id, slot_key) DO NOTHING;
