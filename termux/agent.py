#!/data/data/com.termux/files/usr/bin/python3
"""
Termux AI Agent - 核心脚本
通过 AI API 将自然语言转换为终端命令并执行

使用方式（由 App 通过 Intent 调用）：
    python agent.py "<用户输入>"

API Key 配置方式（优先级从高到低）：
    1. 环境变量 TERMUX_AI_API_KEY
    2. 配置文件 ~/.termux_ai_config.json
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
你的任务是根据用户的需求，生成相应的 Linux 终端命令并在 Termux 中执行。

规则：
1. 分析用户输入，生成最合适的终端命令
2. 只输出纯 JSON，不要包含任何其他文字说明或 markdown 格式
3. JSON 格式：{"command": "要执行的命令", "need_confirm": false}
4. 如果命令涉及危险操作（如 rm -rf、格式化、删除重要文件等），将 need_confirm 设为 true
5. 对于简单的文件操作、查询等安全命令，need_confirm 设为 false
6. 命令应该在 Termux 环境中可执行，注意 Android 的文件路径特点
7. 对于文件操作，优先使用 ~/storage/ 路径（Termux 的共享存储映射）

可用的安全命令示例（无需确认）：
- ls, pwd, echo, cat, mkdir, touch, cp, mv (安全场景)
- pip install, pkg install, apt install
- python, node, git (非破坏性操作)
- find, grep, sort, wc, head, tail
- df, du, free, uname, whoami

需要确认的危险命令示例：
- rm -rf, rm (删除文件)
- dd, mkfs, mount, umount
- chmod 777, chown
- wget/curl 下载并执行
- kill, pkill""";


def get_api_key():
    """获取 API Key，优先级：环境变量 > 配置文件"""
    env_key = os.environ.get("TERMUX_AI_API_KEY")
    if env_key:
        return env_key
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "r") as f:
            config = json.load(f)
            return config.get("api_key", "")
    return ""


def save_api_key(api_key):
    """保存 API Key 到配置文件"""
    config = {}
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "r") as f:
            config = json.load(f)
    config["api_key"] = api_key
    with open(CONFIG_PATH, "w") as f:
        json.dump(config, f, indent=2)
    os.chmod(CONFIG_PATH, 0o600)


def call_ai(messages, api_key):
    """调用 AI API"""
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }
    payload = {
        "model": DEFAULT_MODEL,
        "messages": messages,
        "temperature": 0.1,
        "max_tokens": 4096
    }
    try:
        resp = requests.post(API_URL, headers=headers, json=payload, timeout=30)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]
    except requests.exceptions.ConnectionError:
        return f"错误：无法连接到 API 服务器，请检查网络连接"
    except requests.exceptions.Timeout:
        return f"错误：API 请求超时"
    except requests.exceptions.HTTPError as e:
        if resp.status_code == 401:
            return f"错误：API Key 无效，请在 App 设置中检查 API Key"
        return f"错误：HTTP {resp.status_code} - {resp.text}"
    except (KeyError, json.JSONDecodeError) as e:
        return f"错误：API 响应解析失败 - {e}"
    except Exception as e:
        return f"错误：{e}"


def exec_command(command):
    """执行 shell 命令并返回结果"""
    try:
        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=60
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
        return "错误：命令执行超时（60秒）", -1
    except FileNotFoundError:
        return f"错误：命令未找到 - '{command.split()[0]}' 可能未安装", -1
    except PermissionError:
        return f"错误：权限不足，请检查文件权限", -1
    except Exception as e:
        return f"错误：命令执行失败 - {e}", -1


def generate_command(user_input, api_key, history=None):
    """使用 AI 生成命令"""
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    if history:
        messages.extend(history)
    messages.append({"role": "user", "content": user_input})

    result = call_ai(messages, api_key)
    return result


def summarize_result(command, output, exit_code, api_key):
    """让 AI 总结执行结果"""
    messages = [
        {"role": "system", "content": "你是一个 Termux AI 助手，请用简洁的中文总结命令执行结果。"},
        {"role": "user", "content": f"命令：{command}\n执行结果（exit code: {exit_code}）：\n{output}\n\n请用自然语言总结执行结果，如果出错请指出可能的原因。"}
    ]
    return call_ai(messages, api_key)


def main():
    if len(sys.argv) < 2:
        print("用法: python agent.py \"<用户输入>\"")
        sys.exit(1)

    user_input = sys.argv[1]
    api_key = get_api_key()

    if not api_key:
        result = {
            "error": "未配置 API Key",
            "message": "请在 App 设置中配置 DeepSeek API Key，\n或在 Termux 中执行：\necho '{\"api_key\":\"your-key-here\"}' > ~/.termux_ai_config.json"
        }
        print(json.dumps(result, ensure_ascii=False))
        sys.exit(0)

    command_response = generate_command(user_input, api_key)

    try:
        cmd_data = json.loads(command_response)
        command = cmd_data.get("command", "")
        need_confirm = cmd_data.get("need_confirm", False)
    except (json.JSONDecodeError, TypeError):
        print(json.dumps({
            "command": "",
            "output": "",
            "summary": command_response,
            "exit_code": 0
        }, ensure_ascii=False))
        sys.exit(0)

    output, exit_code = exec_command(command)

    summary = summarize_result(command, output, exit_code, api_key)

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
