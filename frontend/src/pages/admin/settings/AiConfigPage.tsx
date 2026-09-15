import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  AlertCircle,
  Bot,
  Check,
  ChevronDown,
  ChevronUp,
  Cpu,
  Loader2,
  Plug,
  Plus,
  RefreshCw,
  RotateCcw,
  Save,
  Trash2,
  Workflow,
  X
} from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/utils/error";
import {
  getConfigPayload,
  getModelCapabilities,
  listConfigNamespaces,
  listProviderModels,
  resetConfigPayload,
  saveConfigPayload,
  testProvider,
  type AgentConfigPayload,
  type AiConfigPayload,
  type AiProviderConfig,
  type ConfigNamespace,
  type EditableModelCandidate,
  type EditableModelGroup,
  type PipelineConfigPayload,
  type ProviderTestResult
} from "@/services/dynamicConfigService";

// ==================== 通用小组件 ====================

/** 命名空间区块卡片：标题 + 覆盖状态徽标 + 保存 / 恢复默认 */
function NamespaceCard({
  icon: Icon,
  title,
  hint,
  namespace,
  overridden,
  updateBy,
  updateTime,
  onSave,
  saving,
  onReset,
  resetting,
  children
}: {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  hint: string;
  namespace: string;
  overridden: boolean;
  updateBy?: string | null;
  updateTime?: string | null;
  onSave: () => void;
  saving: boolean;
  onReset: () => void;
  resetting: boolean;
  children: ReactNode;
}) {
  return (
    <section className="settings-section" data-namespace={namespace}>
      <div className="settings-section-head">
        <div className="flex items-center gap-2">
          <span className="settings-icon is-cyan h-8 w-8">
            <Icon className="h-4 w-4" />
          </span>
          <h2 className="settings-section-title">{title}</h2>
          {overridden ? (
            <span className="settings-tag is-on">数据库覆盖</span>
          ) : (
            <span className="settings-tag is-off">yaml 默认</span>
          )}
          {overridden && updateBy ? (
            <span className="text-xs text-zinc-500">
              {updateBy} · {updateTime ? new Date(updateTime).toLocaleString() : ""}
            </span>
          ) : null}
        </div>
        <div className="flex items-center gap-2">
          <span className="settings-section-hint">{hint}</span>
          {overridden ? (
            <Button variant="outline" size="sm" onClick={onReset} disabled={resetting || saving}>
              <RotateCcw className="mr-1.5 h-3.5 w-3.5" />
              恢复默认
            </Button>
          ) : null}
          <Button size="sm" onClick={onSave} disabled={saving || resetting}>
            {saving ? (
              <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
            ) : (
              <Save className="mr-1.5 h-3.5 w-3.5" />
            )}
            保存
          </Button>
        </div>
      </div>
      {children}
    </section>
  );
}

function SubCard({
  title,
  hint,
  children,
  actions
}: {
  title: string;
  hint?: string;
  children: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="settings-card">
      <div className="settings-card-title">
        {title}
        {hint ? <span className="settings-card-title-hint">{hint}</span> : null}
        {actions ? <span className="ml-auto">{actions}</span> : null}
      </div>
      {children}
    </div>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="space-y-1.5">
      <label className="flex items-baseline gap-2 text-xs font-medium text-zinc-300">
        {label}
        {hint ? <span className="font-normal text-zinc-500">{hint}</span> : null}
      </label>
      {children}
    </div>
  );
}

function NumInput({
  value,
  onChange,
  min,
  max,
  step = 1,
  placeholder
}: {
  value: number;
  onChange: (value: number) => void;
  min?: number;
  max?: number;
  step?: number;
  placeholder?: string;
}) {
  return (
    <Input
      type="number"
      value={Number.isFinite(value) ? String(value) : ""}
      min={min}
      max={max}
      step={step}
      placeholder={placeholder}
      onChange={(event) => {
        const next = Number(event.target.value);
        if (!Number.isNaN(next)) {
          onChange(next);
        }
      }}
      className="font-mono"
    />
  );
}

/** 深色主题迷你开关 */
function Toggle({
  checked,
  onChange,
  disabled
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn(
        "relative inline-flex h-5 w-9 shrink-0 items-center rounded-full border transition-colors",
        checked ? "border-violet-400/40 bg-violet-500/80" : "border-white/10 bg-white/10",
        disabled && "opacity-50"
      )}
    >
      <span
        className={cn(
          "inline-block h-3.5 w-3.5 rounded-full bg-white shadow transition-transform",
          checked ? "translate-x-[18px]" : "translate-x-[3px]"
        )}
      />
    </button>
  );
}

/** API Key 输入：掩码值原样展示，未改动保存时后端自动保留原值 */
function SecretInput({
  value,
  onChange
}: {
  value?: string | null;
  onChange: (next: string) => void;
}) {
  const isMasked = Boolean(value && value.includes("***"));
  return (
    <Input
      value={value ?? ""}
      placeholder={isMasked ? "已配置，保持原样则不修改" : "未配置"}
      onChange={(event) => onChange(event.target.value)}
      className="font-mono text-xs"
      autoComplete="off"
      spellCheck={false}
    />
  );
}

