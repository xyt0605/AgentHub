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

package com.nageoffer.ai.ragent.agent.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.runtime.ReActAgentRunner;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.config.OrchestrationMode;
import com.nageoffer.ai.ragent.rag.service.engine.ChatEngine;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.ratelimit.ChatQueueLimiter;
import com.nageoffer.ai.ragent.rag.trace.StreamChatTraceRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 执行架构（v2 ReAct）对话引擎
 * <p>
 * 与 workflow 引擎共享同一套入口契约与基础设施：公平排队、SSE 事件通道、
 * 全链路 trace、任务取消；差异仅在执行核心——ReAct 循环替代编排管线，
 * 检索与 MCP 降级为主 Agent 的工具。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatEngine implements ChatEngine {

    private final ReActAgentRunner agentRunner;
    private final ChatQueueLimiter chatQueueLimiter;
    private final StreamCallbackFactory callbackFactory;
    private final StreamChatTraceRunner traceRunner;
    private final StreamTaskManager taskManager;

    @Override
    public OrchestrationMode mode() {
        return OrchestrationMode.AGENT;
    }

    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        streamChat(question, conversationId, deepThinking, null, null, emitter);
    }

    /**
     * agent 引擎为单模型架构（agent.chat.provider/model），不支持按请求切换模型与档位：
     * preferredModelId / tierKey 忽略并记录日志；deepThinking 透传为 enable_thinking 请求参数
     */
    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking,
                           String preferredModelId, String tierKey, SseEmitter emitter) {
        if (preferredModelId != null || tierKey != null) {
            log.info("agent 引擎忽略按请求的模型选择/档位覆盖: preferredModelId={}, tierKey={}", preferredModelId, tierKey);
        }
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();
        // 排队可能在非请求线程执行，用户上下文须在进入队列前取出
        String userId = UserContext.getUserId();
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        chatQueueLimiter.enqueue(question, actualConversationId, emitter,
                () -> traceRunner.run(question, actualConversationId, taskId, callback, traceAware ->
                        agentRunner.run(question, actualConversationId, taskId,
                                Boolean.TRUE.equals(deepThinking), userId, traceAware)));
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}
