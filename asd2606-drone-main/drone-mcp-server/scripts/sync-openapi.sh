#!/usr/bin/env bash
#
# 从运行中的 drone-server 重新生成 OpenAPI 快照。
#
# 什么时候跑：
#   改了 drone-server 的 Controller / DTO 之后，在提交前执行一次，
#   把 src/main/resources/openapi.json 的 diff 一起提交。
#
# 为什么要有快照：
#   drone-mcp-server 启动时不联网，工具目录完全来自这份快照。
#   没有快照就起不来；快照过期则工具参数与实际接口不符。
#
# 用法：
#   ./scripts/sync-openapi.sh                 # 默认 http://localhost:8080
#   DRONE_SERVER_URL=http://10.0.0.5:8080 ./scripts/sync-openapi.sh

set -euo pipefail

BASE="${DRONE_SERVER_URL:-http://localhost:8080}"
TARGET="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/openapi.json"
TMP="$(mktemp)"

echo "拉取 OpenAPI：${BASE}/v3/api-docs"
if ! curl -fsS --max-time 15 "${BASE}/v3/api-docs" -o "${TMP}"; then
  echo "失败：拿不到 OpenAPI。确认 drone-server 已启动、/v3/api-docs 可访问。" >&2
  rm -f "${TMP}"
  exit 1
fi

python3 - "${TMP}" <<'PY'
import json, sys
path = sys.argv[1]
doc = json.load(open(path, encoding="utf-8"))
paths = doc.get("paths") or {}
ops = sum(len(v) for v in paths.values())
print(f"校验通过：paths={len(paths)}，operations={ops}")
if ops == 0:
    raise SystemExit("错误：文档里没有任何 operation")
PY

mv "${TMP}" "${TARGET}"
echo "已更新：${TARGET}"
echo "提示：提交时请带上这个文件的 diff；只改接口不改快照，工具参数会与实际不符。"
