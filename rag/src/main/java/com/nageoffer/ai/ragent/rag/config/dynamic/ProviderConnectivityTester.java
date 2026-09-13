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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 供应商连通性测试器
 * <p>
 * 对 OpenAI 兼容端点发起 GET {base}/models 列表请求（零 token 消耗），
 * 校验 URL 可达性与 API Key 有效性；models 路径按 chat 端点同构推导，
 * 如 bailian 的 /compatible-mode/v1/chat/completions → /compatible-mode/v1/models
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderConnectivityTester {

    private static final String CHAT_COMPLETIONS_SUFFIX = "chat/completions";
    private static final String DEFAULT_MODELS_PATH = "/v1/models";

    private final AIModelProperties aiModelProperties;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ProviderTestResult test(String providerName, String apiKeyOverride, ProviderOverride override) {
        // 优先使用面板草稿中未保存的供应商信息（新增供应商尚未落库时后端查不到）
        String url = override != null && StringUtils.hasText(override.getUrl())
                ? override.getUrl().trim()
                : null;
        String chatEndpoint = override == null ? null : override.getChatEndpoint();
        String draftApiKey = override == null ? null : override.getApiKey();

        AIModelProperties.ProviderConfig provider = null;
        if (url == null) {
            provider = aiModelProperties.getProviders().get(providerName);
            if (provider == null || !StringUtils.hasText(provider.getUrl())) {
                throw new ClientException("供应商 " + providerName + " 不存在或缺少 URL");
            }
            url = provider.getUrl();
            chatEndpoint = provider.getEndpoints() == null ? null : provider.getEndpoints().get("chat");
        }

        String modelsUrl = resolveModelsUrl(url, chatEndpoint);
        String apiKey = resolveApiKey(providerName, apiKeyOverride, draftApiKey);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(modelsUrl))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET();
        if (StringUtils.hasText(apiKey)) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - start;
            int status = response.statusCode();
            return buildResult(status, latencyMs, response.body(), providerName, modelsUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("连通性测试被中断");
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            log.info("供应商连通性测试失败: provider={}, url={}, cost={}ms, error={}",
                    providerName, modelsUrl, latencyMs, e.getMessage());
            return ProviderTestResult.builder()
                    .ok(false)
                    .status(0)
                    .latencyMs(latencyMs)
                    .message("网络不可达或连接超时：" + e.getMessage())
                    .modelsUrl(modelsUrl)
                    .build();
        }
    }

    /**
     * 拉取供应商的可用模型 id 列表（OpenAI 兼容 GET /models，零 token 消耗）。
     * 支持草稿覆盖（未保存的供应商），密钥掩码/留空时回落已生效配置
     */
    public java.util.List<String> fetchModelIds(String providerName, String apiKeyOverride, ProviderOverride override) {
        String url = override != null && StringUtils.hasText(override.getUrl())
                ? override.getUrl().trim()
                : null;
        String chatEndpoint = override == null ? null : override.getChatEndpoint();
        String draftApiKey = override == null ? null : override.getApiKey();

        AIModelProperties.ProviderConfig provider = null;
        if (url == null) {
            provider = aiModelProperties.getProviders().get(providerName);
            if (provider == null || !StringUtils.hasText(provider.getUrl())) {
                throw new ClientException("供应商 " + providerName + " 不存在或缺少 URL");
            }
            url = provider.getUrl();
            chatEndpoint = provider.getEndpoints() == null ? null : provider.getEndpoints().get("chat");
        }

        String modelsUrl = resolveModelsUrl(url, chatEndpoint);
        String apiKey = resolveApiKey(providerName, apiKeyOverride, draftApiKey);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(modelsUrl))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET();
        if (StringUtils.hasText(apiKey)) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        long start = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("模型列表拉取被中断");
        } catch (Exception e) {
            throw new ClientException("模型列表拉取失败，网络不可达：" + e.getMessage());
        }
        long latencyMs = System.currentTimeMillis() - start;
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ClientException("模型列表拉取失败：API Key 无效或无权限（HTTP " + response.statusCode() + "）");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ClientException("模型列表拉取失败：HTTP " + response.statusCode() + " " + abbreviate(response.body()));
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode data = objectMapper.readTree(response.body()).path("data");
            data.forEach(node -> {
                String id = node.path("id").asText(null);
                if (StringUtils.hasText(id)) {
                    ids.add(id);
                }
            });
        } catch (Exception e) {
            throw new ClientException("模型列表响应解析失败：" + e.getMessage());
        }
        if (ids.isEmpty()) {
            throw new ClientException("该端点未返回任何模型（HTTP 200），请确认供应商支持模型列表接口");
        }
        java.util.List<String> sorted = ids.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).limit(500).toList();
        log.info("供应商模型列表已拉取: provider={}, count={}, cost={}ms", providerName, sorted.size(), latencyMs);
        return sorted;
    }

    private ProviderTestResult buildResult(int status, long latencyMs, String body,
                                           String providerName, String modelsUrl) {
        boolean ok = status >= 200 && status < 300;
        String message;
        if (status == 401 || status == 403) {
            message = "API Key 无效或无权限（HTTP " + status + "）";
        } else if (status == 404 || status == 405) {
            message = "URL 可达，但该端点不提供模型列表接口（HTTP " + status + "），请以实际调用为准";
        } else if (ok) {
            message = "连接成功，密钥有效，耗时 " + latencyMs + " ms";
        } else {
            message = "服务返回异常（HTTP " + status + "）：" + abbreviate(body);
        }
        log.info("供应商连通性测试: provider={}, status={}, cost={}ms, ok={}", providerName, status, latencyMs, ok);
        return ProviderTestResult.builder()
                .ok(ok || status == 404 || status == 405)
                .status(status)
                .latencyMs(latencyMs)
                .message(message)
                .modelsUrl(modelsUrl)
                .build();
    }

    /**
     * 密钥解析链：明文草稿值优先（用户刚改过）→ 已生效配置的真实密钥（草稿里是掩码）→
     * 草稿原值（全新供应商尚未落库，通常为空则不发认证头）。
     * 掩码值绝不能作为认证凭据发出，否则必然 401
     */
    private String resolveApiKey(String providerName, String apiKeyOverride, String draftApiKey) {
        if (StringUtils.hasText(apiKeyOverride) && !DynamicConfigService.isMaskedSecret(apiKeyOverride)) {
            return apiKeyOverride.trim();
        }
        AIModelProperties.ProviderConfig existing = aiModelProperties.getProviders().get(providerName);
        if (existing != null && StringUtils.hasText(existing.getApiKey())) {
            return existing.getApiKey();
        }
        return draftApiKey;
    }

    /**
     * 由 base URL 与 chat 端点推导 models 端点；无 chat 端点时用 /v1/models 兜底
     */
    private String resolveModelsUrl(String baseUrl, String chatPath) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String modelsPath = DEFAULT_MODELS_PATH;
        if (StringUtils.hasText(chatPath) && chatPath.toLowerCase().endsWith(CHAT_COMPLETIONS_SUFFIX)) {
            modelsPath = chatPath.substring(0, chatPath.length() - CHAT_COMPLETIONS_SUFFIX.length()) + "models";
        }
        return base + modelsPath;
    }

    /**
     * 面板草稿中未保存的供应商覆盖信息
     */
    @lombok.Data
    public static class ProviderOverride {
        private String url;
        private String chatEndpoint;
        private String apiKey;
    }

    private String abbreviate(String body) {
        if (body == null) {
            return "无响应体";
        }
        String trimmed = body.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProviderTestResult {
        private boolean ok;
        private int status;
        private long latencyMs;
        private String message;
        private String modelsUrl;
    }
}
