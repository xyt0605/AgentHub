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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingClient;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.infra.rerank.RerankClient;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 供应商能力探针
 * <p>
 * 「密钥测通」只打 GET /models，证明的是「这把 key 能列模型」，
 * 而链路真正依赖的是 /embeddings 与 /rerank——两者用的端点不同、模型权限也分开授予。
 * 实践中出现过 key 在 chat 上有效、embedding 返回 401（模型未开通）的组合：
 * 面板全绿，问答却一路兜底「未检索到」，排查只能靠翻后端日志。
 * <p>
 * 本探针按候选模型逐个发一次最小真实请求，把「端点通不通 / 模型有没有权限 / 维度对不对」
 * 三件事一次问清。刻意绕开 {@code RoutingEmbeddingService}：
 * 路由会在候选间 fallback（测 A 挂了报 B 的错），还会把探测失败计进熔断器健康状态，
 * 让一次体检把生产流量的路由决策带偏
 */
@Slf4j
@Component
public class ProviderCapabilityProbe {

    /**
     * 探测用文本：足够短以压低 token 开销，又不至于空串触发部分供应商的参数校验
     */
    private static final String PROBE_TEXT = "连通性测试";

    /**
     * rerank 探测需要 candidates > topN：客户端在候选数不超过 topN 时直接返回、根本不发请求，
     * 只给一条候选等于什么都没测
     */
    private static final int RERANK_PROBE_TOP_N = 1;

    private final AIModelProperties aiModelProperties;
    private final Map<String, EmbeddingClient> embeddingClients;
    private final Map<String, RerankClient> rerankClients;

    /**
     * 向量库建表维度：embedding 实际输出与之不一致时，写入期会报维度错、
     * 检索期则可能悄悄查不到东西，属于必须在配置面板就拦下的问题
     */
    private final int vectorStoreDimension;

    public ProviderCapabilityProbe(AIModelProperties aiModelProperties,
                                   List<EmbeddingClient> embeddingClients,
                                   List<RerankClient> rerankClients,
                                   @Value("${rag.default.dimension:0}") int vectorStoreDimension) {
        this.aiModelProperties = aiModelProperties;
        this.embeddingClients = embeddingClients.stream()
                .collect(Collectors.toMap(EmbeddingClient::provider, Function.identity(), (a, b) -> a));
        this.rerankClients = rerankClients.stream()
                .collect(Collectors.toMap(RerankClient::provider, Function.identity(), (a, b) -> a));
        this.vectorStoreDimension = vectorStoreDimension;
    }

    /**
     * 探测某供应商下所有已登记的 embedding / rerank 候选
     *
     * @param providerName 供应商名
     * @param apiKey       用于本次探测的密钥（已解析出明文；为空表示该供应商无需鉴权）
     * @return 逐候选的探测结果，供应商名下无候选时返回空列表
     */
    public List<CapabilityProbeResult> probe(String providerName, String apiKey) {
        List<CapabilityProbeResult> results = new ArrayList<>();
        results.addAll(probeGroup(providerName, apiKey, "embedding",
                aiModelProperties.getEmbedding(), this::probeEmbedding));
        results.addAll(probeGroup(providerName, apiKey, "rerank",
                aiModelProperties.getRerank(), this::probeRerank));
        return results;
    }

    private List<CapabilityProbeResult> probeGroup(String providerName,
                                                   String apiKey,
                                                   String capability,
                                                   AIModelProperties.ModelGroup group,
                                                   ProbeAction action) {
        if (group == null || group.getCandidates() == null) {
            return List.of();
        }
        List<CapabilityProbeResult> results = new ArrayList<>();
        for (AIModelProperties.ModelCandidate candidate : group.getCandidates()) {
            if (candidate == null || !providerName.equals(candidate.getProvider())) {
                continue;
            }
            if (Boolean.FALSE.equals(candidate.getEnabled())) {
                continue;
            }
            results.add(runOne(providerName, apiKey, capability, candidate, action));
        }
        return results;
    }

    /**
     * 单候选探测：异常一律收敛成 ok=false 的结果行
     * <p>
     * 体检工具不该因为被测对象有病就自己崩掉——任一候选失败都要让其余候选继续测完，
     * 否则第一个坏掉的模型会遮住后面所有问题
     */
    private CapabilityProbeResult runOne(String providerName,
                                         String apiKey,
                                         String capability,
                                         AIModelProperties.ModelCandidate candidate,
                                         ProbeAction action) {
        String modelId = StringUtils.hasText(candidate.getId())
                ? candidate.getId()
                : providerName + "::" + candidate.getModel();
        long start = System.currentTimeMillis();
        try {
            ModelTarget target = new ModelTarget(modelId, candidate, resolveProvider(providerName, apiKey), null);
            String detail = action.run(providerName, candidate, target);
            long latencyMs = System.currentTimeMillis() - start;
            return CapabilityProbeResult.builder()
                    .capability(capability)
                    .modelId(modelId)
                    .model(candidate.getModel())
                    .ok(true)
                    .latencyMs(latencyMs)
                    .message(detail == null ? "调用成功，耗时 " + latencyMs + " ms" : detail)
                    .build();
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            log.info("供应商能力探测失败: provider={}, capability={}, modelId={}, cost={}ms, error={}",
                    providerName, capability, modelId, latencyMs, e.getMessage());
            return CapabilityProbeResult.builder()
                    .capability(capability)
                    .modelId(modelId)
                    .model(candidate.getModel())
                    .ok(false)
                    .latencyMs(latencyMs)
                    .message(rootMessage(e))
                    .build();
        }
    }

