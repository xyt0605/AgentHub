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
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.dynamic.DynamicConfigUpdatedEvent;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 主模型工厂
 * <p>
 * 按 {@code agent.chat.provider + agent.chat.model} 从 ai.providers 解析出
 * OpenAI 兼容端点（url / api-key / endpoints.chat），构造 AgentScope 的
 * {@link OpenAIChatModel}；同一 provider+model 复用实例，避免重复建连。
 * 模型路由/熔断/档位切换由 AgentScope 自身的重试与 fallback 承担，
 * 与 workflow 的 RoutingLLMService 互不干涉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentModelFactory {

    private static final String CHAT_ENDPOINT = "chat";

    private final AgentProperties agentProperties;
    private final AIModelProperties modelProperties;

    private final Map<String, OpenAIChatModel> modelCache = new ConcurrentHashMap<>();

    /**
     * @return 当前配置对应的主模型
     * @throws IllegalStateException provider 未配置或不在 ai.providers 中
     */
    public OpenAIChatModel resolve() {
        AgentProperties.Chat chat = agentProperties.getChat();
        if (StrUtil.hasBlank(chat.getProvider(), chat.getModel())) {
            throw new IllegalStateException("agent.chat.provider / agent.chat.model 未配置，无法启动 Agent 架构");
        }
        AIModelProperties.ProviderConfig provider = modelProperties.getProviders().get(chat.getProvider());
        if (provider == null || StrUtil.isBlank(provider.getUrl())) {
            throw new IllegalStateException("ai.providers 中不存在供应商 " + chat.getProvider() + "，无法启动 Agent 架构");
        }
        String cacheKey = chat.getProvider() + ":" + chat.getModel();
        return modelCache.computeIfAbsent(cacheKey, key -> {
            String endpointPath = provider.getEndpoints() != null
                    ? provider.getEndpoints().get(CHAT_ENDPOINT)
                    : null;
            OpenAIChatModel model = OpenAIChatModel.builder()
                    .baseUrl(provider.getUrl())
                    .apiKey(StrUtil.emptyIfNull(provider.getApiKey()))
                    .modelName(chat.getModel())
                    .endpointPath(StrUtil.emptyIfNull(endpointPath))
                    .build();
            log.info("Agent 主模型已构建, provider={}, model={}, endpoint={}{}",
                    chat.getProvider(), chat.getModel(), provider.getUrl(), endpointPath);
            return model;
        });
    }

    /**
     * 供应商 url / api-key / 模型配置被动态配置面板修改后，
     * 旧实例持有的连接信息已失效，全部丢弃、下次 resolve 重建
     */
    @EventListener(DynamicConfigUpdatedEvent.class)
    public void onDynamicConfigUpdated(DynamicConfigUpdatedEvent event) {
        if (!modelCache.isEmpty()) {
            log.info("动态配置变更，清空 Agent 主模型实例缓存: configKey={}", event.getConfigKey());
            modelCache.clear();
        }
    }
}
