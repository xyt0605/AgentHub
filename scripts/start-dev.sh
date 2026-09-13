#!/usr/bin/env bash
# =============================================================================
# Agenthub 一键启动脚本
#
# 固化 docs/HANDOVER.md §3/§10 的本地启动知识：
#   WSL 容器健康检查与保活 -> 端口预检 -> (可选打包) -> 后端 -> 前端 -> 探活
#
# 用法（Git Bash）：
#   scripts/start-dev.sh                # 常规启动（已在运行的服务自动跳过）
#   scripts/start-dev.sh --build        # 启动前先 mvnw clean package -DskipTests
#   scripts/start-dev.sh --restart      # 强制重启后端与前端（先杀旧进程）
#   scripts/start-dev.sh --backend-only # 只启动后端
#
# 环境变量：WSL_DISTRO（默认 Ubuntu-20.04）
# =============================================================================
set -u

WSL_DISTRO="${WSL_DISTRO:-Ubuntu-20.04}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/scripts/logs"
JAR="$ROOT_DIR/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
BACKEND_LOG="$LOG_DIR/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend.log"
BUILD=0; RESTART=0; BACKEND_ONLY=0
API_BASE="http://localhost:9090/api/agenthub"

for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    --restart) RESTART=1 ;;
    --backend-only) BACKEND_ONLY=1 ;;
    *) echo "未知参数: $arg（支持 --build / --restart / --backend-only）"; exit 1 ;;
  esac
done

mkdir -p "$LOG_DIR"
cd "$ROOT_DIR"

log()  { echo "[$(date '+%H:%M:%S')] $*"; }
fail() { echo "[$(date '+%H:%M:%S')] [失败] $*"; exit 1; }

port_pid() { # 占用端口的 Windows PID（无则空）
  netstat -ano 2>/dev/null | grep "LISTENING" | grep -E ":$1\s" | awk '{print $5}' | head -1
}

wait_port() { # 等待本机端口监听，$2=最长秒数
  local port="$1" timeout="${2:-120}" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if netstat -ano 2>/dev/null | grep "LISTENING" | grep -qE ":$port\s"; then return 0; fi
    sleep 3; waited=$((waited + 3))
  done
  return 1
}

# ---------- [1/6] WSL 与中间件容器 ----------
log "[1/6] 检查 WSL($WSL_DISTRO) 与中间件容器..."
if ! wsl -d "$WSL_DISTRO" -e bash -c "command -v docker >/dev/null" 2>/dev/null; then
  fail "WSL($WSL_DISTRO) 内无 docker，请先按 HANDOVER §3 安装并启动容器栈"
fi

# WSL 空闲回收会杀容器：保活会话（幂等，重复起无害）
wsl -d "$WSL_DISTRO" -e bash -c "pgrep -f 'sleep infinity' >/dev/null || nohup sleep infinity >/dev/null 2>&1 &" 2>/dev/null

for name in postgres redis rustfs rmqnamesrv; do
  status="$(wsl -d "$WSL_DISTRO" -e bash -c "docker inspect -f '{{.State.Running}}' $name 2>/dev/null" 2>/dev/null | tr -d '\r\n')"
  if [ "$status" != "true" ]; then
    log "  容器 $name 未运行，尝试 docker start..."
    wsl -d "$WSL_DISTRO" -e bash -c "docker start $name" >/dev/null 2>&1 || \
      fail "容器 $name 启动失败（若不存在请按 HANDOVER §3.1 初始化容器栈）"
  else
    log "  容器 $name 正常"
  fi
done

# ---------- [2/6] 端口预检 ----------
log "[2/6] 端口预检..."
if ! wsl -d "$WSL_DISTRO" -e bash -c "docker exec redis redis-cli -a 123456 ping 2>/dev/null | grep -q PONG" 2>/dev/null; then
  fail "Redis 未就绪（密码 123456），请检查容器日志"
fi

for port in 9090 5173; do
  pid="$(port_pid "$port")"
  if [ -n "$pid" ]; then
    if [ "$RESTART" = "1" ]; then
      log "  端口 $port 被 PID $pid 占用（--restart），终止旧进程..."
      taskkill //F //PID "$pid" >/dev/null 2>&1 || fail "无法终止端口 $port 的进程 PID=$pid"
      sleep 2
    else
      log "  端口 $port 已被占用（PID $pid）——视为服务已在运行，跳过该端口的启动"
    fi
  fi
