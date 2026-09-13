import React from "react";
import ReactDOM from "react-dom/client";

import App from "@/App";
import { useAuthStore } from "@/stores/authStore";
import { useThemeStore } from "@/stores/themeStore";
import "@/styles/globals.css";

useThemeStore.getState().initialize();
useAuthStore.getState().checkAuth();

// 窗口重新聚焦时静默刷新当前用户信息（头像/昵称等改动切回页面即生效，无需重新登录）
// SPA 内导航不重载页面，store 里的用户信息会停留在登录时刻，这里兜底拉取 /user/me
window.addEventListener("focus", () => {
  if (useAuthStore.getState().isAuthenticated) {
    useAuthStore.getState().fetchCurrentUser();
  }
});

let scrollIdleTimer: ReturnType<typeof setTimeout> | null = null;
const handleScrollActivity = () => {
  document.documentElement.classList.add("is-scrolling");
  if (scrollIdleTimer) clearTimeout(scrollIdleTimer);
  scrollIdleTimer = setTimeout(() => {
    document.documentElement.classList.remove("is-scrolling");
  }, 800);
};
window.addEventListener("scroll", handleScrollActivity, { capture: true, passive: true });
window.addEventListener("wheel", handleScrollActivity, { capture: true, passive: true });
window.addEventListener("touchmove", handleScrollActivity, { capture: true, passive: true });

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
