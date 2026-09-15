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

package com.nageoffer.ai.ragent.rag.aop;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.framework.trace.TraceDegradable;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Date;

/**
 * 注解式 RAG Trace 采集切面
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RagTraceAspect {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_DEGRADED = "DEGRADED";

    private final RagTraceRecordService traceRecordService;
    private final RagTraceProperties traceProperties;

    @Around("@annotation(traceNode)")
    public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) throws Throwable {
        if (!traceProperties.isEnabled()) {
            return joinPoint.proceed();
        }
        String traceId = RagTraceContext.getTraceId();
        if (StrUtil.isBlank(traceId)) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String nodeId = IdUtil.getSnowflakeNextIdStr();
        String parentNodeId = RagTraceContext.currentNodeId();
        int depth = RagTraceContext.depth();
        Date startTime = new Date();
        long startMillis = System.currentTimeMillis();

        traceRecordService.startNode(RagTraceNodeDO.builder()
                .traceId(traceId)
                .nodeId(nodeId)
                .parentNodeId(parentNodeId)
                .depth(depth)
                .nodeType(StrUtil.blankToDefault(traceNode.type(), "METHOD"))
                .nodeName(StrUtil.blankToDefault(traceNode.name(), method.getName()))
                .className(method.getDeclaringClass().getName())
                .methodName(method.getName())
                .status(STATUS_RUNNING)
                .startTime(startTime)
                .build());

        RagTraceContext.pushNode(nodeId);
        try {
            Object result = joinPoint.proceed();
            // 方法没抛异常不等于这一步真的成功：检索类节点会把 embedding 401、向量库不可达
            // 吞成空结果继续往下走，只有返回值自己能说出这件事，故按 TraceDegradable 分流
            String degradedReason = resolveDegradedReason(result);
            traceRecordService.finishNode(
                    traceId,
                    nodeId,
                    degradedReason == null ? STATUS_SUCCESS : STATUS_DEGRADED,
                    degradedReason,
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            return result;
        } catch (Throwable ex) {
            traceRecordService.finishNode(
                    traceId,
                    nodeId,
                    STATUS_ERROR,
                    truncateError(ex),
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            throw ex;
        } finally {
            RagTraceContext.popNode();
        }
    }

    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": " + StrUtil.blankToDefault(throwable.getMessage(), "");
        if (message.length() <= traceProperties.getMaxErrorLength()) {
            return message;
        }
        return message.substring(0, traceProperties.getMaxErrorLength());
    }

    /**
     * 读取返回值自报的降级原因，非 TraceDegradable 或未降级时返回 null
     */
    private String resolveDegradedReason(Object result) {
        if (!(result instanceof TraceDegradable degradable)) {
            return null;
        }
        String reason = degradable.traceDegradedReason();
        if (StrUtil.isBlank(reason)) {
            return null;
        }
        int max = traceProperties.getMaxErrorLength();
        return reason.length() <= max ? reason : reason.substring(0, max);
    }
}
