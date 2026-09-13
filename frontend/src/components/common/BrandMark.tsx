import { cn } from "@/lib/utils";

/** Agenthub 品牌标：中枢节点 + 卫星，青→紫渐变 */
export function BrandMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 64 64"
      role="img"
      aria-label="Agenthub"
      className={cn("h-10 w-10", className)}
    >
      <defs>
        <linearGradient id="agenthub-brand-g" x1="0" y1="0" x2="64" y2="64" gradientUnits="userSpaceOnUse">
          <stop stopColor="#22D3EE" />
          <stop offset="0.55" stopColor="#8B5CF6" />
          <stop offset="1" stopColor="#6366F1" />
        </linearGradient>
      </defs>
      <rect x="2" y="2" width="60" height="60" rx="16" fill="#0D1424" />
      <rect
        x="2"
        y="2"
        width="60"
        height="60"
        rx="16"
        fill="none"
        stroke="url(#agenthub-brand-g)"
        strokeOpacity="0.55"
        strokeWidth="2.5"
      />
      <g stroke="url(#agenthub-brand-g)" strokeWidth="3" strokeLinecap="round" opacity="0.85">
        <path d="M32 32 L32 15" />
        <path d="M32 32 L46.5 40.5" />
        <path d="M32 32 L17.5 40.5" />
      </g>
      <circle cx="32" cy="13.5" r="4.4" fill="#22D3EE" />
      <circle cx="48" cy="41" r="4.4" fill="#8B5CF6" />
      <circle cx="16" cy="41" r="4.4" fill="#6366F1" />
      <circle cx="32" cy="32" r="8.2" fill="url(#agenthub-brand-g)" />
      <circle cx="32" cy="32" r="3" fill="#0D1424" />
    </svg>
  );
}
