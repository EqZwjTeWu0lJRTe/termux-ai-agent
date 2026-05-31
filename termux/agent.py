#!/data/data/com.termux/files/usr/bin/python3
"""
Termux AI Agent - v1.1
核心能力：状态记忆 + 多步任务规划
"""

import json
import os
import subprocess
import sys
import requests
from datetime import datetime

CONFIG_PATH = os.path.expanduser("~/.termux_ai_config.json")
STATE_PATH = os.path.expanduser("~/.termux_agent_state.json")
DEFAULT_MODEL = "deepseek-chat"
API_URL = "https://api.deepseek.com/v1/chat/completions"
MAX_HISTORY = 10

SYSTEM_PROMPT = """你是 Android Termux 中的 AI 助手，具备状态记忆和多步规划能力。

输出 JSON，支持三种模式：

模式一（闲聊）：{"response": "你的自然语言回答"}

模式二（单步命令）：{"command": "shell 命令", "need_confirm": false}

模式三（多步任务，需要多个命令才能完成时）：{"steps": [
  {"cmd": "第一步的命令", "desc": "步骤说明"},
  {"cmd": "第二步的命令", "desc": "步骤说明"}
], "summary": "任务总览"}

规则：
- 安全命令（ls, mkdir, touch, cp, mv 等）→ need_confirm: false
- 危险操作（rm -rf, dd, kill 等）→ need_confirm: true
- 文件路径优先使用 ~/storage/ 映射共享存储
- **「cd」命令必须单独作为一步，不能与 && 连用。** 正确做法：{"cmd": "cd ~/storage/test", "desc": "进入目录"} 作为一步，后续另起一步执行操作
- 多步任务按依赖顺序排列
- 如果上一步失败，不要重复完全相同的计划，而是给出修正方案
- **如果用户开启了全新的任务（和之前的操作无关），输出时加上 "clear_context": true，系统会清除历史重新开始。**"""


def get_api_key():
    env_key = os.environ.get("TERMUX_AI_API_KEY")
    if env_key:
        return env_key
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            return json.load(f).get("api_key", "")
    return ""


def load_state():
    default = {"cwd": os.path.expanduser("~"), "history": [], "turn": 0}
    if not os.path.exists(STATE_PATH):
        return default
    try:
        with open(STATE_PATH) as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError):
        return default


def save_state(state):
    try:
        with open(STATE_PATH, "w") as f:
            json.dump(state, f, indent=2)
    except OSError:
        pass


def append_history(state, cmd, output, exit_code):
    history = state.setdefault("history", [])
    history.append({
        "cmd": cmd[:120],
        "output": output[:200],
        "exit_code": exit_code,
        "ts": datetime.now().isoformat()
    })
    if len(history) > MAX_HISTORY:
        state["history"] = history[-MAX_HISTORY:]
    state["turn"] = state.get("turn", 0) + 1


def update_cwd(state, command):
    cwd = state.get("cwd", os.path.expanduser("~"))
    cmd = command.strip()
    if cmd.startswith("cd "):
        parts = cmd.split(None, 1)
        if len(parts) == 2:
            dir_part = parts[1]
            for sep in [" && ", " ; ", " | ", "||"]:
                if sep in dir_part:
                    dir_part = dir_part.split(sep)[0]
                    break
            new_dir = os.path.expanduser(dir_part.strip())
            if os.path.isabs(new_dir):
                cwd = new_dir
            else:
                cwd = os.path.normpath(os.path.join(cwd, new_dir))
            state["cwd"] = cwd


def is_new_topic(user_input):
    continuation = ["继续", "然后", "下一步", "结果呢", "然后呢", "接着", "还有"]
    return not any(kw in user_input for kw in continuation)


def build_context(state, user_input):
    lines = [f"当前目录：{state.get('cwd', '~')}"]

    if user_input and is_new_topic(user_input):
        state["history"] = []
        lines.append("（新任务，历史已重置）")
    else:
        history = state.get("history", [])
        if history:
            lines.append("上一轮操作：")
            h = history[-1]
            status = "✓" if h.get("exit_code") == 0 else "✗"
            lines.append(f"  {status} {h['cmd']}")

    lines.append(f"对话轮次：第 {state.get('turn', 0) + 1} 次")
    return "\n".join(lines)


def call_ai(messages, api_key, max_tokens=2048):
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }
    payload = {
        "model": DEFAULT_MODEL,
        "messages": messages,
        "temperature": 0.1,
        "max_tokens": max_tokens
    }
    try:
        resp = requests.post(API_URL, headers=headers, json=payload, timeout=30)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]
    except requests.exceptions.ConnectionError:
        return json.dumps({"error": "网络错误", "message": "无法连接到 API 服务器"})
    except requests.exceptions.Timeout:
        return json.dumps({"error": "超时", "message": "API 请求超时"})
    except requests.exceptions.HTTPError as e:
        if resp.status_code == 401:
            return json.dumps({"error": "API Key 无效", "message": "请在 App 设置中检查"})
        return json.dumps({"error": f"HTTP {resp.status_code}", "message": str(e)})
    except Exception as e:
        return json.dumps({"error": "API 调用失败", "message": str(e)})


