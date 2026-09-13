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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.config.OrchestrationMode;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import com.nageoffer.ai.ragent.rag.service.engine.ChatEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * RAG 对话控制器
 * 提供流式问答与任务取消接口
 */
@RestController
@RequiredArgsConstructor
public class RAGChatController {

    private final RAGChatService ragChatService;
    private final RAGDefaultProperties ragDefaultProperties;
    private final OrchestrationProperties orchestrationProperties;
    private final List<ChatEngine> chatEngines;

    /**
     * 发起 SSE 流式对话
     */
    @IdempotentSubmit(
            key = "T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    @GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestParam String question,
                           @RequestParam(required = false) String conversationId,
                           @RequestParam(required = false, defaultValue = "false") Boolean deepThinking,
                           @RequestParam(required = false) String modelId,
                           @RequestParam(required = false) String thinkingLevel) {
        SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());
        // 旧参数兼容：仅传 deepThinking=true 等价于思考强度 deep
        String tierKey = StringUtils.hasText(thinkingLevel)
                ? thinkingLevel
                : (Boolean.TRUE.equals(deepThinking) ? "deep" : null);
        resolveEngine().streamChat(question, conversationId, deepThinking, modelId, tierKey, emitter);
        return emitter;
    }

    /**
     * 停止指定任务
     */
    @IdempotentSubmit
    @PostMapping(value = "/rag/v3/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        resolveEngine().stopTask(taskId);
        return Results.success();
    }

    /**
     * 按 agenthub.engine.type 选择执行引擎；目标引擎缺位时回落 workflow，保证接口始终可用
     */
    private ChatEngine resolveEngine() {
        OrchestrationMode mode = orchestrationProperties.getMode();
        return chatEngines.stream()
                .filter(engine -> engine.mode() == mode)
                .findFirst()
                .or(() -> chatEngines.stream()
                        .filter(engine -> engine.mode() == OrchestrationMode.WORKFLOW)
                        .findFirst())
                .orElse(ragChatService instanceof ChatEngine workflow
                        ? workflow
                        : chatEngines.get(0));
    }
}
