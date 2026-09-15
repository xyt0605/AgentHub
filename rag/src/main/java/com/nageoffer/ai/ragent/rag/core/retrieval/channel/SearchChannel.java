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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import java.util.List;

/**
 * 检索通道接口
 * <p>
 * 每个通道负责一种检索策略，例如：
 * - 向量全局检索
 * - 意图定向检索
 * - ES 关键词检索
 * <p>
 * 多个通道可以并行执行，最后统一合并结果
 */
public interface SearchChannel {

    /**
     * 通道名称（用于日志和监控）
     */
    String getName();

    /**
     * 是否启用该通道
     *
     * @param context 检索上下文
     * @return true 表示启用，false 表示跳过
     */
    boolean isEnabled(SearchContext context);

    /**
     * 执行检索
     *
     * @param context 检索上下文
     * @return 检索结果
     */
    SearchChannelResult search(SearchContext context);

    /**
     * 通道类型
     */
    SearchChannelType getType();

    /**
     * 空结果交卷：通道正常跑完但无数据可召回时的形态，只带通道身份与耗时
     * 表达「查过了，确实没有」，与 {@link #failedResult} 的「没查成」互斥
     */
    default SearchChannelResult emptyResult(long latencyMs) {
        return SearchChannelResult.builder()
                .channelType(getType())
                .channelName(getName())
                .chunks(List.of())
                .latencyMs(latencyMs)
                .build();
    }

    /**
     * 故障交卷：通道因技术原因没能完成检索（鉴权失败、后端不可达、超时等）
     * <p>
     * 形状与空结果一致好让融合层无差别处理，但多带一个 failed 标记：
     * 下游据此把「服务异常」与「知识库没这内容」分开呈现，不再让 401 伪装成查无此文
     */
    default SearchChannelResult failedResult(long latencyMs, String reason) {
        return SearchChannelResult.builder()
                .channelType(getType())
                .channelName(getName())
                .chunks(List.of())
                .latencyMs(latencyMs)
                .failed(true)
                .failureReason(reason)
                .build();
    }

    /**
     * 把异常压成一行归因文本
     * <p>
     * 取抛出处而非根因的 message：路由类异常（「All Embedding model candidates failed: ...」）
     * 已在最外层汇总了全部候选的失败缘由，根因只剩最后一个候选的细节，信息反而更少
     */
    static String describeFailure(Throwable error) {
        if (error == null) {
            return "未知故障";
        }
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }
}
