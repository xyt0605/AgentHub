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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.config.dynamic.DynamicConfigService;
import com.nageoffer.ai.ragent.rag.config.dynamic.ProviderConnectivityTester;
import com.nageoffer.ai.ragent.rag.controller.vo.DynamicConfigNamespaceVO;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 动态配置管理控制器
 * <p>
 * 管理后台可视化配置面板的后端：按命名空间读取生效配置（敏感字段脱敏）、
 * 保存覆盖（热生效）、重置为 yaml 默认，以及供应商连通性测试。
 * 注：入参出参刻意避开 Jackson2 的 JsonNode——Spring Boot 4 的 MVC 消息转换器
 * 对其做 POJO 序列化会输出节点元数据而非原始 JSON，故统一走 Map / String。
 */
@RestController
@RequiredArgsConstructor
public class DynamicConfigController {

    private final DynamicConfigService dynamicConfigService;
    private final ProviderConnectivityTester providerConnectivityTester;
    private final ObjectMapper objectMapper;
    private final com.nageoffer.ai.ragent.rag.config.dynamic.ModelCapabilityMatrix capabilityMatrix;

    /**
     * 列出全部配置命名空间及覆盖状态
     */
    @GetMapping("/admin/configs")
    public Result<List<DynamicConfigNamespaceVO>> listNamespaces() {
        return Results.success(dynamicConfigService.listNamespaces());
    }

    /**
     * 读取指定命名空间的生效配置（敏感字段已脱敏）
     */
    @GetMapping("/admin/configs/{namespace}")
    public Result<Map<String, Object>> getEffective(@PathVariable String namespace) {
        return Results.success(toMap(dynamicConfigService.getEffective(namespace)));
    }

    /**
     * 保存指定命名空间的配置覆盖（合并密钥、校验后热生效）
     */
    @PutMapping("/admin/configs/{namespace}")
    public Result<Void> save(@PathVariable String namespace, @RequestBody String configJson) {
        dynamicConfigService.save(namespace, parseTree(configJson), UserContext.getUsername());
        return Results.success();
    }

    /**
     * 重置指定命名空间为 yaml 默认（删除数据库覆盖）
     */
    @DeleteMapping("/admin/configs/{namespace}")
    public Result<Void> reset(@PathVariable String namespace) {
        dynamicConfigService.reset(namespace, UserContext.getUsername());
        return Results.success();
    }

    /**
     * 供应商连通性测试：对 OpenAI 兼容端点发起零消耗的模型列表请求。
     * 传草稿信息（url / chatEndpoint / apiKey）时按未保存草稿测试，否则查已生效配置
     */
    @PostMapping("/admin/configs/ai/test-provider")
    public Result<ProviderConnectivityTester.ProviderTestResult> testProvider(
            @RequestBody TestProviderRequest request) {
        ProviderConnectivityTester.ProviderOverride override = null;
        if (request.getUrl() != null || request.getChatEndpoint() != null || request.getApiKey() != null) {
            override = new ProviderConnectivityTester.ProviderOverride();
            override.setUrl(request.getUrl());
            override.setChatEndpoint(request.getChatEndpoint());
            override.setApiKey(request.getApiKey());
        }
        return Results.success(providerConnectivityTester.test(
                request.getProvider(), request.getApiKey(), override));
    }

    /**
     * 各模型能力（chat/embedding/rerank/vlm）支持哪些供应商，供前端下拉过滤。
     * 推导自容器实际注册的客户端，与保存校验同一事实来源
     */
    @GetMapping("/admin/configs/ai/capabilities")
    public Result<Map<String, java.util.Set<String>>> capabilities() {
        return Results.success(capabilityMatrix.toFrontendMatrix().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new java.util.TreeSet<>(e.getValue()))));
    }

    /**
     * 拉取供应商可用模型列表（OpenAI 兼容 /models）。
     * 传草稿参数时按未保存草稿拉取，否则查已生效配置；密钥掩码/留空回落已生效值
     */
    @GetMapping("/admin/configs/ai/models")
    public Result<List<String>> listProviderModels(
            @org.springframework.web.bind.annotation.RequestParam String provider,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String apiKey,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String url,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String chatEndpoint) {
        ProviderConnectivityTester.ProviderOverride override = null;
        if (url != null || chatEndpoint != null || apiKey != null) {
            override = new ProviderConnectivityTester.ProviderOverride();
            override.setUrl(url);
            override.setChatEndpoint(chatEndpoint);
            override.setApiKey(apiKey);
        }
        return Results.success(providerConnectivityTester.fetchModelIds(provider, apiKey, override));
    }

    private Map<String, Object> toMap(JsonNode node) {
        try {
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
            });
        } catch (IllegalArgumentException e) {
            throw new com.nageoffer.ai.ragent.framework.exception.ClientException("配置 JSON 转换失败：" + e.getMessage());
        }
    }

    private JsonNode parseTree(String configJson) {
        try {
            return objectMapper.readTree(configJson);
        } catch (Exception e) {
            throw new com.nageoffer.ai.ragent.framework.exception.ClientException("请求体不是合法 JSON：" + e.getMessage());
        }
    }

    @Data
    public static class TestProviderRequest {
        private String provider;
        private String apiKey;
        private String url;
        private String chatEndpoint;
    }
}
