#!/usr/bin/env python3
"""
上下文质量评估集运行器（REQ-AGENT-0023）。

一键跑一组真实问题，度量：
  - 工具选择是否正确（调用了预期工具 / 没调用禁用工具）
  - 回答是否包含预期事实
  - 延迟、上下文 token、缓存命中率
  - 是否还在无条件注入长期记忆（应为 0）

依赖：只跑在**本机**、对着一个可丢弃的 agent 实例（建议单独端口 + 独立
AGENT_MEMORY_ROOT）；会真实调用模型并产生费用。

用法示例：
  python3 tools/context-eval/run_eval.py \
    --agent-url http://127.0.0.1:8083 \
    --server-url http://127.0.0.1:8080 \
    --username 13800001005 --password 123456 \
    --log /tmp/agent_eval.log \
    --memory-root ./drone-agent/data/agent-memory-eval --memory-user 5 \
    --out /tmp/context-eval-report.json
"""
import argparse
import fnmatch
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request

LOG_START = re.compile(r"【工具编排】开始：traceId=(\w+)，userId=\d+，conversationId=([^，]+)")
LOG_TOOL = re.compile(r"要调用的工具是「([^」]+)」.*?traceId=(\w+)")
LOG_BILL = re.compile(
    r"【上下文账单】traceId=(\w+).*?前缀指纹=(\w+).*?prompt_tokens=(\d+)，缓存命中=(\d+)")
LOG_BILL_NO_USAGE = re.compile(r"【上下文账单】traceId=(\w+).*?前缀指纹=(\w+).*?实际值不可用")


def http_json(url, payload, token=None, timeout=60):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def login(server_url, username, password):
    result = http_json(server_url + "/user/login", {"username": username, "password": password})
    if result.get("state") not in (0, 200) or not result.get("data"):
        raise SystemExit("登录失败：%s" % result)
    return result["data"]["token"]


def seed_memory(root, user_id):
    """放一段可检索的归档，供记忆检索用例使用；返回创建的文件路径与目录是否新建"""
    directory = os.path.join(root, str(user_id))
    created_dir = not os.path.isdir(directory)
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, "memory-eval.md")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("## conv=eval-memory | 2026-01-01 09:00 | 2 条\n")
        handle.write("user: 任务 TK-2026-1001 的负责人是张伟\n")
        handle.write("assistant: 已记录\n\n")
    return path, directory, created_dir


def parse_log(path):
    """按 traceId 汇总工具调用、token 与指纹；同时建立 conversationId → traceId 映射"""
    trace_by_conversation = {}
    by_trace = {}

    def bucket(trace_id):
        return by_trace.setdefault(trace_id, {
            "tools": [], "prompts": [], "hits": [], "fingerprints": set(), "long_term": False
        })

    with open(path, encoding="utf-8", errors="ignore") as handle:
        for line in handle:
            start = LOG_START.search(line)
            if start:
                trace_by_conversation[start.group(2).strip()] = start.group(1)

            tool = LOG_TOOL.search(line)
            if tool:
                bucket(tool.group(2))["tools"].append(tool.group(1))

            bill = LOG_BILL.search(line)
            if bill:
                entry = bucket(bill.group(1))
                entry["fingerprints"].add(bill.group(2))
                entry["prompts"].append(int(bill.group(3)))
                entry["hits"].append(int(bill.group(4)))
                entry["long_term"] = entry["long_term"] or ("长期记忆" in line)
                continue

            no_usage = LOG_BILL_NO_USAGE.search(line)
            if no_usage:
                bucket(no_usage.group(1))["fingerprints"].add(no_usage.group(2))

    return trace_by_conversation, by_trace


def matches_any(tool, patterns):
    return any(fnmatch.fnmatchcase(tool, pattern) for pattern in patterns)