    private String probeEmbedding(String providerName,
                                  AIModelProperties.ModelCandidate candidate,
                                  ModelTarget target) {
        EmbeddingClient client = embeddingClients.get(providerName);
        if (client == null) {
            throw new IllegalStateException("该供应商没有 embedding 客户端实现，不能用于向量模型");
        }
        List<Float> vector = client.embed(PROBE_TEXT, target);
        int actualDimension = vector == null ? 0 : vector.size();
        if (actualDimension == 0) {
            throw new IllegalStateException("返回了空向量");
        }
        // 维度对不上不是「调用失败」而是「调用成功但装不进库」，必须在这里说破：
        // 否则要等到文档入库报错、或检索期悄悄召回为空时才发现
        if (vectorStoreDimension > 0 && actualDimension != vectorStoreDimension) {
            throw new IllegalStateException(String.format(
                    "维度不匹配：模型实际输出 %d 维，向量库按 %d 维建表，入库会失败",
                    actualDimension, vectorStoreDimension));
        }
        return "调用成功，返回 " + actualDimension + " 维向量";
    }

    private String probeRerank(String providerName,
                               AIModelProperties.ModelCandidate candidate,
                               ModelTarget target) {
        RerankClient client = rerankClients.get(providerName);
        if (client == null) {
            throw new IllegalStateException("该供应商没有 rerank 客户端实现，不能用于精排模型");
        }
        // 两条候选取 top1：候选数必须大于 topN，否则客户端走「无需精排」短路，一个请求都不发
        List<RetrievedChunk> candidates = List.of(
                RetrievedChunk.builder().id("probe-1").text("连通性测试文档一").build(),
                RetrievedChunk.builder().id("probe-2").text("连通性测试文档二").build());
        List<RetrievedChunk> reranked = client.rerank(PROBE_TEXT, candidates, RERANK_PROBE_TOP_N, target);
        if (reranked == null || reranked.isEmpty()) {
            throw new IllegalStateException("精排返回空结果");
        }
        return "调用成功，精排返回 " + reranked.size() + " 条";
    }

    /**
     * 拼出本次探测用的供应商配置：base URL / endpoints 取已生效配置，密钥用调用方解析好的那把
     * <p>
     * 不直接改 {@link AIModelProperties} 里的对象——那是全局生效配置，
     * 一次体检写脏它会影响真实流量
     */
    private AIModelProperties.ProviderConfig resolveProvider(String providerName, String apiKey) {
        AIModelProperties.ProviderConfig source = aiModelProperties.getProviders().get(providerName);
        if (source == null) {
            throw new IllegalStateException("供应商 " + providerName + " 未配置");
        }
        AIModelProperties.ProviderConfig copy = new AIModelProperties.ProviderConfig();
        copy.setUrl(source.getUrl());
        copy.setApiKey(StringUtils.hasText(apiKey) ? apiKey : source.getApiKey());
        copy.setEndpoints(source.getEndpoints() == null
                ? Map.of()
                : new LinkedHashMap<>(source.getEndpoints()));
        return copy;
    }

    /**
     * 取最内层异常的 message：客户端异常常包一层「xx 请求失败: HTTP 401」，
     * 而真正有用的「Token is invalid / 模型未开通」在 cause 里
     */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        String message = current.getMessage();
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            if (StringUtils.hasText(current.getMessage())) {
                message = current.getMessage();
            }
        }
        return StringUtils.hasText(message) ? message : error.getClass().getSimpleName();
    }

    @FunctionalInterface
    private interface ProbeAction {
        String run(String providerName, AIModelProperties.ModelCandidate candidate, ModelTarget target);
    }

    /**
     * 单个候选模型的探测结果
     */
    @Data
    @Builder
    public static class CapabilityProbeResult {
        /**
         * 能力类型：embedding / rerank
         */
        private String capability;
        /**
         * 候选模型 id（配置里的 id）
         */
        private String modelId;
        /**
         * 实际下发给供应商的模型名
         */
        private String model;
        private boolean ok;
        private long latencyMs;
        private String message;
    }
}
