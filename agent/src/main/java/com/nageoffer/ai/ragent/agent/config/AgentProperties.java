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

package com.nageoffer.ai.ragent.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 执行架构（v2 ReAct）配置
 * <p>
 * 仅在 {@code agenthub.engine.type=agent} 时参与运行；
 * chat.provider 引用 ai.providers 下的供应商，取其 url / api-key / endpoints.chat。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /**
     * 主模型：供应商 + 模型名，供应商在 ai.providers 中定义
     */
    private Chat chat = new Chat();

    /**
     * ReAct 循环上限，超出后熔断收尾
     */
    private int maxIters = 10;

    /**
     * 单次模型调用失败重试次数
     */
    private int maxRetries = 2;

    /**
     * SSE 全局超时（毫秒），与 rag.default.sse-timeout-ms 同语义
     */
    private Long sseTimeoutMs = 5 * 60 * 1000L;

    /**
     * 知识库检索工具的默认召回条数
     */
    private int kbTopK = 5;

    @Data
    public static class Chat {

        /**
         * 供应商名，对应 ai.providers 的 key（如 bailian / siliconflow / ollama）
         */
        private String provider;

        /**
         * 直接传给 OpenAI 兼容端点的模型名
         */
        private String model;
    }
}
