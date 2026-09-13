import { api } from "@/services/api";

// ==================== 命名空间摘要 ====================

export interface ConfigNamespace {
  key: string;
  label: string;
  description: string;
  overridden: boolean;
  updateBy?: string | null;
  updateTime?: string | null;
}

// ==================== ai 命名空间（AI 模型服务） ====================

export interface AiProviderConfig {
  url: string;
  apiKey?: string | null;
  endpoints: Record<string, string>;
}

export interface EditableModelCandidate {
  id: string;
  provider: string;
  model: string;
  url?: string | null;
  dimension?: number | null;
  priority?: number | null;
  enabled?: boolean | null;
  supportsThinking?: boolean | null;
}

export interface TierConfig {
  candidates: string[];
  timeoutMs?: number | null;
}

export interface EditableModelGroup {
  defaultModel?: string | null;
  candidates: EditableModelCandidate[];
  defaultTier?: string | null;
  deepThinkingTier?: string | null;
  tiers?: Record<string, TierConfig> | null;
}

export interface AiConfigPayload {
  providers: Record<string, AiProviderConfig>;
  chat: EditableModelGroup;
  embedding: EditableModelGroup;
  rerank: EditableModelGroup;
  vlm?: EditableModelGroup | null;
  selection: {
    failureThreshold: number;
    openDurationMs: number;
  };
  stream: {
    messageChunkSize: number;
  };
}

// ==================== agent 命名空间（Agent 引擎） ====================

export interface AgentConfigPayload {
  chat: {
    provider: string;
    model: string;
  };
  maxIters: number;
  maxRetries: number;
  sseTimeoutMs: number;
  kbTopK: number;
}

// ==================== pipeline 命名空间（检索管线） ====================

export interface PipelineConfigPayload {
  features: {
    queryRewriteEnabled: boolean;
    rerankEnabled: boolean;
    citationEnabled: boolean;
    contextEnrichEnabled: boolean;
  };
  search: {
    defaultTopK: number;
    recallBudget: number;
    scope: {
      minIntentScore: number;
      confidenceThreshold: number;
      supplementRatio: number;
    };
    channels: {
      timeoutMs: number;
      vector: { enabled: boolean };
      keyword: { enabled: boolean };
      graph: { enabled: boolean };
      webSearch: {
        enabled: boolean;
        count: number;
        timeoutSeconds: number;
        apiKey?: string | null;
        apiUrl: string;
      };
    };
    fusion: {
      strategy: string;
      rrfK: number;
      rerankCandidateLimit: number;
      channelWeights: {
        vector: number;
        keyword: number;
        graph: number;
        webSearch: number;
        defaultWeight: number;
      };
    };
  };
  memory: {
    historyKeepTurns: number;
    summaryEnabled: boolean;
    summaryStartTurns: number;
    summaryMaxChars: number;
    titleMaxLength: number;
  };
  rateLimit: {
    globalEnabled: boolean;
    globalMaxConcurrent: number;
    globalMaxWaitSeconds: number;
    globalLeaseSeconds: number;
    globalPollIntervalMs: number;
  };
}

// ==================== 供应商连通性测试 ====================

export interface ProviderTestResult {
  ok: boolean;
  status: number;
  latencyMs: number;
  message: string;
  modelsUrl: string;
}

// ==================== API ====================

export function listConfigNamespaces(): Promise<ConfigNamespace[]> {
  return api.get<ConfigNamespace[], ConfigNamespace[]>("/admin/configs");
}

export function getConfigPayload<T>(namespace: string): Promise<T> {
  return api.get<T, T>(`/admin/configs/${namespace}`);
}

export function saveConfigPayload(namespace: string, payload: unknown): Promise<void> {
  return api.put(`/admin/configs/${namespace}`, payload);
}

export function resetConfigPayload(namespace: string): Promise<void> {
  return api.delete(`/admin/configs/${namespace}`);
}

export function testProvider(
  provider: string,
  apiKey?: string,
  draft?: { url?: string; chatEndpoint?: string }
): Promise<ProviderTestResult> {
  return api.post<ProviderTestResult, ProviderTestResult>("/admin/configs/ai/test-provider", {
    provider,
    apiKey,
    url: draft?.url,
    chatEndpoint: draft?.chatEndpoint
  });
}

/** 各模型能力支持哪些供应商（由后端容器实际注册的客户端推导），如 { chat: [...], embedding: [...] } */
export function getModelCapabilities(): Promise<Record<string, string[]>> {
  return api.get<Record<string, string[]>, Record<string, string[]>>("/admin/configs/ai/capabilities");
}

/** 拉取供应商可用模型列表（OpenAI 兼容 /models），支持未保存草稿 */
export function listProviderModels(
  provider: string,
  apiKey?: string,
  draft?: { url?: string; chatEndpoint?: string }
): Promise<string[]> {
  return api.get<string[], string[]>("/admin/configs/ai/models", {
    params: {
      provider,
      apiKey: apiKey || undefined,
      url: draft?.url || undefined,
      chatEndpoint: draft?.chatEndpoint || undefined
    }
  });
}
