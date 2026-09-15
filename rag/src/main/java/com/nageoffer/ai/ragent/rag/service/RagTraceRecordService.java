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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;

import java.util.Date;

/**
 * RAG Trace 记录服务
 */
public interface RagTraceRecordService {

    void startRun(RagTraceRunDO run);

    void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs);

    /**
     * 把运行中的 run 标记为降级：链路跑通了，但中途有组件故障被吞掉（典型如检索通道鉴权失败）
     * <p>
     * 只作用于 RUNNING 的 run，且随后的 finishRun 成功收尾不会覆盖该状态——
     * 否则「答了一句兜底话」的请求会和真正健康的请求一样记成 SUCCESS，链路追踪列表里无从分辨
     */
    void markRunDegraded(String traceId, String reason);

    void startNode(RagTraceNodeDO node);

    void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs);
}
