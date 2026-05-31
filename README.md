# Termux AI Agent

通过自然语言与 AI 对话，AI 自动调用 Termux 执行终端命令的 Android 助手 App。

## 系统架构

```
用户输入 → App 聊天界面 → Termux (Python 脚本) → AI API → 执行命令 → 返回结果
```

- **App** (Kotlin)：聊天 UI、消息转发
- **Termux** (Python)：AI Agent 逻辑、命令执行
- **AI API** (DeepSeek/Gemini)：自然语言 → 命令生成

## 用户配置步骤

### 1. 安装 Termux

从 F-Droid 安装 Termux（推荐）：
https://f-droid.org/packages/com.termux/

> 不要从 Google Play 安装，Play 版已停止维护。

### 2. 配置 Termux

**打开「显示在其他应用上层」权限（Android 10+ 必需）：**
系统设置 → 应用 → Termux → 高级 → 打开「显示在其他应用上层」

**安装依赖：**

```bash
pkg update && pkg upgrade -y
pkg install python -y
pip install requests
```

**启用外部应用访问（关键！否则 App 无法与 Termux 通信）：**

```bash
echo "allow-external-apps = true" >> ~/.termux/termux.properties
exit
```

### 3. 部署 Agent 脚本

```bash
# 在 Termux 中创建 agent.py
cat > ~/agent.py << 'SCRIPT_EOF'
# 将 termux/agent.py 的内容粘贴到这里
SCRIPT_EOF
```

或者通过其他方式（adb、HTTP 下载等）将 `termux/agent.py` 放到 Termux 的 `~/agent.py`。

### 4. 配置 App

1. 安装 Termux AI Agent APK
2. 打开 App，点击右上角 ⚙ 进入设置
3. 填入 DeepSeek API Key（或 Gemini API Key）
4. 确认脚本路径（默认：`/data/data/com.termux/files/home/agent.py`）
5. 点击「测试 Termux 连接」，确认绿色成功提示

### 5. 日常使用

在聊天界面输入自然语言指令，例如：

- 「在 Download 文件夹里创建一个 test 文件夹」
- 「查看当前目录有哪些文件」
- 「安装一个 Python 包叫 requests」
- 「帮我查一下手机剩余存储空间」
- 「创建一个名为 notes 的目录，里面放一个 readme.md」

## API Key 获取

### DeepSeek
1. 访问 https://platform.deepseek.com/
2. 注册账号并登录
3. 在 API Keys 页面创建新的 API Key
4. 复制并填入 App 设置

### Gemini (备选)
如需使用 Gemini，需要修改 `agent.py` 中的 API_URL 和请求格式。

## 安全说明

- 对于潜在危险命令（如 `rm -rf`），Agent 会自动标记需确认
- API Key 使用 EncryptedSharedPreferences 加密存储
- 建议在系统设置中关闭 Termux 和本 App 的电池优化，防止后台被杀
- 所有命令执行前会显示给用户，请确认后再执行

## 常见问题

**Q: App 提示「无法启动 Termux 服务」**
A: 请确认：1) Termux 已安装 2) 已配置 `allow-external-apps = true` 3) Termux 已重启

**Q: 命令执行后没有返回结果**
A: 检查 Termux 没有被系统杀后台，在系统设置中关闭 Termux 的电池优化

**Q: API 调用失败**
A: 检查 API Key 是否正确，以及网络连接是否正常

**Q: Python 脚本报错「未找到 requests 模块」**
A: 在 Termux 中执行 `pip install requests`

## 技术栈

- **Android**: Kotlin, XML View, Retrofit/OkHttp (预留)
- **Termux**: Python 3, requests
- **AI API**: DeepSeek Chat API
