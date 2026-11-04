# 远程环境

「设置 → 远程环境」用来接入云端 Linux 机器，让 AI 在手机容器之外跑重活、一次性沙盒或带桌面的远程电脑。当前可用提供商是 **InstaBox**；**E2B** 仅作占位，暂不可用。

## InstaBox

InstaBox 提供一台持久的云端 Debian 虚拟机（默认模板 `agent-browser`，家目录 `/data/home`）。启用并保存 API Key 后，对话里才会出现 `linux_machine` 工具。

### 首次配置

1. 打开 **设置 → 远程环境 → InstaBox**。
2. 打开启用开关，填入 InstaBox API Key（`ibk_…`）。API 地址默认 `https://api.instabox.sh`，模板默认 `agent-browser`，一般不用改。
3. 可选：填写要复用的机器 id（`m_…`）。留空时，AI 会自动创建或复用本应用标记过的机器。
4. 点 **保存**。
5. 切到底部 **Arsenal** 页，点「测试连接」确认 Key 可用，并查看工具能力列表。

Key 经系统密钥库加密后存在应用私有目录，不会出现在对话内容里。

### AI 会怎么用

- 用户明确要求远程电脑、任务对手机/容器过重，或需要立刻出结果的一次性沙盒时，使用 `linux_machine`。
- 同一会话会复用已钉住的机器，不必每次传 `machineId`。
- Build 模式下写操作（创建/执行命令/写文件/桌面控制等）会弹窗确认。
- Plan / Ask 模式只允许只读动作：`info`、`list`、`files_list`、`files_read`、`files_exists`、`list_ports`、`desktop_status`、`metrics`。

关掉开关或清空 Key 后，`linux_machine` 会从本轮工具列表里消失。

### 常见失败

| 提示 | 原因与处理 |
| --- | --- |
| 工具未出现 | 未启用或未保存 Key；到 InstaBox 页检查开关与 Key |
| `DISABLED` / `NO_KEY` | 同上；保存后再发一条消息 |
| 连接测试失败 | Key 无效、网络不通或 API 地址填错；对照 InstaBox 控制台核对 |
