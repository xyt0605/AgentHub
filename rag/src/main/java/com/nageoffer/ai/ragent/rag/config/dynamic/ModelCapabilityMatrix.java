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

import com.nageoffer.ai.ragent.infra.chat.ChatClient;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingClient;
import com.nageoffer.ai.ragent.infra.rerank.RerankClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型能力矩阵
 * <p>
 * 从 Spring 容器实际注册的客户端推导"哪类能力支持哪些供应商"：chat / embedding / rerank
 * 各自按客户端 bean 的 provider() 汇总，VLM 图生文走 chat 客户端故与 chat 同集。
 * 代码新增客户端后矩阵自动更新，配置校验（后端）与下拉过滤（前端）都以此为准，
 * 防止把 DeepSeek 这类无 embedding 能力的供应商配进向量模型。
 */
@Component
public class ModelCapabilityMatrix {

    public static final String NOOP = "noop";

    private final Set<String> chatProviders;
    private final Set<String> embeddingProviders;
    private final Set<String> rerankProviders;

    public ModelCapabilityMatrix(List<ChatClient> chatClients,
                                 List<EmbeddingClient> embeddingClients,
                                 List<RerankClient> rerankClients) {
        this.chatProviders = chatClients.stream()
                .map(ChatClient::provider)
                .collect(Collectors.toUnmodifiableSet());
        this.embeddingProviders = embeddingClients.stream()
                .map(EmbeddingClient::provider)
                .collect(Collectors.toUnmodifiableSet());
        this.rerankProviders = rerankClients.stream()
                .map(RerankClient::provider)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 指定能力是否支持该供应商（rerank 的 noop 空实现视为合法占位）
     */
    public boolean supports(String capability, String provider) {
        if (NOOP.equals(provider)) {
            return "rerank".equals(capability);
        }
        return providersOf(capability).contains(provider);
    }

    public Set<String> providersOf(String capability) {
        return switch (capability) {
            case "chat", "vlm" -> chatProviders;
            case "embedding" -> embeddingProviders;
            case "rerank" -> rerankProviders;
            default -> Set.of();
        };
    }

    /**
     * 前端下拉过滤用的完整矩阵（vlm 与 chat 同集）
     */
    public Map<String, Set<String>> toFrontendMatrix() {
        return Map.of(
                "chat", chatProviders,
                "embedding", embeddingProviders,
                "rerank", rerankProviders,
                "vlm", chatProviders
        );
    }
}
