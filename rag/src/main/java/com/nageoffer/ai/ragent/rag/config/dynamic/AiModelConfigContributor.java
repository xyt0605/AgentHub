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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * AI 模型配置命名空间（ai.*）
 * <p>
 * 托管 {@link AIModelProperties}：供应商、chat 档位、embedding/rerank/vlm 候选、
 * 熔断与流式参数。所有消费方（ModelSelector、OpenAI 风格客户端）每次调用
 * 实时读取该 bean，保存后立即生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelConfigContributor implements DynamicConfigContributor {

    public static final String KEY = "ai";

    private final AIModelProperties properties;
    private final ObjectMapper objectMapper;
    private final ModelCapabilityMatrix capabilityMatrix;

    private JsonNode yamlSnapshot;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "AI 模型服务";
    }

    @Override
    public String description() {
        return "模型供应商、Chat 档位路由、Embedding / Rerank / VLM 候选、熔断与流式参数";
    }

    @Override
    public JsonNode export() {
        ObjectNode root = (ObjectNode) DynamicConfigService.exportPlain(objectMapper, AIModelProperties.class, properties);
        ObjectNode providers = (ObjectNode) root.get("providers");
        if (providers != null) {
            providers.fields().forEachRemaining(entry -> {
                ObjectNode provider = (ObjectNode) entry.getValue();
                JsonNode apiKey = provider.get("apiKey");
                DynamicConfigService.putMasked(provider, "apiKey",
                        apiKey == null || apiKey.isNull() ? null : apiKey.asText());
            });
        }
        return root;
    }

    @Override
    public void validate(JsonNode config) {
        AIModelProperties target = toProperties(config);

        if (target.getProviders() != null) {
            target.getProviders().forEach((name, provider) -> {
                if (StrUtil.isBlank(name)) {
                    throw new ClientException("供应商名称不能为空");
                }
                if (provider == null || StrUtil.isBlank(provider.getUrl())) {
                    throw new ClientException("供应商 " + name + " 缺少 URL");
                }
            });
        }

        Set<String> providerNames = target.getProviders() == null
                ? Set.of()
                : target.getProviders().keySet();

        validateGroupChat(target, providerNames);
        validateGroup(target.getEmbedding(), providerNames, target.getProviders(), "embedding", true);
        validateGroup(target.getRerank(), providerNames, target.getProviders(), "rerank", false);
        validateGroup(target.getVlm(), providerNames, target.getProviders(), "vlm", false);
    }

    /**
     * 供应商能力双重校验：容器里存在对应能力客户端 + 供应商配置了对应端点。
     * 例如 DeepSeek 只有 chat 客户端、也没有 embedding 端点，配进 embedding 候选必然运行时失败，保存即拦截
     */
    private void requireCapability(AIModelProperties.ModelCandidate candidate, String capability,
                                   Map<String, AIModelProperties.ProviderConfig> providers) {
        String provider = candidate.getProvider();
        String label = capability + " 候选 " + resolveCandidateId(candidate);
        if (!capabilityMatrix.supports(capability, provider)) {
            throw new ClientException(label + " 引用的供应商 " + provider + " 不支持 " + capability
                    + "（无对应客户端实现），支持的能力矩阵请以 /admin/configs/ai/capabilities 为准");
        }
        if (ModelCapabilityMatrix.NOOP.equals(provider)) {
            return;
        }
        // VLM 图生文复用 chat 端点，不要求独立的 vlm endpoint
        String endpointKey = "vlm".equals(capability) ? "chat" : capability;
        AIModelProperties.ProviderConfig config = providers.get(provider);
        String endpoint = config == null || config.getEndpoints() == null
                ? null
                : config.getEndpoints().get(endpointKey);
        if (StrUtil.isBlank(endpoint)) {
            throw new ClientException(label + " 引用的供应商 " + provider + " 未配置 " + endpointKey + " endpoint，"
                    + "请先在供应商卡片中补充该端点");
        }
    }

    private void validateGroupChat(AIModelProperties target, Set<String> providerNames) {
        AIModelProperties.ModelGroup chat = target.getChat();
        if (chat == null) {
            return;
        }
        if (chat.getCandidates() == null) {
            chat.setCandidates(new java.util.ArrayList<>());
        }
        Set<String> ids = new HashSet<>();
        for (AIModelProperties.ModelCandidate candidate : chat.getCandidates()) {
            requireCandidateFields(candidate, "chat");
            if (!ids.add(resolveCandidateId(candidate))) {
                throw new ClientException("chat 候选 id 重复: " + resolveCandidateId(candidate));
            }
            requireProvider(candidate.getProvider(), providerNames, "chat 候选 " + candidate.getId());
            requireCapability(candidate, "chat", target.getProviders());
        }
        if (chat.getTiers() != null) {
            chat.getTiers().forEach((tierName, tier) -> {
                if (StrUtil.isBlank(tierName)) {
                    throw new ClientException("档位名不能为空");
                }
                for (String candidateId : tier.getCandidates()) {
                    boolean registered = chat.getCandidates().stream()
                            .anyMatch(c -> resolveCandidateId(c).equals(candidateId));
                    if (!registered) {
                        throw new ClientException("档位 " + tierName + " 引用了未登记的候选: " + candidateId);
                    }
                }
            });
        }
        if (StrUtil.isNotBlank(chat.getDefaultTier()) && !tierExists(chat, chat.getDefaultTier())) {
            throw new ClientException("默认档位不存在: " + chat.getDefaultTier());
        }
        if (StrUtil.isNotBlank(chat.getDeepThinkingTier()) && !tierExists(chat, chat.getDeepThinkingTier())) {
            throw new ClientException("深度思考档位不存在: " + chat.getDeepThinkingTier());
        }
    }

    private void validateGroup(AIModelProperties.ModelGroup group, Set<String> providerNames,
                               Map<String, AIModelProperties.ProviderConfig> providers,
                               String groupName, boolean requireDimension) {
        if (group == null) {
            return;
        }
        if (group.getCandidates() == null) {
            group.setCandidates(new java.util.ArrayList<>());
        }
        Set<String> ids = new HashSet<>();
        for (AIModelProperties.ModelCandidate candidate : group.getCandidates()) {
            requireCandidateFields(candidate, groupName);
            if (!ids.add(resolveCandidateId(candidate))) {
                throw new ClientException(groupName + " 候选 id 重复: " + resolveCandidateId(candidate));
            }
            requireProvider(candidate.getProvider(), providerNames, groupName + " 候选 " + candidate.getId());
            requireCapability(candidate, groupName, providers);
            if (requireDimension && (candidate.getDimension() == null || candidate.getDimension() <= 0)) {
                throw new ClientException(groupName + " 候选 " + resolveCandidateId(candidate) + " 缺少有效向量维度");
            }
        }
        if (StrUtil.isNotBlank(group.getDefaultModel())
                && group.getCandidates().stream().noneMatch(c -> resolveCandidateId(c).equals(group.getDefaultModel()))) {
            throw new ClientException(groupName + " 默认模型未在候选中登记: " + group.getDefaultModel());
        }
    }

    private void requireCandidateFields(AIModelProperties.ModelCandidate candidate, String group) {
        if (candidate == null) {
            throw new ClientException(group + " 存在空候选");
        }
        if (StrUtil.isBlank(resolveCandidateId(candidate)) || StrUtil.isBlank(candidate.getModel())) {
            throw new ClientException(group + " 候选缺少 id 或 model：" + candidate);
        }
    }

    private void requireProvider(String provider, Set<String> providerNames, String context) {
        if (StrUtil.isBlank(provider)) {
            throw new ClientException(context + " 缺少 provider");
        }
        if (!providerNames.contains(provider) && !"noop".equals(provider)) {
            throw new ClientException(context + " 引用了未定义的供应商: " + provider);
        }
    }

    private boolean tierExists(AIModelProperties.ModelGroup chat, String tierName) {
        return chat.getTiers() != null && chat.getTiers().containsKey(tierName);
    }

    private String resolveCandidateId(AIModelProperties.ModelCandidate candidate) {
        return StrUtil.isNotBlank(candidate.getId())
                ? candidate.getId()
                : candidate.getProvider() + "::" + candidate.getModel();
    }

    @Override
    public JsonNode resolveSecrets(JsonNode incoming) {
        ObjectNode merged = incoming.deepCopy();
        JsonNode providers = merged.get("providers");
        if (providers instanceof ObjectNode incomingProviders) {
            Iterator<Map.Entry<String, JsonNode>> fields = incomingProviders.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode apiKey = entry.getValue().get("apiKey");
                String incomingKey = apiKey == null || apiKey.isNull() ? null : apiKey.asText(null);
                if (DynamicConfigService.isMaskedSecret(incomingKey)) {
                    AIModelProperties.ProviderConfig current = properties.getProviders().get(entry.getKey());
                    DynamicConfigService.putMasked((ObjectNode) entry.getValue(), "apiKey",
                            current == null ? null : current.getApiKey());
                }
            }
        }
        return merged;
    }

    @Override
    public void apply(JsonNode config) {
        AIModelProperties target = toProperties(config);
        // 空段不覆盖：坏数据（如误存的信封 JSON）不应清空运行时配置；UI 上的"清空"语义是空数组而非 null
        if (target.getProviders() != null) {
            properties.setProviders(target.getProviders());
        }
        if (target.getChat() != null) {
            properties.setChat(target.getChat());
        }
        if (target.getEmbedding() != null) {
            properties.setEmbedding(target.getEmbedding());
        }
        if (target.getRerank() != null) {
            properties.setRerank(target.getRerank());
        }
        if (target.getVlm() != null) {
            properties.setVlm(target.getVlm());
        }
        if (target.getSelection() != null) {
            properties.setSelection(target.getSelection());
        }
        if (target.getStream() != null) {
            properties.setStream(target.getStream());
        }
        log.info("AI 模型配置已热更新: providers={}, chat 候选={} 个, embedding 候选={} 个",
                target.getProviders() == null ? 0 : target.getProviders().size(),
                target.getChat() == null || target.getChat().getCandidates() == null
                        ? 0 : target.getChat().getCandidates().size(),
                target.getEmbedding() == null || target.getEmbedding().getCandidates() == null
                        ? 0 : target.getEmbedding().getCandidates().size());
    }

    private AIModelProperties toProperties(JsonNode config) {
        try {
            return objectMapper.treeToValue(config, AIModelProperties.class);
        } catch (Exception e) {
            throw new ClientException("AI 配置格式错误：" + e.getMessage());
        }
    }

    @Override
    public void snapshotYaml() {
        yamlSnapshot = export();
        log.info("AI 模型配置 yaml 快照已记录");
    }

    @Override
    public void restoreYaml() {
        if (yamlSnapshot == null) {
            return;
        }
        apply(yamlSnapshot);
    }
}
