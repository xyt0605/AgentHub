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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.framework.trace.TraceDegradable;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多通道检索产出
 *
 * @param channelFailures 本次检索中技术性失败的通道描述（形如 "VectorSearch: ..."），空表示全部通道正常跑完。
 *                        与 chunks 为空是正交的两件事：空 chunks + 无故障 = 知识库确实没有；
 *                        空 chunks + 有故障 = 根本没查成，二者对用户与排查的含义完全不同
 */
public record KnowledgeRetrievalResult(List<RetrievedChunk> chunks,
                                       Map<String, Set<String>> intentIdsByChunkKey,
                                       Set<String> directedIntentIds,
                                       List<String> channelFailures) implements TraceDegradable {

    public KnowledgeRetrievalResult {
        chunks = chunks == null ? List.of() : chunks;
        intentIdsByChunkKey = intentIdsByChunkKey == null ? Map.of() : intentIdsByChunkKey;
        directedIntentIds = directedIntentIds == null
                ? Set.of()
                : Set.copyOf(directedIntentIds);
        channelFailures = channelFailures == null ? List.of() : List.copyOf(channelFailures);
    }

    public static KnowledgeRetrievalResult empty() {
        return new KnowledgeRetrievalResult(List.of(), Map.of(), Set.of(), List.of());
    }

    /**
     * 全通道正常跑完时的构造：无故障可报
     */
    public KnowledgeRetrievalResult(List<RetrievedChunk> chunks,
                                    Map<String, Set<String>> intentIdsByChunkKey,
                                    Set<String> directedIntentIds) {
        this(chunks, intentIdsByChunkKey, directedIntentIds, List.of());
    }

    /**
     * 有通道故障即算降级，哪怕其余通道仍召回到内容——证据集已经不完整，
     * 用「还有结果」掩盖故障正是这次排查绕了大圈的原因
     */
    @Override
    public String traceDegradedReason() {
        return channelFailures.isEmpty() ? null : String.join("；", channelFailures);
    }

    public Set<String> retrievedIntentIds() {
        Set<String> intentIds = new LinkedHashSet<>();
        intentIdsByChunkKey.values().stream()
                .filter(Objects::nonNull)
                .forEach(intentIds::addAll);
        return Collections.unmodifiableSet(intentIds);
    }

    public Set<String> eligibleIntentIds(List<NodeScore> candidateIntents) {
        Set<String> retrievedIntentIds = retrievedIntentIds();
        return candidateIntents.stream()
                .filter(Objects::nonNull)
                .map(NodeScore::getNode)
                .filter(Objects::nonNull)
                .map(IntentNode::getId)
                .filter(StrUtil::isNotBlank)
                .filter(intentId -> !directedIntentIds.contains(intentId)
                        || retrievedIntentIds.contains(intentId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Map<String, List<RetrievedChunk>> groupByIntent(String globalKey) {
        Map<String, List<RetrievedChunk>> grouped = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            Set<String> intentIds = intentIdsByChunkKey.get(RetrievedChunkKey.of(chunk));
            if (intentIds == null || intentIds.isEmpty()) {
                grouped.computeIfAbsent(globalKey, ignored -> new ArrayList<>()).add(chunk);
                continue;
            }
            for (String intentId : intentIds) {
                grouped.computeIfAbsent(intentId, ignored -> new ArrayList<>()).add(chunk);
            }
        }
        return grouped;
    }
}