def evaluate_case(case, reply, tools, entry, log_available):
    problems = []
    checks_evaluated = 0

    expect_tools = case.get("expect_tools_any") or []
    if expect_tools or case.get("expect_no_tools"):
        checks_evaluated += 1
        if not log_available or entry is None:
            problems.append("未取到工具日志")
        elif case.get("expect_no_tools"):
            if tools:
                problems.append("不应调用工具，实际调用了 %s" % sorted(set(tools)))
        elif not any(matches_any(tool, expect_tools) for tool in tools):
            problems.append("未调用预期工具 %s，实际 %s" % (expect_tools, sorted(set(tools))))

    forbid = case.get("forbid_tools") or []
    if forbid and log_available and entry is not None:
        hit = [tool for tool in tools if matches_any(tool, forbid)]
        checks_evaluated += 1
        if hit:
            problems.append("调用了禁用工具 %s" % sorted(set(hit)))

    must = case.get("must_contain") or []
    if must:
        checks_evaluated += 1
        missing = [text for text in must if text not in reply]
        if missing:
            problems.append("回答缺少 %s" % missing)

    forbid_text = case.get("must_not_contain") or []
    if forbid_text:
        checks_evaluated += 1
        present = [text for text in forbid_text if text in reply]
        if present:
            problems.append("回答出现禁用内容 %s" % present)

    return checks_evaluated > 0 and not problems, problems, checks_evaluated


def p95(values):
    if not values:
        return 0
    ordered = sorted(values)
    index = max(0, int(round(0.95 * (len(ordered) - 1))))
    return ordered[index]