/** 供应商连通性测试按钮 + 结果行内展示（支持测试尚未保存的草稿供应商） */
function ProviderTestButton({
  provider,
  apiKey,
  url,
  chatEndpoint
}: {
  provider: string;
  apiKey?: string | null;
  url?: string;
  chatEndpoint?: string;
}) {
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<ProviderTestResult | null>(null);

  const run = async () => {
    setTesting(true);
    setResult(null);
    try {
      const res = await testProvider(provider, apiKey ?? undefined, {
        url: url || undefined,
        chatEndpoint: chatEndpoint || undefined
      });
      setResult(res);
      if (res.ok) {
        toast.success(`${provider} 连接成功`);
      } else {
        toast.error(`${provider}：${res.message}`);
      }
    } catch (error) {
      toast.error(getErrorMessage(error, "连通性测试失败"));
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="flex flex-col items-start gap-1">
      <Button variant="outline" size="sm" onClick={run} disabled={testing}>
        {testing ? (
          <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
        ) : (
          <Plug className="mr-1.5 h-3.5 w-3.5" />
        )}
        测试连通
      </Button>
      {result ? (
        <span
          className={cn("text-xs", result.ok ? "text-emerald-400" : "text-rose-400")}
          title={result.modelsUrl}
        >
          {result.ok ? (
            <Check className="mr-1 inline h-3 w-3" />
          ) : (
            <X className="mr-1 inline h-3 w-3" />
          )}
          {result.message}
        </span>
      ) : null}
      {/* 逐候选展开：整体失败时用户要能一眼看出是哪个模型、哪种能力挂了，
          否则又退回「绿勾/红叉都只给一句话」的老问题 */}
      {result?.capabilities?.length ? (
        <div className="flex flex-col gap-0.5 pl-4">
          {result.capabilities.map((probe) => (
            <span
              key={`${probe.capability}-${probe.modelId}`}
              className={cn("text-[11px]", probe.ok ? "text-zinc-400" : "text-rose-400")}
              title={probe.message}
            >
              {probe.ok ? "✓" : "✗"} {probe.capability} · {probe.modelId}
              <span className="text-zinc-500"> — {probe.message}</span>
            </span>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function RowRemoveButton({ onClick, title }: { onClick: () => void; title?: string }) {
  return (
    <Button
      variant="ghost"
      size="icon"
      className="h-7 w-7 text-zinc-500 hover:text-rose-400"
      onClick={onClick}
      title={title ?? "删除"}
    >
      <Trash2 className="h-3.5 w-3.5" />
    </Button>
  );
}

// 模型列表会话级缓存：同一供应商 + baseUrl 只拉取一次（供应商密钥变更后刷新页面即可重新拉取）
const modelListCache = new Map<string, string[]>();

/** 模型名选择器：点开自动拉取供应商模型列表，输入即过滤，选不到的仍可手动输入 */
function ModelCombobox({
  value,
  onChange,
  provider,
  providers
}: {
  value: string;
  onChange: (next: string) => void;
  provider: string;
  providers: Record<string, AiProviderConfig>;
}) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [models, setModels] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const blurTimerRef = useRef<number | null>(null);

  const providerDraft = providers[provider];
  const cacheKey = `${provider}|${providerDraft?.url ?? ""}`;

  const loadModels = async () => {
    const cached = modelListCache.get(cacheKey);
    if (cached) {
      setModels(cached);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const list = await listProviderModels(provider, providerDraft?.apiKey ?? undefined, {
        url: providerDraft?.url,
        chatEndpoint: providerDraft?.endpoints?.chat
      });
      modelListCache.set(cacheKey, list ?? []);
      setModels(list ?? []);
    } catch (err) {
      setError(getErrorMessage(err, "模型列表拉取失败"));
    } finally {
      setLoading(false);
    }
  };

  const handleFocus = () => {
    if (blurTimerRef.current) {
      window.clearTimeout(blurTimerRef.current);
      blurTimerRef.current = null;
    }
    setOpen(true);
    if (!models) {
      loadModels();
    }
  };

  const handleBlur = () => {
    if (blurTimerRef.current) {
      window.clearTimeout(blurTimerRef.current);
    }
    blurTimerRef.current = window.setTimeout(() => setOpen(false), 180);
  };

  const keyword = value.trim().toLowerCase();
  let filtered = (models ?? []).filter((m) => !keyword || m.toLowerCase().includes(keyword));
  // 聚焦浏览时当前值是精确选中项，按它过滤只会剩它自己——此时展示全量列表
  if (keyword && filtered.length === 1 && filtered[0].toLowerCase() === keyword) {
    filtered = models ?? [];
  }

  return (
    <div className="relative">
      <Input
        value={value}
        placeholder="选择或输入模型名"
        onChange={(event) => onChange(event.target.value)}
        onFocus={handleFocus}
        onBlur={handleBlur}
        className="h-8 pr-7 font-mono text-xs"
        autoComplete="off"
        spellCheck={false}
      />
      {open ? (
        <div className="absolute left-0 right-0 top-full z-30 mt-1 max-h-60 overflow-y-auto rounded-lg border border-white/10 bg-[#101a2e] p-1 shadow-lg">
          {loading ? <div className="px-2 py-1.5 text-xs text-zinc-500">模型列表加载中...</div> : null}
          {!loading && error ? (
            <div className="px-2 py-1.5 text-xs text-amber-400">{error}，可直接手动输入</div>
          ) : null}
          {!loading && !error && models && filtered.length === 0 ? (
            <div className="px-2 py-1.5 text-xs text-zinc-500">无匹配模型，可直接手动输入</div>
          ) : null}
          {filtered.map((model) => (
            <button
              key={model}
              type="button"
              className={cn(
                "block w-full truncate rounded-md px-2 py-1.5 text-left font-mono text-xs text-zinc-200 transition hover:bg-white/10",
                model === value && "bg-violet-500/20 text-violet-200"
              )}
              onMouseDown={(event) => {
                event.preventDefault();
                onChange(model);
                setOpen(false);
              }}
            >
              {model}
            </button>
          ))}
          {!loading && !error && models && filtered.length > 0 ? (
            <div className="px-2 pb-0.5 pt-1 text-[10px] text-zinc-600">
              共 {models.length} 个模型，输入可过滤
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

// ==================== AI 模型服务（ai 命名空间） ====================

const ENDPOINT_TYPES = ["chat", "embedding", "rerank"] as const;

function ProvidersCard({
  providers,
  onChange
}: {
  providers: Record<string, AiProviderConfig>;
  onChange: (next: Record<string, AiProviderConfig>) => void;
}) {
  const names = Object.keys(providers);
  const [newName, setNewName] = useState("");

  const updateProvider = (name: string, patch: Partial<AiProviderConfig>) => {
    onChange({ ...providers, [name]: { ...providers[name], ...patch } });
  };

  const renameProvider = (oldName: string, nextName: string) => {
    const trimmed = nextName.trim();
    if (!trimmed || trimmed === oldName || names.includes(trimmed)) {
      return;
    }
    const next: Record<string, AiProviderConfig> = {};
    names.forEach((key) => {
      next[key === oldName ? trimmed : key] = providers[key];
    });
    onChange(next);
  };

  const addProvider = () => {
    const trimmed = newName.trim();
    if (!trimmed) {
      toast.error("请输入供应商名称（如 openai、deepseek）");
      return;
    }
    if (names.includes(trimmed)) {
      toast.error("供应商已存在");
      return;
    }
    onChange({
      ...providers,
      [trimmed]: { url: "", apiKey: "", endpoints: { chat: "/v1/chat/completions" } }
    });
    setNewName("");
  };

  return (
    <SubCard
      title="模型供应商"
      hint="OpenAI 兼容端点；供应商名需匹配内置客户端类型：bailian / siliconflow / aihubmix / ollama"
      actions={
        <div className="flex items-center gap-1.5">
          <Input
            value={newName}
            placeholder="新供应商名称"
            onChange={(event) => setNewName(event.target.value)}
            onKeyDown={(event) => event.key === "Enter" && addProvider()}
            className="h-8 w-40 text-xs"
          />
          <Button variant="outline" size="sm" onClick={addProvider}>
            <Plus className="mr-1 h-3.5 w-3.5" />
            添加
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        {names.map((name) => {
          const provider = providers[name];
          const endpoints = { ...provider.endpoints };
          return (
            <div key={name} className="rounded-xl border border-white/10 bg-white/[0.03] p-3">
              <div className="flex flex-wrap items-start gap-3">
                <div className="w-36">
                  <Field label="名称">
                    <Input
                      key={name}
                      defaultValue={name}
                      className="h-8 font-mono text-xs"
                      onBlur={(event) => renameProvider(name, event.target.value)}
                    />
                  </Field>
                </div>
                <div className="min-w-[220px] flex-1">
                  <Field label="Base URL">
                    <Input
                      value={provider.url}
                      placeholder="https://api.example.com"
                      onChange={(event) => updateProvider(name, { url: event.target.value })}
                      className="h-8 font-mono text-xs"
                    />
                  </Field>
                </div>
                <div className="min-w-[220px] flex-1">
                  <Field label="API Key">
                    <SecretInput
                      value={provider.apiKey}
                      onChange={(next) => updateProvider(name, { apiKey: next })}
                    />
                  </Field>
                </div>
                <div className="pt-6">
                  <ProviderTestButton
                    provider={name}
                    apiKey={provider.apiKey}
                    url={provider.url}
                    chatEndpoint={endpoints.chat}
                  />
                </div>
                <div className="pt-6">
                  <RowRemoveButton
                    onClick={() => {
                      const next = { ...providers };
                      delete next[name];
                      onChange(next);
                    }}
                  />
                </div>
              </div>
              <div className="mt-3 flex flex-wrap gap-3">
                {ENDPOINT_TYPES.map((type) => (
                  <div key={type} className="w-56">
                    <Field label={`${type} endpoint`}>
                      <Input
                        value={endpoints[type] ?? ""}
                        placeholder={
                          type === "chat"
                            ? "/v1/chat/completions"
                            : type === "embedding"
                              ? "/v1/embeddings"
                              : "未使用"
                        }
                        onChange={(event) =>
                          updateProvider(name, {
                            endpoints: { ...endpoints, [type]: event.target.value }
                          })
                        }
                        className="h-8 font-mono text-xs"
                      />
                    </Field>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
        {names.length === 0 ? (
          <p className="py-6 text-center text-sm text-zinc-500">尚未配置供应商，请先添加</p>
        ) : null}
      </div>
    </SubCard>
  );
}

/** 模型候选编辑表：id / provider / model + 可选列 */
function CandidatesTable({
  group,
  providerNames,
  providers,
  capability,
  capabilities,
  onChange,
  showDimension,
  showPriority,
  showThinking,
  showEnabled,
  allowNoop
}: {
  group: EditableModelGroup;
  providerNames: string[];
  providers: Record<string, AiProviderConfig>;
  /** 本表所属的模型能力，provider 下拉据此过滤 */
  capability: "chat" | "embedding" | "rerank" | "vlm";
  capabilities?: Record<string, string[]> | null;
  onChange: (next: EditableModelGroup) => void;
  showDimension?: boolean;
  showPriority?: boolean;
  showThinking?: boolean;
  showEnabled?: boolean;
  /** rerank 允许无真实供应商的 noop 空实现 */
  allowNoop?: boolean;
}) {
  const candidates = group.candidates ?? [];
  const [newId, setNewId] = useState("");

  // 按能力过滤供应商：容器里没有对应能力客户端的供应商不出现在下拉（当前值保留展示，保存时后端兜底拦截）
  const allowedProviders = useMemo(() => {
    const supported = capabilities?.[capability];
    const base = supported ? providerNames.filter((n) => supported.includes(n)) : providerNames;
    return Array.from(new Set([...base, ...candidates.map((c) => c.provider)]));
  }, [providerNames, capabilities, capability, candidates]);

  const patchCandidate = (index: number, patch: Partial<EditableModelCandidate>) => {
    onChange({
      ...group,
      candidates: candidates.map((c, i) => (i === index ? { ...c, ...patch } : c))
    });
  };

  // id 同时是档位引用键：改名同步更新档位候选与默认模型
  const renameCandidate = (index: number, nextId: string) => {
    const oldId = candidates[index].id;
    const trimmed = nextId.trim();
    if (!trimmed || trimmed === oldId) {
      return;
    }
    if (candidates.some((c, i) => i !== index && c.id === trimmed)) {
      toast.error("候选 id 已存在");
      return;
    }
    const tiers = Object.fromEntries(
      Object.entries(group.tiers ?? {}).map(([tierName, tier]) => [
        tierName,
        { ...tier, candidates: tier.candidates.map((id) => (id === oldId ? trimmed : id)) }
      ])
    );
    onChange({
      ...group,
      candidates: candidates.map((c, i) => (i === index ? { ...c, id: trimmed } : c)),
      defaultModel: group.defaultModel === oldId ? trimmed : group.defaultModel,
      tiers
    });
  };

  const addCandidate = () => {
    const trimmed = newId.trim();
    if (!trimmed) {
      toast.error("请输入候选 id");
      return;
    }
    if (candidates.some((c) => c.id === trimmed)) {
      toast.error("候选 id 已存在");
      return;
    }
    onChange({
      ...group,
      candidates: [
        ...candidates,
        { id: trimmed, provider: allowedProviders[0] ?? providerNames[0] ?? "", model: "", enabled: true, priority: 1 }
      ]
    });
    setNewId("");
  };

  return (
    <div>
      <div className="space-y-2">
        <div className="grid grid-cols-[minmax(110px,1fr)_minmax(110px,1fr)_minmax(150px,1.4fr)_auto_auto] items-center gap-2 px-1.5 text-xs text-zinc-500">
          <span>ID</span>
          <span>Provider</span>
          <span>Model</span>
          <span />
          <span />
        </div>
        {candidates.map((candidate, index) => (
          <div
            key={index}
            className="grid grid-cols-[minmax(110px,1fr)_minmax(110px,1fr)_minmax(150px,1.4fr)_auto_auto] items-center gap-2 rounded-lg border border-white/5 bg-white/[0.02] p-1.5"
          >
            <Input
              key={`${candidate.id}-id`}
              defaultValue={candidate.id}
              className="h-8 font-mono text-xs"
              onBlur={(event) => renameCandidate(index, event.target.value)}
            />
            <Select value={candidate.provider} onValueChange={(next) => patchCandidate(index, { provider: next })}>
              <SelectTrigger className="h-8 text-xs">
                <SelectValue placeholder="provider" />
              </SelectTrigger>
              <SelectContent>
                {allowedProviders.map((name) => (
                  <SelectItem key={name} value={name}>
                    {name}
                  </SelectItem>
                ))}
                {allowNoop && !allowedProviders.includes("noop") ? (
                  <SelectItem value="noop">noop（不精排）</SelectItem>
                ) : null}
              </SelectContent>
            </Select>
            <ModelCombobox
              value={candidate.model}
              onChange={(next) => patchCandidate(index, { model: next })}
              provider={candidate.provider}
              providers={providers}
            />
            <div className="flex items-center gap-3">
              {showDimension ? (
                <div className="flex items-center gap-1.5">
                  <span className="text-xs text-zinc-500">维度</span>
                  <NumInput
                    value={candidate.dimension ?? 0}
                    min={1}
                    onChange={(next) => patchCandidate(index, { dimension: next })}
                  />
                </div>
              ) : null}
              {showPriority ? (
                <div className="flex items-center gap-1.5">
                  <span className="text-xs text-zinc-500">优先级</span>
                  <NumInput
                    value={candidate.priority ?? 100}
                    onChange={(next) => patchCandidate(index, { priority: next })}
                  />
                </div>
              ) : null}
              {showThinking ? (
                <label className="flex cursor-pointer items-center gap-1.5 text-xs text-zinc-400">
                  <Toggle
                    checked={Boolean(candidate.supportsThinking)}
                    onChange={(next) => patchCandidate(index, { supportsThinking: next })}
                  />
                  思考
                </label>
              ) : null}
              {showEnabled ? (
                <label className="flex cursor-pointer items-center gap-1.5 text-xs text-zinc-400">
                  <Toggle
                    checked={candidate.enabled !== false}
                    onChange={(next) => patchCandidate(index, { enabled: next })}
                  />
                  启用
                </label>
              ) : null}
            </div>
            <RowRemoveButton
              onClick={() => onChange({ ...group, candidates: candidates.filter((_, i) => i !== index) })}
            />
          </div>
        ))}
      </div>
      <div className="mt-2 flex items-center gap-1.5">
        <Input
          value={newId}
          placeholder="新候选 id（如 deepseek-v3）"
          onChange={(event) => setNewId(event.target.value)}
          onKeyDown={(event) => event.key === "Enter" && addCandidate()}
          className="h-8 w-56 font-mono text-xs"
        />
        <Button variant="outline" size="sm" onClick={addCandidate}>
          <Plus className="mr-1 h-3.5 w-3.5" />
          添加候选
        </Button>
      </div>
    </div>
  );
}

/** Chat 档位编辑器：档位候选排序 + 超时 */
function TiersEditor({
  group,
  onChange
}: {
  group: EditableModelGroup;
  onChange: (next: EditableModelGroup) => void;
}) {
  const tiers = group.tiers ?? {};
  const candidateIds = (group.candidates ?? []).map((c) => c.id);

  const patchTier = (
    name: string,
    patch: Partial<{ candidates: string[]; timeoutMs: number | null }>
  ) => {
    onChange({
      ...group,
      tiers: { ...tiers, [name]: { ...tiers[name], ...patch } }
    });
  };

  const addTier = () => {
    let name = "tier";
    let suffix = 1;
    while (tiers[name]) {
      name = `tier-${suffix++}`;
    }
    onChange({ ...group, tiers: { ...tiers, [name]: { candidates: [], timeoutMs: 30000 } } });
  };

  const removeTier = (name: string) => {
    const next = { ...tiers };
    delete next[name];
    onChange({
      ...group,
      tiers: next,
      defaultTier: group.defaultTier === name ? undefined : group.defaultTier,
      deepThinkingTier: group.deepThinkingTier === name ? undefined : group.deepThinkingTier
    });
  };

  return (
    <div className="space-y-2">
      {Object.entries(tiers).map(([name, tier]) => {
        const ordered = tier.candidates ?? [];
        const move = (index: number, delta: number) => {
          const next = [...ordered];
          const target = index + delta;
          if (target < 0 || target >= next.length) {
            return;
          }
          [next[index], next[target]] = [next[target], next[index]];
          patchTier(name, { candidates: next });
        };
        const available = candidateIds.filter((id) => !ordered.includes(id));
        return (
          <div key={name} className="rounded-lg border border-white/5 bg-white/[0.02] p-2.5">
            <div className="flex flex-wrap items-center gap-3">
              <Input
                key={name}
                defaultValue={name}
                className="h-8 w-32 font-mono text-xs"
                onBlur={(event) => {
                  const nextName = event.target.value.trim();
                  if (!nextName || nextName === name || tiers[nextName]) {
                    return;
                  }
                  const nextTiers: Record<string, typeof tier> = {};
                  Object.entries(tiers).forEach(([key, value]) => {
                    nextTiers[key === name ? nextName : key] = value;
                  });
                  onChange({
                    ...group,
                    tiers: nextTiers,
                    defaultTier: group.defaultTier === name ? nextName : group.defaultTier,
                    deepThinkingTier:
                      group.deepThinkingTier === name ? nextName : group.deepThinkingTier
                  });
                }}
              />
              <div className="flex min-w-[280px] flex-1 flex-wrap items-center gap-1.5">
                {ordered.map((id, index) => (
                  <span key={id} className="settings-chip inline-flex items-center gap-1">
                    {id}
                    <button
                      type="button"
                      className="text-zinc-500 hover:text-zinc-200"
                      onClick={() => move(index, -1)}
                      title="上移"
                    >
                      <ChevronUp className="h-3 w-3" />
                    </button>
                    <button
                      type="button"
                      className="text-zinc-500 hover:text-zinc-200"
                      onClick={() => move(index, 1)}
                      title="下移"
                    >
                      <ChevronDown className="h-3 w-3" />
                    </button>
                    <button
                      type="button"
                      className="text-zinc-500 hover:text-rose-400"
                      onClick={() =>
                        patchTier(name, { candidates: ordered.filter((x) => x !== id) })
                      }
                      title="移除"
                    >
                      <X className="h-3 w-3" />
                    </button>
                  </span>
                ))}
                {available.length > 0 ? (
                  <Select
                    value=""
                    onValueChange={(id) => patchTier(name, { candidates: [...ordered, id] })}
                  >
                    <SelectTrigger className="h-7 w-24 border-dashed text-xs text-zinc-400">
                      <SelectValue placeholder="+ 加入" />
                    </SelectTrigger>
                    <SelectContent>
                      {available.map((id) => (
                        <SelectItem key={id} value={id}>
                          {id}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                ) : null}
              </div>
              <div className="flex items-center gap-1.5">
                <span className="text-xs text-zinc-500">超时 ms</span>
                <NumInput
                  value={tier.timeoutMs ?? 0}
                  min={0}
                  step={1000}
                  onChange={(next) => patchTier(name, { timeoutMs: next > 0 ? next : null })}
                />
              </div>
              <RowRemoveButton onClick={() => removeTier(name)} title="删除档位" />
            </div>
          </div>
        );
      })}
      <Button variant="outline" size="sm" onClick={addTier}>
        <Plus className="mr-1 h-3.5 w-3.5" />
        添加档位
      </Button>
    </div>
  );
}

function AiConfigSection({
  draft,
  onChange,
  capabilities,
  overridden,
  updateBy,
  updateTime,
  saving,
  resetting,
  onSave,
  onReset
}: {
  draft: AiConfigPayload;
  onChange: (next: AiConfigPayload) => void;
  capabilities?: Record<string, string[]> | null;
  overridden: boolean;
  updateBy?: string | null;
  updateTime?: string | null;
  saving: boolean;
  resetting: boolean;
  onSave: () => void;
  onReset: () => void;
}) {
  const providerNames = Object.keys(draft.providers ?? {});
  const chat = draft.chat;

  return (
    <NamespaceCard
      icon={Cpu}
      title="AI 模型服务"
      hint="供应商、档位路由与模型候选，保存后热生效"
      namespace="ai"
      overridden={overridden}
      updateBy={updateBy}
      updateTime={updateTime}
      onSave={onSave}
      saving={saving}
      onReset={onReset}
      resetting={resetting}
    >
      <ProvidersCard
        providers={draft.providers ?? {}}
        onChange={(providers) => onChange({ ...draft, providers })}
      />

      <SubCard title="Chat 档位路由" hint="请求按档位从左到右故障转移">
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="默认档位">
            <Select
              value={chat.defaultTier ?? ""}
              onValueChange={(next) => onChange({ ...draft, chat: { ...chat, defaultTier: next } })}
            >
              <SelectTrigger className="h-8 text-xs">
                <SelectValue placeholder="选择档位" />
              </SelectTrigger>
              <SelectContent>
                {Object.keys(chat.tiers ?? {}).map((name) => (
                  <SelectItem key={name} value={name}>
                    {name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
          <Field label="深度思考档位" hint="开启深度思考时路由到该档位">
            <Select
              value={chat.deepThinkingTier ?? ""}
              onValueChange={(next) =>
                onChange({ ...draft, chat: { ...chat, deepThinkingTier: next } })
              }
            >
              <SelectTrigger className="h-8 text-xs">
                <SelectValue placeholder="选择档位" />
              </SelectTrigger>
              <SelectContent>
                {Object.keys(chat.tiers ?? {}).map((name) => (
                  <SelectItem key={name} value={name}>
                    {name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
        </div>
        <div className="mt-4">
          <div className="settings-subhead">
            档位列表
            <span className="settings-subhead-hint">候选顺序即故障转移顺序</span>
          </div>
          <div className="mt-2">
            <TiersEditor group={chat} onChange={(next) => onChange({ ...draft, chat: next })} />
          </div>
        </div>
        <div className="mt-4">
          <div className="settings-subhead">候选注册表</div>
          <div className="mt-2">
            <CandidatesTable
              group={chat}
              providerNames={providerNames}
              capability="chat"
              capabilities={capabilities}
              providers={draft.providers ?? {}}
              onChange={(next) => onChange({ ...draft, chat: next })}
              showThinking
              showEnabled
            />
          </div>
        </div>
      </SubCard>

      <div className="grid items-start gap-4 xl:grid-cols-2">
        <SubCard
          title="Embedding 模型"
          hint={draft.embedding?.defaultModel ? `默认 ${draft.embedding.defaultModel}` : undefined}
        >
          <Field label="默认模型" hint="入库与检索共用">
            <Select
              value={draft.embedding?.defaultModel ?? ""}
              onValueChange={(next) =>
                onChange({ ...draft, embedding: { ...draft.embedding, defaultModel: next } })
              }
            >
              <SelectTrigger className="h-8 text-xs">
                <SelectValue placeholder="选择默认模型" />
              </SelectTrigger>
              <SelectContent>
                {(draft.embedding?.candidates ?? []).map((c) => (
                  <SelectItem key={c.id} value={c.id}>
                    {c.id}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
          <div className="mt-3">
            <CandidatesTable
              group={draft.embedding}
              providerNames={providerNames}
              capability="embedding"
              capabilities={capabilities}
              providers={draft.providers ?? {}}
              onChange={(next) => onChange({ ...draft, embedding: next })}
              showDimension
              showPriority
            />
          </div>
        </SubCard>

        <div className="space-y-4">
          <SubCard
            title="Rerank 模型"
            hint={draft.rerank?.defaultModel ? `默认 ${draft.rerank.defaultModel}` : undefined}
          >
            <Field label="默认模型">
              <Select
                value={draft.rerank?.defaultModel ?? ""}
                onValueChange={(next) =>
                  onChange({ ...draft, rerank: { ...draft.rerank, defaultModel: next } })
                }
              >
                <SelectTrigger className="h-8 text-xs">
                  <SelectValue placeholder="选择默认模型" />
                </SelectTrigger>
                <SelectContent>
                  {(draft.rerank?.candidates ?? []).map((c) => (
                    <SelectItem key={c.id} value={c.id}>
                      {c.id}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <div className="mt-3">
              <CandidatesTable
                group={draft.rerank}
                providerNames={providerNames}
                capability="rerank"
                capabilities={capabilities}
              providers={draft.providers ?? {}}
                onChange={(next) => onChange({ ...draft, rerank: next })}
                showPriority
                allowNoop
              />
            </div>
          </SubCard>

          <SubCard title="视觉模型（VLM）" hint="知识库图片解析">
            <CandidatesTable
              group={draft.vlm ?? { candidates: [] }}
              providerNames={providerNames}
              capability="vlm"
              capabilities={capabilities}
              providers={draft.providers ?? {}}
              onChange={(next) => onChange({ ...draft, vlm: next })}
            />
          </SubCard>
        </div>
      </div>

      <SubCard title="调度与流式">
        <div className="grid gap-3 md:grid-cols-3">
          <Field label="熔断失败阈值" hint="连续失败 N 次熔断">
            <NumInput
              value={draft.selection?.failureThreshold ?? 2}
              min={1}
              onChange={(next) =>
                onChange({ ...draft, selection: { ...draft.selection, failureThreshold: next } })
              }
            />
          </Field>
          <Field label="熔断恢复时长" hint="毫秒">
            <NumInput
              value={draft.selection?.openDurationMs ?? 30000}
              min={1000}
              step={1000}
              onChange={(next) =>
                onChange({ ...draft, selection: { ...draft.selection, openDurationMs: next } })
              }
            />
          </Field>
          <Field label="流式分片大小" hint="字符 / 分片">
            <NumInput
              value={draft.stream?.messageChunkSize ?? 1}
              min={1}
              onChange={(next) =>
                onChange({ ...draft, stream: { ...draft.stream, messageChunkSize: next } })
              }
            />
          </Field>
        </div>
      </SubCard>
    </NamespaceCard>
  );
}

// ==================== Agent 引擎（agent 命名空间） ====================

function AgentConfigSection({
  draft,
  onChange,
  providerNames,
  providers,
  overridden,
  updateBy,
  updateTime,
  saving,
  resetting,
  onSave,
  onReset
}: {
  draft: AgentConfigPayload;
  onChange: (next: AgentConfigPayload) => void;
  providerNames: string[];
  providers: Record<string, AiProviderConfig>;
  overridden: boolean;
  updateBy?: string | null;
  updateTime?: string | null;
  saving: boolean;
  resetting: boolean;
  onSave: () => void;
  onReset: () => void;
}) {
  return (
    <NamespaceCard
      icon={Bot}
      title="Agent 引擎"
      hint="agenthub.engine.type=agent 时生效，保存后热生效"
      namespace="agent"
      overridden={overridden}
      updateBy={updateBy}
      updateTime={updateTime}
      onSave={onSave}
      saving={saving}
      onReset={onReset}
      resetting={resetting}
    >
      <SubCard title="ReAct 主模型与运行参数">
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <Field label="主模型供应商" hint="引用 AI 模型服务的供应商">
            <Select
              value={draft.chat?.provider ?? ""}
              onValueChange={(next) => onChange({ ...draft, chat: { ...draft.chat, provider: next } })}
            >
              <SelectTrigger className="h-8 text-xs">
                <SelectValue placeholder="选择供应商" />
              </SelectTrigger>
              <SelectContent>
                {providerNames.map((name) => (
                  <SelectItem key={name} value={name}>
                    {name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
          <Field label="主模型名称" hint="点击从供应商模型列表选取">
            <ModelCombobox
              value={draft.chat?.model ?? ""}
              onChange={(next) => onChange({ ...draft, chat: { ...draft.chat, model: next } })}
              provider={draft.chat?.provider ?? ""}
              providers={providers}
            />
          </Field>
          <Field label="知识检索条数" hint="knowledge_search 返回条数">
            <NumInput
              value={draft.kbTopK ?? 5}
              min={1}
              max={20}
              onChange={(next) => onChange({ ...draft, kbTopK: next })}
            />
          </Field>
          <Field label="ReAct 迭代上限" hint="超出后熔断收尾">
            <NumInput
              value={draft.maxIters ?? 10}
              min={1}
              max={50}
              onChange={(next) => onChange({ ...draft, maxIters: next })}
            />
          </Field>
          <Field label="单次调用重试次数">
            <NumInput
              value={draft.maxRetries ?? 2}
              min={0}
              max={10}
              onChange={(next) => onChange({ ...draft, maxRetries: next })}
            />
          </Field>
          <Field label="SSE 超时" hint="毫秒">
            <NumInput
              value={draft.sseTimeoutMs ?? 300000}
              min={10000}
              step={10000}
              onChange={(next) => onChange({ ...draft, sseTimeoutMs: next })}
            />
          </Field>
        </div>
      </SubCard>
    </NamespaceCard>
  );
}

// ==================== 检索管线（pipeline 命名空间） ====================

function PipelineConfigSection({
  draft,
  onChange,
  overridden,
  updateBy,
  updateTime,
  saving,
  resetting,
  onSave,
  onReset
}: {
  draft: PipelineConfigPayload;
  onChange: (next: PipelineConfigPayload) => void;
  overridden: boolean;
  updateBy?: string | null;
  updateTime?: string | null;
  saving: boolean;
  resetting: boolean;
  onSave: () => void;
  onReset: () => void;
}) {
  const features = draft.features;
  const search = draft.search;
  const memory = draft.memory;
  const rateLimit = draft.rateLimit;
  const webSearch = search.channels?.webSearch;
  const fusion = search.fusion;

  return (
    <NamespaceCard
      icon={Workflow}
      title="检索管线"
      hint="管线开关、检索漏斗与会话记忆，保存后热生效（限流除外）"
      namespace="pipeline"
      overridden={overridden}
      updateBy={updateBy}
      updateTime={updateTime}
      onSave={onSave}
      saving={saving}
      onReset={onReset}
      resetting={resetting}
    >
      <SubCard title="管线开关">
        <div className="flex flex-wrap gap-6">
          {(
            [
              ["queryRewriteEnabled", "查询改写", "多问题改写提升召回"],
              ["rerankEnabled", "Rerank 精排", "融合后精排候选"],
              ["citationEnabled", "行内引用", "回答行内 [N] 角标，开启增加首字延迟"],
              ["contextEnrichEnabled", "上下文富化", "元数据补全"]
            ] as const
          ).map(([key, label, hint]) => (
            <label key={key} className="flex cursor-pointer items-start gap-2.5">
              <Toggle
                checked={Boolean(features[key])}
                onChange={(next) => onChange({ ...draft, features: { ...features, [key]: next } })}
              />
              <span className="text-sm text-zinc-200">
                {label}
                <span className="block text-xs text-zinc-500">{hint}</span>
              </span>
            </label>
          ))}
        </div>
      </SubCard>

      <SubCard title="检索漏斗" hint="召回 → 融合 → 精排 → 上下文，须单调收窄">
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <Field label="最终条数" hint="default-top-k">
            <NumInput
              value={search.defaultTopK}
              min={1}
              onChange={(next) => onChange({ ...draft, search: { ...search, defaultTopK: next } })}
            />
          </Field>
          <Field label="每通道召回" hint="recall-budget，须 ≥ 最终条数">
            <NumInput
              value={search.recallBudget}
              min={0}
              onChange={(next) => onChange({ ...draft, search: { ...search, recallBudget: next } })}
            />
          </Field>
          <Field label="Rerank 候选池" hint="0 = 不截断">
            <NumInput
              value={fusion.rerankCandidateLimit}
              min={0}
              onChange={(next) =>
                onChange({
                  ...draft,
                  search: { ...search, fusion: { ...fusion, rerankCandidateLimit: next } }
                })
              }
            />
          </Field>
          <Field label="RRF 平滑常数 k">
            <NumInput
              value={fusion.rrfK}
              min={1}
              onChange={(next) => onChange({ ...draft, search: { ...search, fusion: { ...fusion, rrfK: next } } })}
            />
          </Field>
        </div>
        <div className="mt-4 grid gap-3 md:grid-cols-3">
          <Field label="意图分下限" hint="min-intent-score">
            <NumInput
              value={search.scope?.minIntentScore ?? 0.4}
              min={0}
              max={1}
              step={0.05}
              onChange={(next) =>
                onChange({ ...draft, search: { ...search, scope: { ...search.scope, minIntentScore: next } } })
              }
            />
          </Field>
          <Field label="收窄置信阈值" hint="confidence-threshold">
            <NumInput
              value={search.scope?.confidenceThreshold ?? 0.6}
              min={0}
              max={1}
              step={0.05}
              onChange={(next) =>
                onChange({
                  ...draft,
                  search: { ...search, scope: { ...search.scope, confidenceThreshold: next } }
                })
              }
            />
          </Field>
          <Field label="补充路配额" hint="supplement-ratio，0 = 关闭">
            <NumInput
              value={search.scope?.supplementRatio ?? 0.25}
              min={0}
              max={0.95}
              step={0.05}
              onChange={(next) =>
                onChange({
                  ...draft,
                  search: { ...search, scope: { ...search.scope, supplementRatio: next } }
                })
              }
            />
          </Field>
        </div>
      </SubCard>

      <SubCard title="检索通道">
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          {(
            [
              ["vector", "向量检索"],
              ["keyword", "关键词检索"],
              ["graph", "图谱检索"]
            ] as const
          ).map(([key, label]) => (
            <label
              key={key}
              className="flex items-center justify-between gap-2 rounded-lg border border-white/5 bg-white/[0.02] px-3 py-2.5"
            >
              <span className="text-sm text-zinc-200">{label}</span>
              <Toggle
                checked={Boolean(search.channels[key]?.enabled)}
                onChange={(next) =>
                  onChange({
                    ...draft,
                    search: {
                      ...search,
                      channels: { ...search.channels, [key]: { ...search.channels[key], enabled: next } }
                    }
                  })
                }
              />
            </label>
          ))}
          <label className="flex items-center justify-between gap-2 rounded-lg border border-white/5 bg-white/[0.02] px-3 py-2.5">
            <span className="text-sm text-zinc-200">联网检索</span>
            <Toggle
              checked={Boolean(webSearch?.enabled)}
              onChange={(next) =>
                onChange({
                  ...draft,
                  search: {
                    ...search,
                    channels: { ...search.channels, webSearch: { ...webSearch, enabled: next } }
                  }
                })
              }
            />
          </label>
        </div>
        <div className="mt-3 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <Field label="单通道超时" hint="毫秒">
            <NumInput
              value={search.channels?.timeoutMs ?? 15000}
              min={1000}
              step={1000}
              onChange={(next) =>
                onChange({
                  ...draft,
                  search: { ...search, channels: { ...search.channels, timeoutMs: next } }
                })
              }
            />
          </Field>
          {webSearch ? (
            <>
              <Field label="联网检索条数">
                <NumInput
                  value={webSearch.count}
                  min={1}
                  max={20}
                  onChange={(next) =>
                    onChange({
                      ...draft,
                      search: {
                        ...search,
                        channels: { ...search.channels, webSearch: { ...webSearch, count: next } }
                      }
                    })
                  }
                />
              </Field>
              <Field label="联网检索 API Key">
                <SecretInput
                  value={webSearch.apiKey}
                  onChange={(next) =>
                    onChange({
                      ...draft,
                      search: {
                        ...search,
                        channels: { ...search.channels, webSearch: { ...webSearch, apiKey: next } }
                      }
                    })
                  }
                />
              </Field>
              <Field label="联网检索 API 地址">
                <Input
                  value={webSearch.apiUrl}
                  onChange={(event) =>
                    onChange({
                      ...draft,
                      search: {
                        ...search,
                        channels: { ...search.channels, webSearch: { ...webSearch, apiUrl: event.target.value } }
                      }
                    })
                  }
                  className="h-8 font-mono text-xs"
                />
              </Field>
            </>
          ) : null}
        </div>
        <div className="mt-3 grid gap-3 md:grid-cols-4">
          {(
            [
              ["vector", "向量权重"],
              ["keyword", "关键词权重"],
              ["graph", "图谱权重"],
              ["webSearch", "联网权重"]
            ] as const
          ).map(([key, label]) => (
            <Field key={key} label={label} hint="RRF 加权">
              <NumInput
                value={fusion.channelWeights?.[key] ?? 1}
                min={0}
                step={0.1}
                onChange={(next) =>
                  onChange({
                    ...draft,
                    search: {
                      ...search,
                      fusion: { ...fusion, channelWeights: { ...fusion.channelWeights, [key]: next } }
                    }
                  })
                }
              />
            </Field>
          ))}
        </div>
      </SubCard>

      <SubCard title="会话记忆">
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-5">
          <Field label="历史保留轮数">
            <NumInput
              value={memory.historyKeepTurns}
              min={1}
              max={100}
              onChange={(next) => onChange({ ...draft, memory: { ...memory, historyKeepTurns: next } })}
            />
          </Field>
          <Field label="摘要压缩">
            <div className="flex h-8 items-center">
              <Toggle
                checked={Boolean(memory.summaryEnabled)}
                onChange={(next) => onChange({ ...draft, memory: { ...memory, summaryEnabled: next } })}
              />
              <span className="ml-2 text-xs text-zinc-400">{memory.summaryEnabled ? "启用" : "关闭"}</span>
            </div>
          </Field>
          <Field label="摘要起始轮数">
            <NumInput
              value={memory.summaryStartTurns}
              min={1}
              onChange={(next) => onChange({ ...draft, memory: { ...memory, summaryStartTurns: next } })}
            />
          </Field>
          <Field label="摘要长度上限">
            <NumInput
              value={memory.summaryMaxChars}
              min={200}
              max={1000}
              step={50}
              onChange={(next) => onChange({ ...draft, memory: { ...memory, summaryMaxChars: next } })}
            />
          </Field>
          <Field label="会话标题上限">
            <NumInput
              value={memory.titleMaxLength}
              min={10}
              max={100}
              onChange={(next) => onChange({ ...draft, memory: { ...memory, titleMaxLength: next } })}
            />
          </Field>
        </div>
      </SubCard>

      <SubCard title="全局限流" hint="限流器与线程池在启动期构建，此处改动需重启生效">
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-5">
          <Field label="全局限流">
            <div className="flex h-8 items-center">
              <Toggle
                checked={Boolean(rateLimit?.globalEnabled)}
                onChange={(next) => onChange({ ...draft, rateLimit: { ...rateLimit, globalEnabled: next } })}
              />
              <span className="ml-2 text-xs text-zinc-400">
                {rateLimit?.globalEnabled ? "启用" : "关闭"}
              </span>
            </div>
          </Field>
          <Field label="最大并发">
            <NumInput
              value={rateLimit?.globalMaxConcurrent ?? 10}
              min={1}
              onChange={(next) =>
                onChange({ ...draft, rateLimit: { ...rateLimit, globalMaxConcurrent: next } })
              }
            />
          </Field>
          <Field label="最长等待" hint="秒">
            <NumInput
              value={rateLimit?.globalMaxWaitSeconds ?? 15}
              min={1}
              onChange={(next) =>
                onChange({ ...draft, rateLimit: { ...rateLimit, globalMaxWaitSeconds: next } })
              }
            />
          </Field>
          <Field label="租约时长" hint="秒">
            <NumInput
              value={rateLimit?.globalLeaseSeconds ?? 30}
              min={1}
              onChange={(next) =>
                onChange({ ...draft, rateLimit: { ...rateLimit, globalLeaseSeconds: next } })
              }
            />
          </Field>
          <Field label="轮询间隔" hint="毫秒">
            <NumInput
              value={rateLimit?.globalPollIntervalMs ?? 200}
              min={50}
              step={50}
              onChange={(next) =>
                onChange({ ...draft, rateLimit: { ...rateLimit, globalPollIntervalMs: next } })
              }
            />
          </Field>
        </div>
      </SubCard>
    </NamespaceCard>
  );
}

// ==================== 页面主体 ====================

function LoadingSkeleton() {
  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">AI 配置</h1>
          <p className="admin-page-subtitle">加载中...</p>
        </div>
      </div>
      {[0, 1, 2].map((index) => (
        <div key={index} className="mb-4 h-64 animate-pulse rounded-xl border border-white/10 bg-white/[0.06]" />
      ))}
    </div>
  );
}

export function AiConfigPage() {
  const [namespaces, setNamespaces] = useState<ConfigNamespace[]>([]);
  const [ai, setAi] = useState<AiConfigPayload | null>(null);
  const [agent, setAgent] = useState<AgentConfigPayload | null>(null);
  const [pipeline, setPipeline] = useState<PipelineConfigPayload | null>(null);
  const [capabilities, setCapabilities] = useState<Record<string, string[]> | null>(null);
  const [loading, setLoading] = useState(true);
  const [savingNs, setSavingNs] = useState<string | null>(null);
  const [resettingNs, setResettingNs] = useState<string | null>(null);

  const nsMeta = useMemo(() => {
    const map: Record<string, ConfigNamespace> = {};
    namespaces.forEach((ns) => {
      map[ns.key] = ns;
    });
    return map;
  }, [namespaces]);

  const loadNamespaces = async () => {
    const list = await listConfigNamespaces();
    setNamespaces(list ?? []);
  };

  const loadAll = async () => {
    const [nsList, aiPayload, agentPayload, pipelinePayload, caps] = await Promise.all([
      listConfigNamespaces(),
      getConfigPayload<AiConfigPayload>("ai"),
      getConfigPayload<AgentConfigPayload>("agent"),
      getConfigPayload<PipelineConfigPayload>("pipeline"),
      getModelCapabilities().catch(() => null)
    ]);
    setNamespaces(nsList ?? []);
    setAi(aiPayload);
    setAgent(agentPayload);
    setPipeline(pipelinePayload);
    setCapabilities(caps);
  };

  useEffect(() => {
    loadAll()
      .catch((error) => {
        console.error(error);
        toast.error(getErrorMessage(error, "加载 AI 配置失败"));
      })
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async (namespace: string) => {
    const payload = namespace === "ai" ? ai : namespace === "agent" ? agent : pipeline;
    if (!payload) {
      return;
    }
    setSavingNs(namespace);
    try {
      await saveConfigPayload(namespace, payload);
      toast.success("配置已保存并实时生效");
      await loadNamespaces();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setSavingNs(null);
    }
  };

  const handleReset = async (namespace: string) => {
    if (!window.confirm("确认恢复该配置为 yaml 默认值？数据库中的覆盖将被删除。")) {
      return;
    }
    setResettingNs(namespace);
    try {
      await resetConfigPayload(namespace);
      const payload = await getConfigPayload<Record<string, unknown>>(namespace);
      if (namespace === "ai") {
        setAi(payload as unknown as AiConfigPayload);
      } else if (namespace === "agent") {
        setAgent(payload as unknown as AgentConfigPayload);
      } else {
        setPipeline(payload as unknown as PipelineConfigPayload);
      }
      await loadNamespaces();
      toast.success("已恢复为 yaml 默认配置");
    } catch (error) {
      toast.error(getErrorMessage(error, "重置失败"));
    } finally {
      setResettingNs(null);
    }
  };

  if (loading) {
    return <LoadingSkeleton />;
  }

  if (!ai || !agent || !pipeline) {
    return (
      <div className="admin-page">
        <div className="settings-card flex flex-col items-center gap-3 py-12 text-center">
          <AlertCircle className="h-8 w-8 text-zinc-500" />
          <p className="text-sm text-zinc-400">配置加载失败，请检查后端服务是否可用</p>
          <Button variant="outline" size="sm" onClick={() => window.location.reload()}>
            重新加载
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">AI 配置</h1>
          <p className="admin-page-subtitle">
            模型供应商、档位路由、Agent 引擎与检索管线的可视化配置，保存后运行时热生效，无需修改 yaml 重启
          </p>
        </div>
        <div className="admin-page-actions">
          <Button
            variant="outline"
            size="sm"
            onClick={async () => {
              try {
                await loadAll();
                toast.success("已刷新");
              } catch (error) {
                toast.error(getErrorMessage(error, "刷新失败"));
              }
            }}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      <AiConfigSection
        draft={ai}
        onChange={setAi}
        capabilities={capabilities}
        overridden={Boolean(nsMeta.ai?.overridden)}
        updateBy={nsMeta.ai?.updateBy}
        updateTime={nsMeta.ai?.updateTime}
        saving={savingNs === "ai"}
        resetting={resettingNs === "ai"}
        onSave={() => handleSave("ai")}
        onReset={() => handleReset("ai")}
      />

      <AgentConfigSection
        draft={agent}
        onChange={setAgent}
        providerNames={Object.keys(ai.providers ?? {})}
        providers={ai.providers ?? {}}
        overridden={Boolean(nsMeta.agent?.overridden)}
        updateBy={nsMeta.agent?.updateBy}
        updateTime={nsMeta.agent?.updateTime}
        saving={savingNs === "agent"}
        resetting={resettingNs === "agent"}
        onSave={() => handleSave("agent")}
        onReset={() => handleReset("agent")}
      />

      <PipelineConfigSection
        draft={pipeline}
        onChange={setPipeline}
        overridden={Boolean(nsMeta.pipeline?.overridden)}
        updateBy={nsMeta.pipeline?.updateBy}
        updateTime={nsMeta.pipeline?.updateTime}
        saving={savingNs === "pipeline"}
        resetting={resettingNs === "pipeline"}
        onSave={() => handleSave("pipeline")}
        onReset={() => handleReset("pipeline")}
      />
    </div>
  );
}
