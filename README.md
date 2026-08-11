<div align="center">

<img src="docs/img/banner.png" alt="Skerry — 理应如此的 SSH 客户端。终端 · SFTP · 隧道 · VNC/RDP · 加密保险库 · 无账号、无云。Linux · Windows · macOS · Android" width="820">

**简体中文**

[![CI](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml/badge.svg)](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/SeCherkasov/SkerrySSH)](../../releases/latest)
[![Clients: GPL-3.0](https://img.shields.io/badge/clients-GPL--3.0-blue)](LICENSE)
[![Server: AGPL-3.0](https://img.shields.io/badge/server-AGPL--3.0-blue)](server/LICENSE)

</div>

---

开源 SSH 客户端，单一核心（Kotlin Multiplatform）覆盖所有平台：
**Linux · Windows · macOS · Android**。

- **本地优先** —— 无需账号或外部服务即可完整使用；同步可选且自托管。
- **零知识** —— 保险库采用 Argon2id + XChaCha20-Poly1305 加密；主密码和加密密钥永不离开设备。
- **受策略约束的 AI** —— 模型输出被视为不可信输入：执行命令需要明确确认；本地推理（llama.cpp）杜绝外发流量。

---

## Skerry 与同类产品对比

| | Skerry | Termius | PuTTY | Tabby |
|---|---|---|---|---|
| **许可证** | GPL-3.0 / AGPL-3.0 | 专有 | MIT | MIT |
| **平台** | Linux · Windows · macOS · Android | Linux · Windows · macOS · Android · iOS | Windows · Unix | Linux · Windows · macOS |
| **价格** | 免费 | 从 $10/月 | 免费 | 免费 |
| **无需账号** | ✅ | ⚠️ 仅本地 | ✅ | ✅ |
| **加密保险库** | ✅ | ✅ | ❌ | ⚠️ 可选 |
| **同步** | ✅ 自托管 | ✅ 厂商云 | ❌ | ✅ 自托管 |
| **团队共享** | ✅ | ⚠️ 付费 | ❌ | ❌ |
| **SFTP** | ✅ 双栏 | ✅ | ⚠️ 仅命令行 | ✅ |
| **Mosh** | ✅ | ✅ | ❌ | ❌ |
| **VNC / RDP** | ✅ | ❌ | ❌ | ❌ |
| **会话实时共享** | ✅ | ⚠️ 付费 | ❌ | ❌ |
| **AI 助手** | ✅ 本地或自带 Key | ⚠️ 仅云端 | ❌ | ❌ |

*竞品数据来自各项目官网（2026-07-23）。如有出入，欢迎提交 PR 修正或开 issue。*

---

## 状态

**Linux**、**Windows**、**macOS** 和 **Android** 正在积极开发。

**iOS/iPadOS** 暂缓 —— 缺少用于构建和调试的硬件，项目没有 iOS 目标。

---

## 安装

安装包在 **[最新发布](../../releases/latest)** 中：

| 平台 | 架构 | 文件 |
|---|---|---|
| Linux | x86_64 | `.deb`、`.rpm`、`.AppImage` |
| Linux | arm64 | `.deb`、`.rpm`、`.AppImage` |
| Windows | x64 | `.msi`、`.zip` |
| macOS | Apple Silicon | `.dmg` |
| macOS | Intel | `.dmg` |
| Android | arm64-v8a | `.apk` |

- **签名**：构建产物未签名（没有 Apple 开发者账号）。macOS 首次启动会被 Gatekeeper 拦截 —— 右键应用 → 打开，或在 系统设置 → 隐私与安全性 中允许。Windows `.msi` 同样未签名，首次运行 SmartScreen 会警告。
- **macOS 版本号**：关于本应用 显示 `1.x.y` 而非 `0.x`（打包要求主版本号 ≥ 1），真实版本以"关于"页为准。
- **校验和**：`sha256sum -c --ignore-missing SHA256SUMS.txt`

从源码构建见下文。

---

## 截图

![带主机管理、会话标签页和实时指标面板的终端](docs/screenshots/desktop-terminal.png)

<details>
<summary>更多截图</summary>

![双栏 SFTP 管理器](docs/screenshots/desktop-sftp.png)

![端口转发管理器](docs/screenshots/desktop-tunnels.png)

![保险库：密钥、密码、证书](docs/screenshots/desktop-vault.png)

![带主机级策略的 AI 助手](docs/screenshots/desktop-ai.png)

| 主机列表 | 移动端终端 |
|---|---|
| ![带分组和标签的主机列表](docs/screenshots/mobile-hosts.png) | ![移动端终端](docs/screenshots/mobile-terminal.png) |

</details>

---

## 功能特性

- **协议** —— SSH、Mosh、Telnet、串口（桌面端和 Android USB-OTG），以及无需任何连接的本地 Shell 标签页。
- **SSH** —— 跳板机（ProxyJump）、来自保险库或磁盘的证书、CA 签名的主机密钥证书、keyboard-interactive 双因素认证、自动重连、从 `~/.ssh/config` 导入主机。
- **SFTP** —— 双栏管理器：文件查看器和编辑器、可排序列、名称过滤、传输队列。
- **端口转发** —— 本地、远程、动态/SOCKS；保险库解锁后自动拉起转发；一键转发主机上发现的端口。
- **容器** —— 直接从主机进入 Docker 容器或 Kubernetes Pod 执行命令。
- **远程桌面** —— 为本项目自研的 VNC 和 RDP 客户端栈：截图、Ctrl+Alt+Del、剪贴板互通、会话中实时修改设置。RDP 支持 H.264（有解码器时）：Android 始终可用，桌面端需要 PATH 中的 `ffmpeg`。
- **终端** —— 自研网格仿真，每个标签页最多四个平铺窗格并支持同步输入、回滚搜索、语法高亮、基于历史的命令面板、向多个会话广播输入、从输出中提取文件路径在 SFTP 中打开、会话录制（asciinema v2）并支持应用内回放。
- **主机监控** —— 独立页面：CPU、内存和网络（含历史曲线）、磁盘和交换分区（等级条）、进程排行、systemd 单元、挂载点、容器、设备端阈值告警。
- **会话共享** —— 通过端到端加密通道把终端流共享给队友，可只读或移交键盘控制。
- **生产环境防护** —— 对标记为 `prod` 的主机上的每条命令做风险评分，危险命令需要确认。
- **Runbook（运行手册）** —— 在真实会话中分步执行流程：每步可以是命令或 SFTP 传输、等待确认的暂停点、非零退出码即停止。运行日志记录每步的状态、耗时和输出。
- **代码片段** —— 带类型提示的命令库，`${{…}}` 变量（日期/时间、uuid、随机数、剪贴板、保险库机密、交互式参数）在运行时展开，并先显示确认预览。
- **AI** —— 每台主机一个策略；桌面端会话旁有助手面板，移动端按键呼出输入框；可用你自己的 OpenAI Key 或本地模型。见 [AI 与隐私](#ai-与隐私)。
- **保险库** —— 用 Argon2id + XChaCha20-Poly1305 保护密钥、密码、身份和证书；Android 支持生物识别解锁；机密卡片展示算法、指纹、有效期、关联依赖和最后使用时间；30 天回收站可在所有已同步设备上恢复。
- **同步** —— 可选、自托管、零知识：WebSocket 实时推送、二维码配对设备、独立密码保护的浏览器账号区（仅元数据和设备吊销）。见 [同步服务器](#同步服务器)。
- **团队** —— 主机、代码片段和 Runbook 的端到端加密共享，按成员设置访问范围，活动流记录谁改了哪台主机、谁打开了会话。
- **界面** —— 深色和浅色主题，终端跟随应用主题，系统模式跟随操作系统，界面支持英语、俄语和简体中文。

---

## AI 与隐私

助手在以下边界内工作：

- **请求内容** —— 请求文本和固定的系统提示词。终端输出、主机列表和保险库记录不会被发送。
- **云端模式** —— 仅使用你自己的 OpenAI Key：流量从应用直达你设置的端点，中间没有服务器。
- **主机策略** —— 决定请求发给谁：
  - **严格**（新主机默认）—— 仅本地模型。
  - **均衡** —— 云端，但从提示词中剔除明显的机密：私钥、令牌、`password=…`。该机制基于模式匹配，不提供任何保证。
  - **宽松** —— 云端且不做脱敏，适用于非敏感系统。
  - **关闭** —— 该主机上隐藏助手。
- **快速聊天** —— 始终脱敏，包含本地模型。
- **本地模型** —— 通过设备上的 llama.cpp 运行 GGUF 模型（Qwen3、Phi-4 Mini），无外发流量。
- **命令执行** —— 模型输出不可信：运行需要明确确认，危险命令需要二次确认。

---

## 技术栈

- **语言与 UI** —— Kotlin 2.4、Compose Multiplatform 1.9
- **构建** —— Gradle 9.6、Android Gradle Plugin 9.1、JDK 21（所有模块 `jvmToolchain(21)`）
- **Android** —— minSdk 26（Android 8.0）、compileSdk 37、targetSdk 36
- **SSH 与加密** —— sshj、BouncyCastle、libsodium（ionspin KMP）：Argon2id + XChaCha20-Poly1305
- **终端** —— 自研网格仿真，桌面端本地 Shell 用 pty4j
- **远程桌面** —— 为本项目自研的 VNC（RFB）和 RDP 栈，无第三方客户端
- **串口** —— jSerialComm（桌面端）、usb-serial-for-android（Android）
- **AI** —— 本地模型用 llamatik（llama.cpp 绑定），云端用 Ktor 客户端
- **同步** —— Ktor（客户端和服务器）、Exposed、SQLite/PostgreSQL、HikariCP、Nimbus SRP-6a
- **质量** —— JUnit 5、Kover 覆盖率、detekt 静态分析

确切版本见 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)。

---

## 仓库结构

```
shared/       # KMP 核心：ssh/、sftp/、vault/、sync/、team/、share/、terminal/、ai/（+ai/local）、
              # telnet/、serial/、mosh/、rdp/、vnc/、graphics/、audio/、tunnel/、container/、
              # snippet/、runbook/、host/、tag/、files/、guard/、update/
composeApp/   # UI（Compose Multiplatform）：commonMain + androidMain + desktopMain
androidApp/   # Android 应用（MainActivity、manifest），applicationId app.skerry
server/       # 自托管同步服务器（Ktor，AGPL-3.0）
sync-wire/    # 客户端与服务器共享的线协议
docs/         # 文档与设计素材
```

---

## 从源码构建

开发流程、提交规范和打包说明见 **[CONTRIBUTING.md](CONTRIBUTING.md)**。

需要 **JDK 21**（`foojay-resolver` 会在需要时自动获取）和 Android SDK —— 每个客户端构建都会配置 `:androidApp`，所以即使只构建桌面版也需要设置 `ANDROID_HOME` 或在 `local.properties` 中配置 `sdk.dir`。

构建产物对应构建机器的操作系统和 CPU 架构：只有 macOS/ARM 才能产出 arm64 的 `.dmg`。

```bash
./gradlew :composeApp:run                                # 运行
./gradlew :composeApp:packageDistributionForCurrentOS    # .deb / .rpm / .msi / .dmg
./gradlew :composeApp:packageAppImage                    # 便携版 Linux .AppImage
./gradlew :composeApp:packagePortableZip                 # 便携版 .zip
```

Android：

```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :androidApp:installDebug
```

测试（JUnit 5）和静态分析：

```bash
./gradlew test allTests    # 单独 `test` 会跳过多平台模块
./gradlew detektAll        # 已有问题记录在 gradle/detekt-baseline-*.xml
```

---

## 同步服务器

服务器只用于设备间同步，而且永远是你的服务器：没有厂商云。

零知识设计：服务器上存放的是密文（包装后的 `dataKey`、加密的保险库记录）和同步元数据。认证使用 SRP-6a，密码永不传输，服务器无法解密你存储的任何内容。

快速开始 —— 使用 [Docker Hub](https://hub.docker.com/r/secherkasov/skerry-sync) 上的预构建多架构镜像，SQLite 存储在命名卷中，零配置：

```bash
docker run -d --name skerry-sync -p 8080:8080 \
  -e SKERRY_JWT_SECRET="$(openssl rand -base64 48)" \
  -e SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)" \
  -v skerry-data:/data \
  secherkasov/skerry-sync:latest
```

服务器监听 `http://localhost:8080`，自带内置离线 Web 前端：`/` 公共页面、`/account` 账号管理、`/console` 运维控制台。从源码构建 —— 在仓库根目录执行 `docker compose up -d --build`；PostgreSQL 通过 `db` 服务和 [docker-compose.yml](docker-compose.yml) 中的 postgres 变量启用。仅构建服务器无需 Android SDK：`./gradlew :server:run -PserverOnly`。

配置、API 端点、TLS 终结（Caddy/nginx）、备份和隐私模型见 **[server/README.md](server/README.md)**。

---

## 安全

私有漏洞报告、受支持版本、威胁模型和审计状态见 **[SECURITY.md](SECURITY.md)**。

---

## 参与贡献

欢迎提交 issue 和 pull request。环境搭建、模块结构、项目开发方式以及 PR 需要满足的条件见 **[CONTRIBUTING.md](CONTRIBUTING.md)**。

---

## 许可证

- 客户端（`shared/`、`composeApp/`、`androidApp/`）—— [GPL-3.0](LICENSE)
- 同步服务器（`server/`）—— [AGPL-3.0](server/LICENSE)：以服务形式托管本项目的 fork 必须把修改回馈给项目。
- 内置字体 —— OFL-1.1 和 Apache-2.0，文本与版本见 [licenses/](licenses/README.md)