def main():
    parser = argparse.ArgumentParser(description="上下文质量评估集运行器")
    parser.add_argument("--agent-url", default="http://127.0.0.1:8081")
    parser.add_argument("--server-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="13800001005")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--token", help="直接给 token，跳过登录")
    parser.add_argument("--cases", default=os.path.join(os.path.dirname(__file__), "cases.json"))
    parser.add_argument("--log", help="agent 日志路径；不给则只能评估回答内容")
    parser.add_argument("--memory-root", help="内存根目录；给了才跑需要记忆夹具的用例（请用可丢弃目录）")
    parser.add_argument("--memory-user", type=int, default=5)
    parser.add_argument("--include-heavy", action="store_true", help="包含日报这类重用例")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--out", help="把明细写成 JSON")
    args = parser.parse_args()

    with open(args.cases, encoding="utf-8") as handle:
        cases = json.load(handle)

    token = args.token or login(args.server_url, args.username, args.password)

    seeded = None
    try:
        if args.memory_root:
            seeded = seed_memory(args.memory_root, args.memory_user)

        results = []
        for case in cases:
            if case.get("requires") == "memory" and not args.memory_root:
                results.append({"id": case["id"], "status": "skipped", "reason": "未提供 --memory-root"})
                continue
            if case.get("heavy") and not args.include_heavy:
                results.append({"id": case["id"], "status": "skipped", "reason": "需要 --include-heavy"})
                continue

            conversation = "eval-%s-%d" % (case["id"].lower(), int(time.time() * 1000))
            started = time.time()
            try:
                response = http_json(args.agent_url + "/chat",
                                     {"conversationId": conversation, "message": case["question"]},
                                     token, args.timeout)
                latency_ms = int((time.time() - started) * 1000)
                if response.get("state") not in (0, 200):
                    results.append({
                        "id": case["id"], "status": "failed", "conversation": conversation,
                        "latencyMs": latency_ms,
                        "problems": ["接口返回 state=%s：%s" % (response.get("state"), response.get("message"))]
                    })
                    continue
                reply = (response.get("data") or {}).get("reply") or ""
                results.append({
                    "id": case["id"], "category": case.get("category"), "question": case["question"],
                    "status": "done", "conversation": conversation, "reply": reply,
                    "latencyMs": latency_ms
                })
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                results.append({
                    "id": case["id"], "status": "failed", "conversation": conversation,
                    "problems": ["请求失败：%s" % error]
                })

        trace_by_conversation, by_trace = ({}, {})
        if args.log and os.path.exists(args.log):
            trace_by_conversation, by_trace = parse_log(args.log)

        for result in results:
            if result["status"] != "done":
                continue
            trace = trace_by_conversation.get(result["conversation"])
            entry = by_trace.get(trace) if trace else None
            tools = entry["tools"] if entry else []
            result["tools"] = tools
            if entry:
                result["promptTokensLast"] = entry["prompts"][-1] if entry["prompts"] else None
                result["promptTokensTotal"] = sum(entry["prompts"]) if entry["prompts"] else None
                if entry["prompts"]:
                    result["cacheHitRate"] = round(
                        sum(entry["hits"]) * 100.0 / sum(entry["prompts"]), 1)
                result["fingerprint"] = sorted(entry["fingerprints"])[0] if entry["fingerprints"] else None
                result["longTermInjected"] = entry["long_term"]

            case = next(item for item in cases if item["id"] == result["id"])
            passed, problems, evaluated = evaluate_case(
                case, result["reply"], tools, entry, bool(entry))
            result["status"] = "passed" if passed else "failed"
            result["problems"] = problems
            if evaluated == 0:
                result["status"] = "unchecked"

        done = [item for item in results if item["status"] in ("passed", "failed")]
        passed = [item for item in done if item["status"] == "passed"]
        latencies = [item["latencyMs"] for item in done if "latencyMs" in item]
        context_tokens = [item["promptTokensLast"] for item in done if item.get("promptTokensLast")]
        total_tokens = [item["promptTokensTotal"] for item in done if item.get("promptTokensTotal")]
        cache_rates = [item["cacheHitRate"] for item in done if item.get("cacheHitRate") is not None]
        fingerprints = {item["fingerprint"] for item in done if item.get("fingerprint")}
        long_term = sum(1 for item in done if item.get("longTermInjected"))

        print("\n%-5s %-6s %-8s %-26s %8s %9s %7s" %
              ("ID", "类别", "结果", "工具", "延迟ms", "上下文tok", "命中%"))
        for item in results:
            if item["status"] == "skipped":
                print("%-5s %-6s %-8s %s" % (item["id"], "-", "跳过", item["reason"]))
                continue
            if item["status"] == "failed" and "latencyMs" not in item:
                print("%-5s %-6s %-8s %s" % (item["id"], "-", "失败", "；".join(item.get("problems", []))))
                continue
            print("%-5s %-6s %-8s %-26s %8d %9s %7s" % (
                item["id"], item.get("category", "-"),
                "通过" if item["status"] == "passed" else ("待核" if item["status"] == "unchecked" else "失败"),
                ",".join(sorted(set(item.get("tools", [])))) or "-",
                item.get("latencyMs", 0),
                item.get("promptTokensLast", "-"),
                item.get("cacheHitRate", "-")))
            if item.get("problems"):
                print("      ↳ " + "；".join(item["problems"]))

        print("\n===== 汇总 =====")
        print("执行 %d，通过 %d（%.1f%%）" % (
            len(done), len(passed), (len(passed) * 100.0 / len(done)) if done else 0))
        if latencies:
            print("延迟：平均 %d ms，P95 %d ms" %
                  (statistics.mean(latencies), p95(latencies)))
        if context_tokens:
            print("最终上下文：平均 %d token" % statistics.mean(context_tokens))
        if total_tokens:
            print("累计 prompt：平均 %d token/用例" % statistics.mean(total_tokens))
        if cache_rates:
            print("缓存命中率：平均 %.1f%%" % statistics.mean(cache_rates))
        print("前缀指纹：%d 个 %s" % (len(fingerprints), sorted(fingerprints)))
        print("无条件注入长期记忆：%d 次（预期 0）" % long_term)

        if args.out:
            with open(args.out, "w", encoding="utf-8") as handle:
                json.dump({"summary": {
                    "executed": len(done), "passed": len(passed),
                    "avgLatencyMs": round(statistics.mean(latencies)) if latencies else None,
                    "p95LatencyMs": p95(latencies) if latencies else None,
                    "avgContextTokens": round(statistics.mean(context_tokens)) if context_tokens else None,
                    "avgCacheHitRate": round(statistics.mean(cache_rates), 1) if cache_rates else None,
                    "fingerprints": sorted(fingerprints),
                    "longTermInjectionCount": long_term
                }, "cases": results}, handle, ensure_ascii=False, indent=2)
            print("明细已写入 %s" % args.out)

        if done and len(passed) < len(done):
            sys.exit(1)
    finally:
        if seeded:
            path, directory, created_dir = seeded
            try:
                os.remove(path)
                if created_dir and not os.listdir(directory):
                    os.rmdir(directory)
            except OSError:
                pass


if __name__ == "__main__":
    main()