done

# ---------- [3/6] 后端产物 ----------
log "[3/6] 检查后端产物..."
if [ "$BUILD" = "1" ]; then
  pid9090="$(port_pid 9090)"
  if [ -n "$pid9090" ]; then
    log "  打包前先停掉运行中的后端（Windows 下 jar 被锁会导致 repackage 失败，HANDOVER §6.1）..."
    taskkill //F //PID "$pid9090" >/dev/null 2>&1
    sleep 2
  fi
  log "  mvnw clean package -DskipTests（约 1~3 分钟）..."
  ./mvnw -q clean package -DskipTests || fail "后端打包失败"
fi
[ -f "$JAR" ] || fail "未找到 $JAR，请用 --build 先打包（或检查构建产物）"

# ---------- [4/6] 启动后端 ----------
if port_pid 9090 >/dev/null 2>&1 && [ "$RESTART" != "1" ]; then
  code="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 5 -X POST "$API_BASE/auth/login" \
    -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin"}' 2>/dev/null)"
  if [ "$code" = "200" ]; then
    log "[4/6] 后端已在运行（9090 探活 200），跳过启动"
  else
    log "[4/6] 端口 9090 有监听但探活失败（HTTP $code），疑似残留进程，终止后重启..."
    taskkill //F //PID "$(port_pid 9090)" >/dev/null 2>&1
    sleep 2
    log "  启动后端（日志: scripts/logs/backend.log）..."
    (cd "$ROOT_DIR" && nohup java -Dfile.encoding=UTF-8 -jar "$JAR" > "$BACKEND_LOG" 2>&1 &)
    log "  等待 9090 就绪（最长 150 秒）..."
    wait_port 9090 150 || { log "  超时，最近日志："; tail -20 "$BACKEND_LOG"; fail "后端启动超时"; }
  fi
else
  log "[4/6] 启动后端（日志: scripts/logs/backend.log）..."
  (cd "$ROOT_DIR" && nohup java -Dfile.encoding=UTF-8 -jar "$JAR" > "$BACKEND_LOG" 2>&1 &)
  log "  等待 9090 就绪（最长 150 秒）..."
  wait_port 9090 150 || { log "  超时，最近日志："; tail -20 "$BACKEND_LOG"; fail "后端启动超时"; }
fi

# ---------- [5/6] 启动前端 ----------
if [ "$BACKEND_ONLY" = "1" ]; then
  log "[5/6] --backend-only，跳过前端"
else
  if port_pid 5173 >/dev/null 2>&1 && [ "$RESTART" != "1" ]; then
    code="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:5173/ 2>/dev/null)"
    if [ "$code" = "200" ]; then
      log "[5/6] 前端已在运行（5173 探活 200），跳过启动"
    else
      log "[5/6] 端口 5173 有监听但不可访问（HTTP $code），疑似残留进程，终止后重启..."
      taskkill //F //PID "$(port_pid 5173)" >/dev/null 2>&1
      sleep 2
      start_frontend=1
    fi
  else
    start_frontend=1
  fi
  if [ "${start_frontend:-0}" = "1" ]; then
    log "  启动前端（日志: scripts/logs/frontend.log）..."
    # 注意：Git Bash 下 nohup npm 不可靠（npm 是 .cmd shim），用后台 + disown
    (cd "$ROOT_DIR/frontend" && npm run dev > "$FRONTEND_LOG" 2>&1 & disown)
    wait_port 5173 60 || { log "  超时，最近日志："; tail -20 "$FRONTEND_LOG"; fail "前端启动超时"; }
  fi
fi

# ---------- [6/6] 探活 ----------
log "[6/6] 探活..."
sleep 2
code="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 5 "$API_BASE/auth/login" -X POST \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin"}' 2>/dev/null)"
if [ "$code" = "200" ]; then
  log "探活通过（登录接口 200）"
else
  fail "登录接口探活失败（HTTP $code），查看 $BACKEND_LOG"
fi

echo ""
log "================ Agenthub 已就绪 ================"
log "  前端        http://localhost:5173   （admin / admin）"
log "  后端        $API_BASE"
log "  日志        scripts/logs/backend.log / frontend.log"
log "  停止        任务管理器结束 java/node 进程，或 netstat -ano | findstr :9090 后 taskkill /F /PID <pid>"
log "================================================"
