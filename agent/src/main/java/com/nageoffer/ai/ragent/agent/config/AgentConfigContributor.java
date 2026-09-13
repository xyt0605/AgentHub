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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.agent.runtime.AgentModelFactory;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.dynamic.DynamicConfigContributor;
import com.nageoffer.ai.ragent.rag.config.dynamic.DynamicConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent 引擎配置命名空间（agent.*）
 * <p>
 * 托管 {@link AgentProperties}：主模型（provider/model）、ReAct 迭代与重试上限、
 * SSE 超时、知识检索条数。ReActAgentRunner 每次执行实时读取，保存后立即生效；
 * 主模型实例缓存由 {@link AgentModelFactory} 监听配置变更事件自行清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentConfigContributor implements DynamicConfigContributor {

    public static final String KEY = "agent";

    private final AgentProperties agentProperties;
    private final AIModelProperties aiModelProperties;
    private final ObjectMapper objectMapper;

    private JsonNode yamlSnapshot;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Agent 引擎";
    }

    @Override
    public String description() {
        return "agenthub.engine.type=agent 时的 ReAct 主模型、迭代上限与 SSE 超时";
    }

    @Override
    public JsonNode export() {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode chat = root.putObject("chat");
            chat.put("provider", StrUtil.emptyIfNull(agentProperties.getChat().getProvider()));
            chat.put("model", StrUtil.emptyIfNull(agentProperties.getChat().getModel()));
            root.put("maxIters", agentProperties.getMaxIters());
            root.put("maxRetries", agentProperties.getMaxRetries());
            root.put("sseTimeoutMs", agentProperties.getSseTimeoutMs());
            root.put("kbTopK", agentProperties.getKbTopK());
            return root;
        } catch (Exception e) {
            throw new ClientException("Agent 配置导出失败：" + e.getMessage());
        }
    }

    @Override
    public void validate(JsonNode config) {
        String provider = DynamicConfigService.text(config.at("/chat"), "provider", null);
        String model = DynamicConfigService.text(config.at("/chat"), "model", null);
        if (StrUtil.isBlank(provider) || StrUtil.isBlank(model)) {
            throw new ClientException("Agent 主模型的 provider 与 model 均不能为空");
        }
        if (aiModelProperties.getProviders() != null && !aiModelProperties.getProviders().containsKey(provider)) {
            throw new ClientException("Agent 主模型引用了未定义的供应商: " + provider
                    + "，请先在「AI 模型服务」中添加");
        }
        int maxIters = config.path("maxIters").asInt(0);
        if (maxIters < 1 || maxIters > 50) {
            throw new ClientException("max-iters 须在 1~50 之间");
        }
        int maxRetries = config.path("maxRetries").asInt(0);
        if (maxRetries < 0 || maxRetries > 10) {
            throw new ClientException("max-retries 须在 0~10 之间");
        }
    }

    @Override
    public JsonNode resolveSecrets(JsonNode incoming) {
        return incoming;
    }

    @Override
    public void apply(JsonNode config) {
        JsonNode chat = config.at("/chat");
        // 空段不覆盖：坏数据不应清空运行时配置
        if (chat != null && chat.isObject()) {
            agentProperties.getChat().setProvider(chat.path("provider").asText(null));
            agentProperties.getChat().setModel(chat.path("model").asText(null));
        }
        if (config.has("maxIters") && !config.path("maxIters").isNull()) {
            agentProperties.setMaxIters(config.path("maxIters").asInt(agentProperties.getMaxIters()));
        }
        if (config.has("maxRetries") && !config.path("maxRetries").isNull()) {
            agentProperties.setMaxRetries(config.path("maxRetries").asInt(agentProperties.getMaxRetries()));
        }
        if (config.has("sseTimeoutMs") && !config.path("sseTimeoutMs").isNull()) {
            agentProperties.setSseTimeoutMs(config.path("sseTimeoutMs").asLong(agentProperties.getSseTimeoutMs()));
        }
        if (config.has("kbTopK") && !config.path("kbTopK").isNull()) {
            agentProperties.setKbTopK(config.path("kbTopK").asInt(agentProperties.getKbTopK()));
        }
        log.info("Agent 引擎配置已热更新: provider={}, model={}, maxIters={}, maxRetries={}",
                agentProperties.getChat().getProvider(), agentProperties.getChat().getModel(),
                agentProperties.getMaxIters(), agentProperties.getMaxRetries());
    }

    @Override
    public void snapshotYaml() {
        yamlSnapshot = export();
        log.info("Agent 引擎配置 yaml 快照已记录");
    }

    @Override
    public void restoreYaml() {
        if (yamlSnapshot == null) {
            return;
        }
        apply(yamlSnapshot);
    }
}
