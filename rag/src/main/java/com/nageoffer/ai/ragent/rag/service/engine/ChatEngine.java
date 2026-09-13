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

package com.nageoffer.ai.ragent.rag.service.engine;

import com.nageoffer.ai.ragent.rag.config.OrchestrationMode;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话引擎 SPI（ChatEngine）
 * <p>
 * workflow（v1 编排管线）与 agent（v2 ReAct 架构）两类执行引擎的共同契约：
 * 控制器按 {@code agenthub.engine.type} 选择引擎，限流、幂等、SSE 通道、
 * 持久化与取消机制由各引擎自行复用基础设施，不在此层重复。
 * <p>
 * 本接口定义在 rag 模块（被依赖方），实现方包括 rag 自身的 workflow 引擎
 * 与 agent 模块的 ReAct 引擎，避免反向依赖。
 */
public interface ChatEngine {

    /**
     * 本引擎对应的执行架构档位
     */
    OrchestrationMode mode();

    /**
     * 发起 SSE 流式对话
     *
     * @param question       用户问题
     * @param conversationId 会话 ID，空则由引擎新建
     * @param deepThinking   是否开启深度思考
     * @param emitter        SSE 发射器
     */
    void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter);

    /**
     * 带模型选择与档位覆盖的流式对话
     *
     * @param preferredModelId 首选模型 id（ai.chat.candidates 注册表），空走默认路由
     * @param tierKey          档位覆盖（fast / standard / deep），空走默认档位
     */
    default void streamChat(String question, String conversationId, Boolean deepThinking,
                            String preferredModelId, String tierKey, SseEmitter emitter) {
        // 引擎未适配模型选择时退化为默认路由
        streamChat(question, conversationId, deepThinking, emitter);
    }

    /**
     * 停止指定任务
     *
     * @param taskId 任务 ID
     */
    void stopTask(String taskId);
}
