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

package com.nageoffer.ai.ragent.rag.config.dynamic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 检索管线配置命名空间（pipeline）
 * <p>
 * 聚合四组运行时读取的配置 bean：管线开关（RAGConfigProperties）、
 * 检索漏斗（SearchChannelProperties）、会话记忆（MemoryProperties）、
 * 全局限流（RAGRateLimitProperties，限流器与线程池在启动期构建，改动需重启生效）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineConfigContributor implements DynamicConfigContributor {

    public static final String KEY = "pipeline";

    private final RAGConfigProperties ragConfigProperties;
    private final SearchChannelProperties searchChannelProperties;
    private final MemoryProperties memoryProperties;
    private final RAGRateLimitProperties rateLimitProperties;
    private final ObjectMapper objectMapper;

    private JsonNode yamlSnapshot;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "检索管线";
    }

    @Override
    public String description() {
        return "管线开关、检索漏斗与通道、会话记忆、全局限流";
    }

    @Override
    public JsonNode export() {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("features", DynamicConfigService.exportPlain(objectMapper, RAGConfigProperties.class, ragConfigProperties));
        ObjectNode search = (ObjectNode) DynamicConfigService.exportPlain(
                objectMapper, SearchChannelProperties.class, searchChannelProperties);
        maskWebSearchKey(search);
        root.set("search", search);
        root.set("memory", DynamicConfigService.exportPlain(objectMapper, MemoryProperties.class, memoryProperties));
        root.set("rateLimit", DynamicConfigService.exportPlain(objectMapper, RAGRateLimitProperties.class, rateLimitProperties));
        return root;
    }

    private void maskWebSearchKey(JsonNode search) {
        JsonNode webSearch = search.at("/channels/webSearch");
        if (webSearch.isObject()) {
            JsonNode apiKey = webSearch.get("apiKey");
            DynamicConfigService.putMasked((ObjectNode) webSearch, "apiKey",
                    apiKey == null || apiKey.isNull() ? null : apiKey.asText());
        }
    }

    @Override
    public void validate(JsonNode config) {
        try {
            SearchChannelProperties search = toObject(config.get("search"), SearchChannelProperties.class);
            search.afterPropertiesSet();
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("检索参数校验失败：" + e.getMessage());
        }
        Integer topK = config.at("/search/defaultTopK").isNumber()
                ? config.at("/search/defaultTopK").asInt() : null;
        if (topK == null || topK <= 0) {
            throw new ClientException("default-top-k 必须为正数");
        }
        if (config.get("memory") == null || config.get("features") == null) {
            throw new ClientException("配置缺少 features / memory 段，请整节保存");
        }
    }

    @Override
    public JsonNode resolveSecrets(JsonNode incoming) {
        ObjectNode merged = incoming.deepCopy();
        JsonNode webSearch = merged.at("/search/channels/webSearch");
        if (webSearch.isObject()) {
            JsonNode apiKey = webSearch.get("apiKey");
            String incomingKey = apiKey == null || apiKey.isNull() ? null : apiKey.asText(null);
            if (DynamicConfigService.isMaskedSecret(incomingKey)) {
                DynamicConfigService.putMasked((ObjectNode) webSearch, "apiKey",
                        searchChannelProperties.getChannels().getWebSearch().getApiKey());
            }
        }
        return merged;
    }

    @Override
    public void apply(JsonNode config) {
        try {
            // 空段不覆盖：坏数据不应清空运行时配置
            JsonNode featuresNode = config.get("features");
            JsonNode searchNode = config.get("search");
            JsonNode memoryNode = config.get("memory");
            JsonNode rateLimitNode = config.get("rateLimit");
            if (featuresNode == null || searchNode == null || memoryNode == null || rateLimitNode == null) {
                log.warn("检索管线配置存在缺失段，跳过应用以免清空运行时值: features={}, search={}, memory={}, rateLimit={}",
                        featuresNode != null, searchNode != null, memoryNode != null, rateLimitNode != null);
                return;
            }
            RAGConfigProperties features = toObject(featuresNode, RAGConfigProperties.class);
            ragConfigProperties.setQueryRewriteEnabled(features.getQueryRewriteEnabled());
            ragConfigProperties.setRerankEnabled(features.getRerankEnabled());
            ragConfigProperties.setCitationEnabled(features.getCitationEnabled());
            ragConfigProperties.setContextEnrichEnabled(features.getContextEnrichEnabled());

            SearchChannelProperties search = toObject(searchNode, SearchChannelProperties.class);
            searchChannelProperties.setDefaultTopK(search.getDefaultTopK());
            searchChannelProperties.setRecallBudget(search.getRecallBudget());
            searchChannelProperties.setScope(search.getScope());
            searchChannelProperties.setChannels(search.getChannels());
            searchChannelProperties.setFusion(search.getFusion());

            MemoryProperties memory = toObject(memoryNode, MemoryProperties.class);
            memoryProperties.setHistoryKeepTurns(memory.getHistoryKeepTurns());
            memoryProperties.setSummaryEnabled(memory.getSummaryEnabled());
            memoryProperties.setSummaryStartTurns(memory.getSummaryStartTurns());
            memoryProperties.setSummaryMaxChars(memory.getSummaryMaxChars());
            memoryProperties.setTitleMaxLength(memory.getTitleMaxLength());

            RAGRateLimitProperties rateLimit = toObject(rateLimitNode, RAGRateLimitProperties.class);
            rateLimitProperties.setGlobalEnabled(rateLimit.getGlobalEnabled());
            rateLimitProperties.setGlobalMaxConcurrent(rateLimit.getGlobalMaxConcurrent());
            rateLimitProperties.setGlobalMaxWaitSeconds(rateLimit.getGlobalMaxWaitSeconds());
            rateLimitProperties.setGlobalLeaseSeconds(rateLimit.getGlobalLeaseSeconds());
            rateLimitProperties.setGlobalPollIntervalMs(rateLimit.getGlobalPollIntervalMs());
            log.info("检索管线配置已热更新: topK={}, recallBudget={}, rerank={}, queryRewrite={}",
                    search.getDefaultTopK(), search.getRecallBudget(),
                    features.getRerankEnabled(), features.getQueryRewriteEnabled());
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("检索管线配置应用失败：" + e.getMessage());
        }
    }

    private <T> T toObject(JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            throw new ClientException("配置缺少 " + type.getSimpleName() + " 段，请整节保存");
        }
        try {
            return objectMapper.treeToValue(node, type);
        } catch (Exception e) {
            throw new ClientException(type.getSimpleName() + " 配置格式错误：" + e.getMessage());
        }
    }

    @Override
    public void snapshotYaml() {
        yamlSnapshot = export();
        log.info("检索管线配置 yaml 快照已记录");
    }

    @Override
    public void restoreYaml() {
        if (yamlSnapshot == null) {
            return;
        }
        apply(yamlSnapshot);
    }
}
