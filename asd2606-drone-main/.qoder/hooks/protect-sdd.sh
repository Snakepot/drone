#!/bin/bash
# 保护 SDD 宪法与模板：这两类文件不允许 Agent 修改（AGENTS.md 强制规则）。
# 输入：Qoder PreToolUse JSON（stdin）；阻止：exit 2 + stderr 原因。
set -u

INPUT=$(cat)

FILE=$(printf '%s' "$INPUT" | python3 -c '
import json, os, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
ti = data.get("tool_input") or {}
path = ti.get("file_path") or ti.get("path") or ti.get("notebook_path") or ""
if not path:
    sys.exit(0)
if os.path.isabs(path):
    try:
        path = os.path.relpath(path, os.getcwd())
    except Exception:
        pass
print(os.path.normpath(path).replace("\\", "/"))
' 2>/dev/null)

[ -z "${FILE:-}" ] && exit 0

case "$FILE" in
  docs/sdd/constitution.md|docs/sdd/templates|docs/sdd/templates/*)
    echo "已阻止：$FILE 属于 SDD 宪法/模板，AGENTS.md 规定不得修改。请改为新增需求文档或 ADR。" >&2
    exit 2
    ;;
esac

exit 0
