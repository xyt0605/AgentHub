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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具桥接
 * <p>
 * 把 rag 既有 McpToolRegistry 中的远程 MCP 工具适配为 AgentScope 的
 * {@link AgentTool}，复用同一份连接与工具元数据。根 pom 约定：agent 模块
 * 不得使用 AgentScope 自带的 MCP 客户端（SDK 版本冲突），一律经此桥接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolBridge {

    private static final TypeReference<Map<String, Object>> SCHEMA_TYPE = new TypeReference<>() {
    };

    private final McpToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    /**
     * @return 当前已注册的全部 MCP 工具的 AgentScope 适配视图
     */
    public List<AgentTool> bridgeAll() {
        List<McpToolExecutor> executors = toolRegistry.listAllExecutors();
        List<AgentTool> tools = new ArrayList<>(executors.size());
        for (McpToolExecutor executor : executors) {
            try {
                tools.add(bridge(executor));
            } catch (Exception e) {
                log.warn("MCP 工具桥接失败, toolId={}, reason={}", executor.getToolId(), e.getMessage());
            }
        }
        log.info("MCP 工具桥接完成, registered={}, bridged={}", executors.size(), tools.size());
        return tools;
    }

    private AgentTool bridge(McpToolExecutor executor) {
        McpSchema.Tool definition = executor.getToolDefinition();
        String name = definition.name();
        String description = StrUtil.emptyIfNull(definition.description());
        Map<String, Object> parameters = toSchemaMap(definition);
        return new AgentTool() {

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public Map<String, Object> getParameters() {
                return parameters;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> execute(executor, param.getInput()))
                        .subscribeOn(Schedulers.boundedElastic());
            }
        };
    }

    private ToolResultBlock execute(McpToolExecutor executor, Map<String, Object> input) {
        long startMs = System.currentTimeMillis();
        McpSchema.CallToolResult result = executor.execute(input);
        String text = renderResult(result);
        boolean failed = Boolean.TRUE.equals(result.isError());
        log.info("Agent 调用 MCP 工具完成, toolId={}, elapsed={}ms, failed={}",
                executor.getToolId(), System.currentTimeMillis() - startMs, failed);
        return failed ? ToolResultBlock.error(text) : ToolResultBlock.text(text);
    }

    /**
     * 工具结果统一渲染为文本，供模型阅读
     */
    private String renderResult(McpSchema.CallToolResult result) {
        List<McpSchema.Content> contents = result.content();
        if (contents == null || contents.isEmpty()) {
            return "（工具返回空结果）";
        }
        StringBuilder output = new StringBuilder();
        for (McpSchema.Content content : contents) {
            if (output.length() > 0) {
                output.append('\n');
            }
            if (content instanceof McpSchema.TextContent textContent) {
                output.append(textContent.text());
            } else {
                output.append("（").append(content.type()).append(" 类型内容，略）");
            }
        }
        return output.toString();
    }

    /**
     * MCP JSON Schema 转 AgentScope 参数声明；转换失败回落为无参对象 schema
     */
    private Map<String, Object> toSchemaMap(McpSchema.Tool definition) {
        try {
            Map<String, Object> schema = objectMapper.convertValue(definition.inputSchema(), SCHEMA_TYPE);
            if (schema != null && !schema.isEmpty()) {
                return schema;
            }
        } catch (Exception e) {
            log.warn("MCP 工具 schema 转换失败, toolId={}, reason={}", definition.name(), e.getMessage());
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("type", "object");
        fallback.put("properties", Map.of());
        return fallback;
    }
}
