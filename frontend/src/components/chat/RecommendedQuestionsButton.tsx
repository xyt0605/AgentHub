import { ChevronDown, Loader2, Sparkles } from "lucide-react";

import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import type { Message } from "@/types";

interface RecommendedQuestionsButtonProps {
  message: Message;
}

export function RecommendedQuestionsButton({ message }: RecommendedQuestionsButtonProps) {
  const toggleRecommended = useChatStore((state) => state.toggleRecommended);

  const open = Boolean(message.recommendedOpen);
  // 生成中转圈；手动触发即展开，loading 必然可见
  const spinning = message.recommendedState === "loading";

  return (
    <button
      type="button"
      onClick={() => toggleRecommended(message.id)}
      disabled={spinning}
      aria-expanded={open}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full py-1 pl-2.5 pr-2 text-xs transition-colors",
        open
          ? "bg-violet-500/25 text-violet-300"
          : "text-zinc-400 hover:bg-white/10 hover:text-zinc-100",
        spinning && "cursor-wait opacity-80"
      )}
    >
      {spinning ? (
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
      ) : (
        <Sparkles className={cn("h-3.5 w-3.5", open && "text-cyan-300")} />
      )}
      推荐问题
      <ChevronDown
        className={cn("h-3 w-3 transition-transform", open && "rotate-180")}
        aria-hidden="true"
      />
    </button>
  );
}
