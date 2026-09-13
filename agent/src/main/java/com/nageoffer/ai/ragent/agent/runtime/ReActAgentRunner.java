/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.agent.runtime;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 执行器（v2 Agent 架构核心）
 * <p>
 * 每次请求构建一个 ReActAgent：人设取 {@link AgentPromptSlot#AGENT_MAIN} 槽位
 * （空白回落内置默认），工具 = 知识库检索 + MCP 桥接；会话记忆与消息持久化
 * 完全复用 workflow 的 ConversationMemoryService 与 StreamCallback 通道，
 * 增量事件按 SSE 协议桥接（thinking delta → onThinking，正文 delta → onContent）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActAgentRunner {

    private static final String AGENT_NAME = "agenthub-agent";

    /**
     * AGENT_MAIN 槽位未配置时的内置人设
     */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是 Agenthub 智能助手，遵循 ReAct 模式思考并回答用户问题。

            工具使用约定：
            1. 涉及用户私有知识、内部文档、制度规范的问题，必须先调用 knowledge_search 工具检索知识库，
               基于检索结果作答，并使用 [编号] 标注引用来源；检索不到时如实说明，不要编造。
            2. 需要查询外部系统数据（天气、工单、销售等）时，调用对应的 MCP 工具。
            3. 工具多次执行仍无法获得答案时，停止调用，基于已有信息给出尽力回答并说明局限。

            回答要求：使用简体中文，条理清晰、结论先行；不要向用户暴露工具协议细节。
            """;

    private final AgentProperties agentProperties;
    private final AgentModelFactory modelFactory;
    private final AgentPromptResolver promptResolver;
    private final ConversationMemoryService memoryService;
    private final McpToolBridge mcpToolBridge;
    private final ObjectProvider<VectorRetrieverService> retrieverProvider;
    private final RAGDefaultProperties defaultProperties;
    private final StreamTaskManager taskManager;

    /**
     * @param question       用户问题
     * @param conversationId 会话 ID
     * @param taskId         任务 ID（回调通道已注册取消收尾）
     * @param deepThinking   深度思考开关（当前模型自主决定思考深度，预留透传）
     * @param userId         用户 ID（请求线程外执行，须由调用方先取出）
     * @param callback       SSE 回调通道
     */
    public void run(String question, String conversationId, String taskId,
                    boolean deepThinking, String userId, StreamCallback callback) {
        List<Msg> messages;
        ReActAgent agent;
        try {
            messages = prepareMessages(question, conversationId, userId, callback);
            agent = buildAgent();
        } catch (Exception e) {
            log.error("Agent 构建失败, taskId={}, conversationId={}", taskId, conversationId, e);
            callback.onError(e);
            return;
        }

        Disposable stream = agent.streamEvents(messages)
                .doFinally(signal -> closeQuietly(agent, taskId))
                .subscribe(
                        event -> bridgeEvent(event, callback, taskId),
                        error -> {
                            log.error("Agent 执行异常, taskId={}, conversationId={}", taskId, conversationId, error);
                            if (!taskManager.isCancelled(taskId)) {
                                callback.onError(error);
                            }
                        },
                        () -> {
                            if (!taskManager.isCancelled(taskId)) {
                                callback.onComplete();
                            }
                        });

        taskManager.bindHandle(taskId, () -> {
            log.info("Agent 任务被取消, taskId={}", taskId);
            agent.interrupt();
            stream.dispose();
        });
    }

    /**
     * 加载会话记忆并把用户消息落库，组装 ReAct 输入消息序列
     */
    private List<Msg> prepareMessages(String question, String conversationId,
                                      String userId, StreamCallback callback) {
        List<ChatMessage> history = memoryService.load(conversationId, userId);
        String replyToMessageId = memoryService.append(conversationId, userId,
                new ChatMessage(ChatMessage.Role.USER, question));
        callback.onReplyToMessageId(replyToMessageId);

        List<Msg> messages = new ArrayList<>(history.size() + 1);
        for (ChatMessage message : history) {
            if (StrUtil.isBlank(message.getContent())) {
                continue;
            }
            messages.add(Msg.builder()
                    .role(toMsgRole(message.getRole()))
                    .textContent(message.getContent())
                    .build());
        }
        messages.add(Msg.builder().role(MsgRole.USER).textContent(question).build());
        return messages;
    }

    private MsgRole toMsgRole(ChatMessage.Role role) {
        if (role == ChatMessage.Role.USER) {
            return MsgRole.USER;
        }
        if (role == ChatMessage.Role.ASSISTANT) {
            return MsgRole.ASSISTANT;
        }
        return MsgRole.SYSTEM;
    }

    private ReActAgent buildAgent() {
        Toolkit toolkit = new Toolkit();
        registerTools(toolkit);
        String systemPrompt = promptResolver.resolve(AgentPromptSlot.AGENT_MAIN);
        if (StrUtil.isBlank(systemPrompt)) {
            systemPrompt = DEFAULT_SYSTEM_PROMPT;
        }
        return ReActAgent.builder()
                .name(AGENT_NAME)
                .sysPrompt(systemPrompt)
                .model(modelFactory.resolve())
                .toolkit(toolkit)
                .maxIters(agentProperties.getMaxIters())
                .maxRetries(agentProperties.getMaxRetries())
                .build();
    }

    private void registerTools(Toolkit toolkit) {
        VectorRetrieverService retriever = retrieverProvider.getIfAvailable();
        if (retriever != null) {
            toolkit.registerAgentTool(new KnowledgeSearchTool(
                    retriever, defaultProperties, agentProperties.getKbTopK()));
        } else {
            log.warn("向量检索服务不可用，Agent 未注册知识库检索工具");
        }
        List<? extends io.agentscope.core.tool.AgentTool> mcpTools = mcpToolBridge.bridgeAll();
        mcpTools.forEach(toolkit::registerAgentTool);
    }

    /**
     * AgentScope 增量事件 → SSE 回调桥接
     */
    private void bridgeEvent(AgentEvent event, StreamCallback callback, String taskId) {
        if (event instanceof ThinkingBlockDeltaEvent thinking) {
            callback.onThinking(thinking.getDelta());
        } else if (event instanceof TextBlockDeltaEvent text) {
            callback.onContent(text.getDelta());
        } else if (event instanceof ToolCallStartEvent toolCall) {
            callback.onThinking("\n> 调用工具 " + toolCall.getToolCallName() + " ...\n");
        } else if (event instanceof ExceedMaxItersEvent) {
            callback.onThinking("\n> 已达到最大迭代次数，基于当前信息收尾。\n");
        }
    }

    private void closeQuietly(ReActAgent agent, String taskId) {
        try {
            agent.close();
        } catch (Exception e) {
            log.warn("Agent 资源释放失败, taskId={}, reason={}", taskId, e.getMessage());
        }
    }
}
