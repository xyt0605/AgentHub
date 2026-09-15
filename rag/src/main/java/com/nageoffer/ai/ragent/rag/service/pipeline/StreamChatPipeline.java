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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.source.CitationContextEnricher;
import com.nageoffer.ai.ragent.rag.core.source.GroundingChunksAssembler;
import com.nageoffer.ai.ragent.rag.core.source.SourcesAssembler;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.trace.StreamChatTraceRunner;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式对话流水线
 * <p>
 * 承载从 RAGChatServiceImpl 提取的业务编排逻辑：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 系统响应 / 检索 -> Prompt 组装 -> 流式输出
 * <p>
 * 流水线模式：通过私有方法 + boolean 返回值（handleXxx 返回 true 表示已处理并短路）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    private final ConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final AgentPromptResolver agentPromptResolver;
    private final StreamTaskManager taskManager;
    private final SourcesAssembler sourcesAssembler;
    private final GroundingChunksAssembler groundingChunksAssembler;
    private final CitationContextEnricher citationContextEnricher;
    private final StreamChatTraceRunner traceRunner;

    /**
     * 执行流式对话管道
     */
    public void execute(StreamChatContext ctx) {
        loadMemory(ctx);
        rewriteQuery(ctx);
        resolveIntents(ctx);

        if (handleGuidance(ctx)) {
            return;
        }
        if (handleSystemOnly(ctx)) {
            return;
        }

        RetrievalContext retrievalCtx = retrieve(ctx);
        // 故障与是否召回到内容正交：哪怕还有别的通道出了证据，这次的证据集也已不完整，
        // 一律先落进 trace，再决定怎么回话
        if (retrievalCtx.isDegraded()) {
            log.error("检索链路故障，故障通道：{}", retrievalCtx.getChannelFailures());
            markRunDegraded(retrievalCtx);
        }
        if (handleEmptyRetrieval(ctx, retrievalCtx)) {
            return;
        }

        streamRagResponse(ctx, retrievalCtx);
    }

    // ==================== 流水线阶段 ====================

    private void loadMemory(StreamChatContext ctx) {
        List<ChatMessage> history = memoryService.load(ctx.getConversationId(), ctx.getUserId());
        String questionMessageId = memoryService.append(
                ctx.getConversationId(), ctx.getUserId(), ChatMessage.user(ctx.getQuestion()));
        ctx.getCallback().onReplyToMessageId(questionMessageId);
        ctx.setHistory(history);
    }

    private void rewriteQuery(StreamChatContext ctx) {
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(ctx.getQuestion(), ctx.getHistory());
        ctx.setRewriteResult(rewriteResult);
    }

    private void resolveIntents(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = intentResolver.resolve(ctx.getRewriteResult());
        ctx.setSubIntents(subIntents);
    }

    private boolean handleGuidance(StreamChatContext ctx) {
        GuidanceDecision decision = guidanceService.detectAmbiguity(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getSubIntents()
        );
        if (!decision.isPrompt()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent(decision.getPrompt());
        callback.onComplete();
        return true;
    }

    private boolean handleSystemOnly(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = ctx.getSubIntents();
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        if (!allSystemOnly) {
            return false;
        }
        String customPrompt = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .map(ns -> ns.getNode().getPromptTemplate())
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        StreamCancellationHandle handle = streamSystemResponse(
                ctx,
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getHistory(),
                customPrompt,
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle == null ? null : handle::cancel);
        return true;
    }

    private RetrievalContext retrieve(StreamChatContext ctx) {
        return retrievalEngine.retrieve(ctx.getSubIntents());
    }

    private boolean handleEmptyRetrieval(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        if (!retrievalCtx.isEmpty()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        if (retrievalCtx.isDegraded()) {
            // 检索没查成 ≠ 知识库里没有。沿用兜底文案会让鉴权失效、向量库不可达
            // 伪装成「查无此文」：用户以为库里缺资料，实际是链路坏了，排查只能靠翻日志
            callback.onContent("检索服务暂时不可用，未能完成本次知识库检索，请稍后重试或联系管理员。");
            callback.onComplete();
            return true;
        }
        callback.onContent("未检索到与问题相关的文档内容。");
        callback.onComplete();
        return true;
    }

    /**
     * 把降级写进 trace run，让链路追踪列表能直接筛出这类「答了，但答得不对劲」的请求
     * <p>
     * 必须在 onComplete 之前调用：onComplete 会触发 finishRun 收尾，晚于它写入就被 SUCCESS 覆盖
     */
    private void markRunDegraded(RetrievalContext retrievalCtx) {
        try {
            traceRunner.markDegraded(retrievalCtx.traceDegradedReason());
        } catch (Exception e) {
            log.warn("标记 trace 降级失败，不影响对话返回", e);
        }
    }

    private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        // 聚合所有意图用于 prompt 规划
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(ctx.getSubIntents());

        // 检索完成后建立唯一来源编号：同一列表用于完成事件、来源面板与消息落库，开启引用时还作为行内角标编号
        List<SourceRef> sources = sourcesAssembler.assemble(retrievalCtx.getIntentChunks());
        ctx.getCallback().onSources(sources);
        // 开关关闭时这一步只负责清掉上下文里的内部 docId，不注入编号
        retrievalCtx.setKbContext(citationContextEnricher.enrich(retrievalCtx.getKbContext(), sources));

        // 装配 grounding 片段随消息落库 供答案后推荐追问生成 grounding（不参与 prompt）
        ctx.getCallback().onGroundingChunks(groundingChunksAssembler.assemble(retrievalCtx.getIntentChunks()));

        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                retrievalCtx,
                mergedGroup,
                ctx.getHistory(),
                ctx.isDeepThinking(),
                ctx.getPreferredModelId(),
                ctx.getTierKey(),
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle == null ? null : handle::cancel);
    }

    // ==================== LLM 响应 ====================

    private StreamCancellationHandle streamSystemResponse(StreamChatContext ctx, String question,
                                                          List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : agentPromptResolver.resolve(AgentPromptSlot.SYSTEM_CHAT);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .preferredModelId(ctx.getPreferredModelId())
                .tierKey(ctx.getTierKey())
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, String preferredModelId, String tierKey,
                                                       StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .eligibleIntentIds(ctx.getEligibleIntentIds())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .preferredModelId(preferredModelId)
                .tierKey(tierKey)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }
}
