#!/usr/bin/env python3
"""提交前检查：敏感信息 + Java 未使用 import。

Qoder PreToolUse 在 `git commit` 前调用：
- 敏感信息：命中即 exit 2，阻止提交；
- 未使用 import：在安全前提下自动删除并 `git add`，否则 exit 2。

安全前提：该 Java 文件没有未暂存改动（index == worktree），
避免把用户未暂存的修改一起提交。
"""
import os
import re
import subprocess
import sys
from pathlib import Path

SECRET_PATTERNS = [
    (re.compile(r"(?i)(api[_-]?key|apikey|secret|token|password|passwd|pwd)\s*[:=]\s*[\"']?([A-Za-z0-9_\-./+=]{8,})"),
     "疑似密钥/密码"),
    (re.compile(r"\bsk-[A-Za-z0-9]{16,}\b"), "疑似 API Key（sk-）"),
    (re.compile(r"\bghp_[A-Za-z0-9]{20,}\b"), "疑似 GitHub Token"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "疑似 AWS Access Key"),
    (re.compile(r"-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----"), "疑似私钥"),
    (re.compile(r"\bBearer\s+eyJ[A-Za-z0-9_-]{10,}"), "疑似 Bearer Token"),
]

ALLOW_VALUE = re.compile(
    r"^(?:\$\{.*\}|your[-_].*|x{3,}.*|change[-_]?me.*|.*please[-_]change.*|"
    r"example.*|dummy.*|placeholder.*|test.*|sample.*|none|null|false|\*+)$",
    re.I,
)

SENSITIVE_FILES = (".env", ".env.local", ".env.production", "credentials.json",
                   "id_rsa", "id_ed25519")
SENSITIVE_SUFFIXES = (".pem", ".p12", ".pfx", ".key")

IMPORT_RE = re.compile(r"^import\s+(static\s+)?([\w.]+)\s*;$")


def run(args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        return ""
    return result.stdout


def staged_files():
    out = subprocess.run(["git", "diff", "--cached", "--name-only", "-z",
                          "--diff-filter=ACMR"],
                         capture_output=True, text=True).stdout
    return [f for f in out.split("\0") if f]


def index_content(path):
    return run(["git", "show", f":{path}"])


def added_lines(path):
    """返回 [(行号, 内容)]；-U0 下逐条跟踪新增行。"""
    diff = run(["git", "diff", "--cached", "-U0", "--", path])
    line_no = 0
    for line in diff.splitlines():
        if line.startswith("@@"):
            match = re.search(r"\+(\d+)", line)
            line_no = int(match.group(1)) if match else 0
            continue
        if line.startswith("+++ ") or line.startswith("--- "):
            continue
        if line.startswith("+"):
            yield line_no, line[1:]
            line_no += 1


def check_sensitive(paths):
    findings = []
    for path in paths:
        base = os.path.basename(path).lower()
        if base in SENSITIVE_FILES or base.endswith(SENSITIVE_SUFFIXES):
            findings.append(f"[安全] {path}：疑似凭据文件，禁止提交")
            continue
        for line_no, text in added_lines(path):
            for pattern, label in SECRET_PATTERNS:
                match = pattern.search(text)
                if not match:
                    continue
                value = match.group(2) if match.lastindex and match.lastindex >= 2 else match.group(0)
                if ALLOW_VALUE.match(value or ""):
                    continue
                findings.append(f"[安全] {path}:{line_no} {label}：{text.strip()[:160]}")
                break
    return findings


def unused_import_lines(content):
    """返回 [(行号, 原始行)]；只处理“一行一个 import”。"""
    lines = content.splitlines()
    body = "\n".join(line for line in lines if not line.strip().startswith("import "))
    unused = []
    for index, line in enumerate(lines):
        stripped = line.strip()
        match = IMPORT_RE.match(stripped)
        if not match or stripped.count(";") != 1:
            continue
        target = match.group(2).split(".")[-1]
        if target == "*":
            continue
        if not re.search(r"\b" + re.escape(target) + r"\b", body):
            unused.append((index + 1, line))
    return unused


def has_unstaged_changes(path):
    result = subprocess.run(["git", "diff", "--quiet", "--", path])
    return result.returncode == 1


def fix_unused_imports(paths):
    """返回 (未修复的问题, 已自动删除的 import 数)。"""
    findings = []
    fixed = 0
    for path in paths:
        if not path.endswith(".java"):
            continue
        content = index_content(path)
        if not content:
            continue
        unused = unused_import_lines(content)
        if not unused:
            continue
        if has_unstaged_changes(path):
            findings.append(
                f"[import] {path}：有未暂存改动，无法安全自动修复；"
                f"请先 git add 或手动删除：{', '.join(str(n) for n, _ in unused)}")
            continue
        remove = {line_no for line_no, _ in unused}
        try:
            with open(path, "r", encoding="utf-8", newline="") as handle:
                lines = handle.readlines()
            kept = [line for index, line in enumerate(lines, 1) if index not in remove]
            with open(path, "w", encoding="utf-8", newline="") as handle:
                handle.writelines(kept)
            added = subprocess.run(["git", "add", "--", path],
                                   capture_output=True, text=True)
            if added.returncode != 0:
                findings.append(f"[import] {path}：自动修复后 git add 失败，请手动处理")
                continue
            fixed += len(unused)
            sys.stderr.write(
                f"[import] {path}：已自动移除 {len(unused)} 个未使用 import 并重新 add\n")
        except OSError as error:
            findings.append(f"[import] {path}：自动修复失败：{error}")
    return findings, fixed


def main():
    paths = staged_files()
    if not paths:
        return 0
    findings = check_sensitive(paths)
    import_findings, fixed = fix_unused_imports(paths)
    findings.extend(import_findings)
    if findings:
        sys.stderr.write("提交前检查未通过：\n")
        for item in findings:
            sys.stderr.write("  - " + item + "\n")
        sys.stderr.write("请修复后再提交；密钥/密码不要写入仓库。\n")
        return 2
    if fixed:
        sys.stderr.write(f"提交前检查：已自动移除 {fixed} 个未使用 import，继续提交。\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
