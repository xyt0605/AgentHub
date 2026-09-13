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
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具（Agent 主模型的 Tool）
 * <p>
 * 将 rag 既有向量检索门面包装为 ReAct 工具：模型在需要用户私有知识时
 * 主动调用，检索默认 Collection（与 workflow 的兜底全库检索同源）。
 * 检索结果以编号片段文本回填，供模型引用作答。
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeSearchTool implements AgentTool {

    private static final String TOOL_NAME = "knowledge_search";

    private final VectorRetrieverService retrieverService;
    private final RAGDefaultProperties defaultProperties;
    private final int defaultTopK;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "在本地知识库中检索与查询最相关的文档片段（制度、规范、业务知识等私有资料）。"
                + "回答知识库类问题前必须先调用本工具，基于检索结果作答并注明出处编号；"
                + "检索不到时如实说明，不要编造。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description", "检索查询语句，用适合匹配文档的完整中文短语描述要找的信息");

        Map<String, Object> topK = new LinkedHashMap<>();
        topK.put("type", "integer");
        topK.put("description", "返回的片段数量，可选，默认 " + defaultTopK + "，范围 1-10");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", query);
        properties.put("top_k", topK);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> doSearch(param.getInput()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock doSearch(Map<String, Object> input) {
        Object queryValue = input == null ? null : input.get("query");
        if (!(queryValue instanceof String query) || query.isBlank()) {
            return ToolResultBlock.error("参数 query 不能为空");
        }
        int topK = parseTopK(input.get("top_k"));
        long startMs = System.currentTimeMillis();
        List<RetrievedChunk> chunks;
        try {
            chunks = retrieverService.retrieve(RetrieveRequest.builder()
                    .query(query)
                    .topK(topK)
                    .collectionName(defaultProperties.getCollectionName())
                    .build());
        } catch (Exception e) {
            log.warn("知识库检索工具执行失败, query={}, reason={}", query, e.getMessage());
            return ToolResultBlock.error("知识库检索失败: " + e.getMessage());
        }
        if (chunks == null || chunks.isEmpty()) {
            log.info("知识库检索工具无结果, query={}, elapsed={}ms", query, System.currentTimeMillis() - startMs);
            return ToolResultBlock.text("知识库中未检索到与「" + query + "」相关的内容。");
        }
        StringBuilder output = new StringBuilder("共检索到 ").append(chunks.size()).append(" 条知识库片段：\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            output.append("[").append(i + 1).append("] ");
            if (StrUtil.isNotBlank(chunk.getDocName())) {
                output.append("来源文档：").append(chunk.getDocName());
            }
            if (chunk.getScore() != null) {
                output.append("（相关度 ").append(String.format("%.3f", chunk.getScore())).append("）");
            }
            output.append('\n')
                    .append(StrUtil.nullToEmpty(chunk.getText()))
                    .append("\n\n");
        }
        log.info("知识库检索工具完成, query={}, hits={}, elapsed={}ms",
                query, chunks.size(), System.currentTimeMillis() - startMs);
        return ToolResultBlock.text(output.toString());
    }

    private int parseTopK(Object value) {
        int topK = defaultTopK;
        if (value instanceof Number number) {
            topK = number.intValue();
        } else if (value instanceof String text && !text.isBlank()) {
            try {
                topK = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                // 回落默认值
            }
        }
        return Math.max(1, Math.min(10, topK));
    }
}