def exec_command(command, cwd):
    try:
        result = subprocess.run(
            command, shell=True, capture_output=True, text=True, timeout=60, cwd=cwd
        )
        output = ""
        if result.stdout:
            output += result.stdout
        if result.stderr:
            output += f"\n[STDERR]\n{result.stderr}"
        if not output.strip():
            output = "（命令执行完毕，无输出）"
        return output.strip(), result.returncode
    except subprocess.TimeoutExpired:
        return "命令执行超时（60秒）", -1
    except Exception as e:
        return f"执行失败：{e}", -1


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "参数错误", "message": "用法：python agent.py '用户指令'"}))
        sys.exit(1)

    user_input = sys.argv[1]
    api_key = sys.argv[2] if len(sys.argv) > 2 else get_api_key()

    if not api_key:
        print(json.dumps({
            "error": "未配置 API Key",
            "message": "请在 App 设置中配置 DeepSeek API Key"
        }, ensure_ascii=False))
        return

    state = load_state()
    context = build_context(state, user_input)

    full_prompt = f"{SYSTEM_PROMPT}\n\n上下文：\n{context}"

    raw = call_ai(
        [{"role": "system", "content": full_prompt},
         {"role": "user", "content": user_input}],
        api_key
    )

    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        print(json.dumps({
            "command": "", "output": "", "summary": raw, "exit_code": 0
        }, ensure_ascii=False))
        return

    if data.get("clear_context"):
        state["history"] = []
        state["turn"] = 0
        save_state(state)

    response = data.get("response", "").strip()
    command = data.get("command", "").strip()
    steps = data.get("steps", [])
    need_confirm = data.get("need_confirm", False)

    # 模式一：闲聊
    if response:
        print(json.dumps({
            "command": "", "output": "", "summary": response, "exit_code": 0
        }, ensure_ascii=False))
        return

    # 模式三：多步任务
    if steps:
        cwd = state.get("cwd", os.path.expanduser("~"))
        results = []
        all_ok = True
        n = len(steps)
        for i, step in enumerate(steps):
            cmd = step.get("cmd", "").strip()
            desc = step.get("desc", f"步骤 {i+1}")
            if not cmd:
                continue

            out, code = exec_command(cmd, cwd)
            update_cwd(state, cmd)
            cwd = state.get("cwd", cwd)
            append_history(state, cmd, out, code)
            results.append({
                "cmd": cmd, "desc": desc, "output": out, "exit_code": code
            })

            if code != 0:
                all_ok = False
                decision = call_ai(
                    [{"role": "system", "content": f"步骤「{desc}」执行失败（exit code: {code}）。错误：{out[:300]}。请用 JSON 决定：继续执行后续步骤还是放弃？输出 {{\"action\": \"continue\"}} 或 {{\"action\": \"abort\", \"reason\": \"原因\"}}"}],
                    api_key, max_tokens=256
                )
                try:
                    dec = json.loads(decision)
                    if dec.get("action") == "abort":
                        results.append({
                            "cmd": "", "desc": f"⏹ 已中止：{dec.get('reason', '')}",
                            "output": "", "exit_code": -1
                        })
                        break
                except (json.JSONDecodeError, TypeError):
                    pass

        summary = call_ai(
            [{"role": "system", "content": "用简洁的中文总结刚刚执行的任务结果。"},
             {"role": "user", "content": json.dumps(results, ensure_ascii=False)}],
            api_key, max_tokens=1024
        )

        save_state(state)
        print(json.dumps({
            "command": f"[{n} 步任务]",
            "output": "\n---\n".join(
                f"[{r['desc']}]\n{r.get('output', '')}" for r in results
            ),
            "summary": summary,
            "exit_code": 0 if all_ok else 1,
            "steps": results
        }, ensure_ascii=False))
        return

    # 模式二：单步命令
    if not command:
        print(json.dumps({
            "command": "", "output": "", "summary": "AI 未能生成有效命令", "exit_code": 0
        }, ensure_ascii=False))
        return

    cwd = state.get("cwd", os.path.expanduser("~"))
    out, code = exec_command(command, cwd)
    update_cwd(state, command)
    append_history(state, command, out, code)
    save_state(state)

    summary = call_ai(
        [{"role": "system", "content": "用简洁的中文总结命令执行结果。"},
         {"role": "user", "content": f"命令：{command}\n结果（exit code: {code}）：\n{out}\n\n请总结"}],
        api_key
    )

    print(json.dumps({
        "command": command, "output": out, "summary": summary,
        "exit_code": code, "need_confirm": need_confirm
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
