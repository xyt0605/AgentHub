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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * RAG Trace 记录服务实现
 */
@Service
@RequiredArgsConstructor
public class RagTraceRecordServiceImpl implements RagTraceRecordService {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_DEGRADED = "DEGRADED";

    private final RagTraceRunMapper runMapper;
    private final RagTraceNodeMapper nodeMapper;

    @Override
    public void startRun(RagTraceRunDO run) {
        runMapper.insert(run);
    }

    @Override
    public void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs) {
        // 成功收尾不能抹掉已经记下的 DEGRADED：那条记录正是「表面答完了、实则链路有故障」的唯一痕迹。
        // 故成功路径先按「仅当状态仍是 RUNNING」写，未命中说明已降级，再补写一次纯耗时；
        // 失败收尾无条件覆盖——ERROR 比 DEGRADED 更重
        boolean preserveDegraded = STATUS_SUCCESS.equals(status);
        RagTraceRunDO update = RagTraceRunDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .build();
        int updated = runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId)
                .eq(preserveDegraded, RagTraceRunDO::getStatus, STATUS_RUNNING));
        if (preserveDegraded && updated == 0) {
            runMapper.update(
                    RagTraceRunDO.builder().endTime(endTime).durationMs(durationMs).build(),
                    Wrappers.lambdaUpdate(RagTraceRunDO.class)
                            .eq(RagTraceRunDO::getTraceId, traceId));
        }
    }

    @Override
    public void markRunDegraded(String traceId, String reason) {
        runMapper.update(
                RagTraceRunDO.builder().status(STATUS_DEGRADED).errorMessage(reason).build(),
                Wrappers.lambdaUpdate(RagTraceRunDO.class)
                        .eq(RagTraceRunDO::getTraceId, traceId)
                        .eq(RagTraceRunDO::getStatus, STATUS_RUNNING));
    }

    @Override
    public void startNode(RagTraceNodeDO node) {
        nodeMapper.insert(node);
    }

    @Override
    public void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs) {
        RagTraceNodeDO update = RagTraceNodeDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .build();
        nodeMapper.update(update, Wrappers.lambdaUpdate(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId)
                .eq(RagTraceNodeDO::getNodeId, nodeId));
    }
}
