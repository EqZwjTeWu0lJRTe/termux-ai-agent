#!/data/data/com.termux/files/usr/bin/python3
"""
Termux AI Agent - 核心脚本
由 App 通过 Intent 调用：python agent.py "<用户输入>"
输出 JSON 供 App 解析展示
"""

import json
import os
import subprocess
import sys
import requests

CONFIG_PATH = os.path.expanduser("~/.termux_ai_config.json")
DEFAULT_MODEL = "deepseek-chat"
API_URL = "https://api.deepseek.com/v1/chat/completions"

SYSTEM_PROMPT = """你是一个运行在 Android Termux 环境中的 AI 助手。
根据用户需求生成一条可执行的 shell 命令。
只输出 JSON 格式：{"command": "要执行的命令", "need_confirm": false}

规则：
- 对于安全命令（ls, mkdir, touch, cp, mv, pkg, pip 等），need_confirm 为 false
- 对于危险操作（rm -rf, dd, kill, chmod 777 等），need_confirm 为 true
- 文件路径优先使用 ~/storage/ 映射共享存储
- 多步骤任务用 && 连接"""


def get_api_key():
    env_key = os.environ.get("TERMUX_AI_API_KEY")
    if env_key:
        return env_key
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            return json.load(f).get("api_key", "")
    return ""


def call_ai(messages, api_key):
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }
    payload = {
        "model": DEFAULT_MODEL,
        "messages": messages,
        "temperature": 0.1,
        "max_tokens": 1024
    }
    try:
        resp = requests.post(API_URL, headers=headers, json=payload, timeout=30)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]
    except requests.exceptions.ConnectionError:
        return json.dumps({"error": "网络错误", "message": "无法连接到 API 服务器"})
    except requests.exceptions.Timeout:
        return json.dumps({"error": "超时", "message": "API 请求超时，请检查网络"})
    except requests.exceptions.HTTPError as e:
        if resp.status_code == 401:
            return json.dumps({"error": "API Key 无效", "message": "请在 App 设置中检查 API Key"})
        return json.dumps({"error": f"HTTP {resp.status_code}", "message": str(e)})
    except Exception as e:
        return json.dumps({"error": "API 调用失败", "message": str(e)})


def exec_command(command):
    try:
        result = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=60)
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
        result = {
            "error": "未配置 API Key",
            "message": "请在 App 设置中配置 DeepSeek API Key\n或在 Termux 中执行：\necho '{\"api_key\":\"你的key\"}' > ~/.termux_ai_config.json"
        }
        print(json.dumps(result, ensure_ascii=False))
        return

    # 1. AI 生成命令
    command_response = call_ai(
        [{"role": "system", "content": SYSTEM_PROMPT},
         {"role": "user", "content": user_input}],
        api_key
    )

    try:
        cmd_data = json.loads(command_response)
        command = cmd_data.get("command", "").strip()
        need_confirm = cmd_data.get("need_confirm", False)
    except (json.JSONDecodeError, TypeError):
        # AI 没输出 JSON，直接把原文本当摘要返回
        print(json.dumps({
            "command": "",
            "output": "",
            "summary": command_response,
            "exit_code": 0
        }, ensure_ascii=False))
        return

    if not command:
        print(json.dumps({
            "command": "",
            "output": "",
            "summary": "AI 未能生成有效命令",
            "exit_code": 0
        }, ensure_ascii=False))
        return

    # 2. 执行命令
    output, exit_code = exec_command(command)

    # 3. 让 AI 用自然语言总结
    summary = call_ai(
        [{"role": "system", "content": "你是一个 Termux AI 助手，用简洁的中文总结命令执行结果。"},
         {"role": "user", "content": f"命令：{command}\n执行结果（exit code: {exit_code}）：\n{output}\n\n请总结"}],
        api_key
    )

    result = {
        "command": command,
        "output": output,
        "summary": summary,
        "exit_code": exit_code,
        "need_confirm": need_confirm
    }
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
