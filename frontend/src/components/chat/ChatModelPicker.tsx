import * as React from "react";
import { Brain, ChevronDown, Cpu, Lightbulb, Zap } from "lucide-react";

import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useChatStore, type ThinkingLevel } from "@/stores/chatStore";
import { cn } from "@/lib/utils";

const THINKING_LEVELS: { value: ThinkingLevel; label: string; hint: string }[] = [
  { value: "fast", label: "快速回答", hint: "低延迟档位，适合简单问题" },
  { value: "balanced", label: "均衡", hint: "默认档位，速度与质量兼顾" },
  { value: "deep", label: "深度思考", hint: "更深入的分析推理，响应较慢" }
];

/**
 * 聊天输入框左下角的模型与思考强度选择器。
 * 模型列表来自 AI 配置的候选注册表；思考强度档位与所选模型能力匹配，
 * 不支持思考的模型禁用"深度思考"并自动回落均衡。
 */
export function ChatModelPicker({ disabled, className }: { disabled?: boolean; className?: string }) {
  const {
    chatModels,
    selectedModelId,
    thinkingLevel,
    setSelectedModel,
    setThinkingLevel,
    loadChatModels
  } = useChatStore();

  // 首次挂载拉一次模型列表（store 内去重）
  React.useEffect(() => {
    loadChatModels();
  }, [loadChatModels]);

  const selectedModel = chatModels.find((m) => m.id === selectedModelId) || null;
  const deepAvailable = !selectedModel || selectedModel.supportsThinking;
  // Radix Select 禁止 SelectItem 用空字符串 value，用哨兵值表示"自动路由"
  const AUTO_VALUE = "__auto__";

  return (
    <div className={cn("flex items-center gap-2", className)}>
      <Select
        value={selectedModelId || AUTO_VALUE}
        onValueChange={(next) => setSelectedModel(next === AUTO_VALUE ? "" : next)}
        disabled={disabled}
      >
        <SelectTrigger
          className="h-8 w-auto min-w-[130px] gap-1.5 rounded-lg border-transparent bg-white/[0.04] px-3 text-xs text-zinc-300 hover:bg-white/[0.08] focus:ring-0"
          aria-label="选择模型"
        >
          <Cpu className="h-3.5 w-3.5 text-zinc-500" />
          <SelectValue placeholder="自动模型" />
          <ChevronDown className="h-3 w-3 text-zinc-500" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={AUTO_VALUE} className="text-xs">
            自动（默认路由）
          </SelectItem>
          {chatModels.map((model) => (
            <SelectItem key={model.id} value={model.id} className="text-xs">
              <span className="inline-flex items-center gap-2">
                {model.id}
                {model.supportsThinking ? (
                  <Brain className="h-3 w-3 text-violet-400" aria-label="支持思考" />
                ) : null}
              </span>
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <Select
        value={thinkingLevel}
        onValueChange={(next) => setThinkingLevel(next as ThinkingLevel)}
        disabled={disabled}
      >
        <SelectTrigger
          className="h-8 w-auto min-w-[110px] gap-1.5 rounded-lg border-transparent bg-white/[0.04] px-3 text-xs text-zinc-300 hover:bg-white/[0.08] focus:ring-0"
          aria-label="思考强度"
        >
          {thinkingLevel === "deep" ? (
            <Brain className="h-3.5 w-3.5 text-violet-400" />
          ) : thinkingLevel === "fast" ? (
            <Zap className="h-3.5 w-3.5 text-amber-400" />
          ) : (
            <Lightbulb className="h-3.5 w-3.5 text-zinc-500" />
          )}
          <SelectValue />
          <ChevronDown className="h-3 w-3 text-zinc-500" />
        </SelectTrigger>
        <SelectContent>
          {THINKING_LEVELS.map((level) => {
            const optionDisabled = level.value === "deep" && !deepAvailable;
            return (
              <SelectItem
                key={level.value}
                value={level.value}
                disabled={optionDisabled}
                className="text-xs"
              >
                <span className="flex flex-col">
                  <span className="inline-flex items-center gap-1.5">
                    {level.label}
                    {optionDisabled ? (
                      <span className="text-[10px] text-zinc-500">当前模型不支持</span>
                    ) : null}
                  </span>
                  <span className="text-[10px] font-normal text-zinc-500">{level.hint}</span>
                </span>
              </SelectItem>
            );
          })}
        </SelectContent>
      </Select>
    </div>
  );
}
