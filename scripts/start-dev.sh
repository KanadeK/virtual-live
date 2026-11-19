#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"
case "${1:-}" in
 --background) exec node "$PROJECT_DIR/scripts/dev-service.mjs" start ;;
 --status) exec node "$PROJECT_DIR/scripts/dev-service.mjs" status ;;
 --stop) exec node "$PROJECT_DIR/scripts/dev-service.mjs" stop ;;
 "") ;;
 *) echo "用法：$0 [--background|--status|--stop]"; exit 1 ;;
esac
if [[ -f .env ]]; then set -a; source .env; set +a; fi
for tool in java mvn node npm; do command -v "$tool" >/dev/null || { echo "缺少 $tool，请先安装 Java 21+ / Maven / Node 20.19+。"; exit 1; }; done
JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 21 ]]; then echo "本项目需要 Java 21+。请切换 JAVA_HOME 后重试。"; exit 1; fi
SERVER_PORT="${SERVER_PORT:-8080}"
for port in "$SERVER_PORT" 5173; do
 if lsof -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1; then echo "端口 $port 已被占用。若 Demo 已运行，请直接访问 http://localhost:5173/control。"; exit 1; fi
done
if [[ ! -d frontend/node_modules ]]; then (cd frontend && npm ci); fi
if [[ ! -d collector/node_modules ]]; then (cd collector && npm ci); fi
(cd collector && npm run build)
(cd backend && mvn -q -DskipTests package)
mkdir -p .runtime
BACKEND_PID=""; FRONTEND_PID=""
cleanup(){ trap - EXIT INT TERM; [[ -z "$FRONTEND_PID" ]] || kill "$FRONTEND_PID" 2>/dev/null || true; [[ -z "$BACKEND_PID" ]] || kill "$BACKEND_PID" 2>/dev/null || true; wait 2>/dev/null || true; }
trap cleanup EXIT INT TERM
(cd backend && exec java -jar target/douyinVirtualLive-1.0.0.jar) >.runtime/backend.log 2>&1 & BACKEND_PID=$!
(cd frontend && exec node node_modules/vite/bin/vite.js --host 127.0.0.1) >.runtime/frontend.log 2>&1 & FRONTEND_PID=$!
READY=false
for attempt in {1..90}; do
 if ! kill -0 "$BACKEND_PID" 2>/dev/null || ! kill -0 "$FRONTEND_PID" 2>/dev/null; then echo "启动失败，请查看 .runtime/backend.log 和 .runtime/frontend.log"; exit 1; fi
 if curl -fsS "http://127.0.0.1:$SERVER_PORT/api/health" >/dev/null 2>&1 && curl -fsS http://127.0.0.1:5173/ >/dev/null 2>&1; then READY=true; break; fi
 sleep 1
done
if [[ "$READY" != true ]]; then echo "启动超时，请查看 .runtime/ 下日志。"; exit 1; fi
printf '\n小助手 AI 直播间已启动\n控制台  http://localhost:5173/control\n直播页  http://localhost:5173/live\n日志    .runtime/backend.log / .runtime/frontend.log\n按 Ctrl+C 停止两端服务。\n\n'
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do sleep 2; done
