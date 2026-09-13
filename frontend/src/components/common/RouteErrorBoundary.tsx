import { AlertTriangle, Home, RefreshCw } from "lucide-react";
import { useRouteError, isRouteErrorResponse, Link } from "react-router-dom";

import { Button } from "@/components/ui/button";

/**
 * 路由级错误边界：任何页面组件抛错时兜底渲染本页，
 * 只降级当前路由而不白屏整个应用（挂载于 router 各顶层路由的 errorElement）
 */
export function RouteErrorBoundary() {
  const error = useRouteError();
  const message = isRouteErrorResponse(error)
    ? `${error.status} ${error.statusText}`
    : error instanceof Error
      ? error.message
      : "发生了未知错误";

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#070b14] p-6">
      <div className="w-full max-w-lg space-y-5 rounded-2xl border border-white/10 bg-[#101a2e] p-8 text-center shadow-soft">
        <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-amber-500/15">
          <AlertTriangle className="h-7 w-7 text-amber-400" />
        </div>
        <div className="space-y-2">
          <h1 className="text-lg font-semibold text-zinc-100">页面出错了</h1>
          <p className="text-sm text-zinc-400">页面渲染时发生异常，可以尝试刷新或返回首页；问题持续出现请联系管理员。</p>
          <pre className="mt-3 max-h-32 overflow-auto whitespace-pre-wrap rounded-lg border border-white/10 bg-black/30 p-3 text-left text-xs text-zinc-500">
            {message}
          </pre>
        </div>
        <div className="flex items-center justify-center gap-3">
          <Button variant="outline" size="sm" onClick={() => window.location.reload()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新页面
          </Button>
          <Button size="sm" asChild>
            <Link to="/chat">
              <Home className="mr-1.5 h-3.5 w-3.5" />
              返回首页
            </Link>
          </Button>
        </div>
      </div>
    </div>
  );
}
