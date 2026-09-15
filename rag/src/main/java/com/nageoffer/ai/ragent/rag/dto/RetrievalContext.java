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

package com.nageoffer.ai.ragent.rag.dto;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.TraceDegradable;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索上下文（MCP + KB 结果的统一承载）
 */
@Data
@Builder
public class RetrievalContext implements TraceDegradable {

    /**
     * MCP 召回的上下文
     */
    private String mcpContext;

    /**
     * KB 召回的上下文
     */
    private String kbContext;

    /**
     * 意图 ID -> 分片列表
     */
    private Map<String, List<RetrievedChunk>> intentChunks;

    /**
     * 允许参与模板选择和规则注入的意图 ID
     */
    @Builder.Default
    private Set<String> eligibleIntentIds = Set.of();

    /**
     * 本次检索中技术性失败的通道描述（形如 "VectorSearch: ..."）
     * <p>
     * 空上下文的两种成因必须分开：通道正常跑完但没匹配到，是「知识库里确实没有」；
     * 通道压根没查成（鉴权失败 / 后端不可达 / 超时），是服务异常。
     * 二者混在一起时，密钥失效会伪装成查无此文，排查要绕到日志里才看得见
     */
    @Builder.Default
    private List<String> channelFailures = List.of();

    /**
     * 是否存在 MCP 上下文
     */
    public boolean hasMcp() {
        return StrUtil.isNotBlank(mcpContext);
    }

    /**
     * 是否存在 KB 上下文
     */
    public boolean hasKb() {
        return StrUtil.isNotBlank(kbContext);
    }

    /**
     * 是否无任何上下文
     */
    public boolean isEmpty() {
        return !hasMcp() && !hasKb();
    }

    /**
     * 检索链路是否发生技术性故障（与是否召回到内容正交）
     */
    public boolean isDegraded() {
        return channelFailures != null && !channelFailures.isEmpty();
    }

    @Override
    public String traceDegradedReason() {
        return isDegraded() ? String.join("；", channelFailures) : null;
    }
}
