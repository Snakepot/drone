#!/bin/bash
# 拦截危险 Git 命令：force push / reset --hard / clean -fd / commit --no-verify。
# 输入：Qoder PreToolUse JSON（stdin）；阻止：exit 2 + stderr 原因。
set -u

INPUT=$(cat)

COMMAND=$(printf '%s' "$INPUT" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
ti = data.get("tool_input") or {}
print(ti.get("command") or ti.get("cmd") or "")
' 2>/dev/null)

[ -z "${COMMAND:-}" ] && exit 0

if printf '%s' "$COMMAND" | grep -Eq '(^|[[:space:]])git[[:space:]]+push([[:space:]]|$).*(--force([[:space:]]|$)|[[:space:]]-f([[:space:]]|$))'; then
  echo "已阻止：检测到 git push --force/-f。强制推送可能覆盖远端历史；请确认后手动执行。" >&2
  exit 2
fi
if printf '%s' "$COMMAND" | grep -Eq '(^|[[:space:]])git[[:space:]]+reset[[:space:]]+--hard([[:space:]]|$)'; then
  echo "已阻止：检测到 git reset --hard。它会丢弃未提交改动；请先确认或改用 git stash。" >&2
  exit 2
fi
if printf '%s' "$COMMAND" | grep -Eq '(^|[[:space:]])git[[:space:]]+clean[[:space:]]+-[a-zA-Z]*[fd]'; then
  echo "已阻止：检测到 git clean -f/-d。它会删除未跟踪文件；请先确认或手动清理。" >&2
  exit 2
fi
if printf '%s' "$COMMAND" | grep -Eq '(^|[[:space:]])git[[:space:]]+commit([[:space:]]|$).*--no-verify'; then
  echo "已阻止：检测到 git commit --no-verify。跳过 hook 会绕过仓库校验；请先修复原因。" >&2
  exit 2
fi

exit 0
