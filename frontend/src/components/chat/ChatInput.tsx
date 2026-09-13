import * as React from "react";
import { Lightbulb, Send, Square } from "lucide-react";

import { ChatModelPicker } from "@/components/chat/ChatModelPicker";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";

export function ChatInput() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const {
    sendMessage,
    isStreaming,
    cancelGeneration,
    chatModels,
    selectedModelId,
    thinkingLevel,
    inputFocusKey
  } = useChatStore();

  const selectedModel = chatModels.find((m) => m.id === selectedModelId) || null;

  const focusInput = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.focus({ preventScroll: true });
  }, []);

  const adjustHeight = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    const next = Math.min(el.scrollHeight, 160);
    el.style.height = `${next}px`;
  }, []);

  React.useEffect(() => {
    adjustHeight();
  }, [value, adjustHeight]);

  React.useEffect(() => {
    if (!inputFocusKey) return;
    focusInput();
  }, [inputFocusKey, focusInput]);

  const handleSubmit = async () => {
    if (isStreaming) {
      cancelGeneration();
      focusInput();
      return;
    }
    if (!value.trim()) return;
    const next = value;
    setValue("");
    focusInput();
    await sendMessage(next);
    focusInput();
  };

  const hasContent = value.trim().length > 0;

  return (
    <div className="space-y-4">
      <div
        className={cn(
          "relative flex flex-col rounded-2xl border bg-[#101a2e] px-4 pt-3 pb-2 transition-all duration-200",
          isFocused
            ? "border-white/10 shadow-[0_4px_12px_rgba(0,0,0,0.06)]"
            : "border-white/10 hover:border-white/10"
        )}
      >
        <div className="relative">
          <Textarea
            ref={textareaRef}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            placeholder={thinkingLevel === "deep" ? "输入需要深度分析的问题..." : "输入你的问题..."}
            className="max-h-40 min-h-[44px] w-full resize-none border-0 bg-transparent px-2 pt-2 pb-2 pr-2 text-[15px] text-zinc-100 shadow-none placeholder:text-zinc-400 focus-visible:ring-0"
            rows={1}
            onFocus={() => setIsFocused(true)}
            onBlur={() => setIsFocused(false)}
            onCompositionStart={() => {
              isComposingRef.current = true;
            }}
            onCompositionEnd={() => {
              isComposingRef.current = false;
            }}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                const nativeEvent = event.nativeEvent as KeyboardEvent;
                if (nativeEvent.isComposing || isComposingRef.current || nativeEvent.keyCode === 229) {
                  return;
                }
                event.preventDefault();
                handleSubmit();
              }
            }}
            aria-label="聊天输入框"
          />
          <div className="pointer-events-none absolute bottom-0 left-0 right-0 h-[10px] bg-gradient-to-b from-transparent via-[#131c2e]/40 to-[#131c2e]" />
        </div>
        <div className="relative mt-2 flex items-center gap-2">
          <ChatModelPicker disabled={isStreaming} />
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!hasContent && !isStreaming}
            aria-label={isStreaming ? "停止生成" : "发送消息"}
            className={cn(
              "ml-auto rounded-full p-2.5 transition-all duration-200",
              isStreaming
                ? "bg-red-500/15 text-red-300 hover:bg-red-500/25"
                : hasContent
                  ? "bg-violet-600 text-white hover:bg-violet-500"
                  : "cursor-not-allowed bg-white/[0.04] text-zinc-500"
            )}
          >
            {isStreaming ? <Square className="h-4 w-4" /> : <Send className="h-4 w-4" />}
          </button>
        </div>
      </div>
      {thinkingLevel === "deep" ? (
        <p className="text-xs text-violet-300">
          <span className="inline-flex items-center gap-1.5">
            <Lightbulb className="h-3.5 w-3.5" />
            深度思考模式已开启{selectedModel ? `（${selectedModel.id}）` : ""}，AI将进行更深入的分析推理
          </span>
        </p>
      ) : null}
      <p className="text-center text-xs text-zinc-400">
        <kbd className="rounded bg-white/[0.04] px-1.5 py-0.5 text-zinc-400">Enter</kbd> 发送
        <span className="px-1.5">·</span>
        <kbd className="rounded bg-white/[0.04] px-1.5 py-0.5 text-zinc-400">
          Shift + Enter
        </kbd>{" "}
        换行
        {isStreaming ? <span className="ml-2 animate-pulse-soft">生成中...</span> : null}
      </p>
    </div>
  );
}
