# Skerry v0.4.0 设计方案：AI 自动执行模式（Agent Mode）

> 状态：待评审 · 作者：二开 · 日期：2026-08-11
> 目标版本：v0.4.0（在 v0.3.2 二开版基础上）

---

## 1. 目标

把 AI 从"只给命令的顾问"升级为"受约束的操作员"：**用户授权一次，AI 在护栏内自主执行多步任务**（命令 → 读输出 → 决策 → 下一步），危险操作仍然卡用户确认。

现状（v0.3.x）：`TerminalAiController` 明确 `No auto-run under any policy` —— AI 只产出单条命令，确认后发送一次，看不到结果，无法迭代。

## 2. 交互流程（用户视角）

```
[用户] 开启会话的"AI 自动执行"开关（per-host，默认关）
[用户] "看看磁盘为什么满了，清理一下"
[AI]   agent 循环开始：
       ├─ 思考 → 执行 df -h → 读输出（终端实时可见）
       ├─ 思考 → 执行 du -sh /var/* | sort -rh | head（Warn 级，自动过）
       ├─ 思考 → 提议 rm -rf /var/log/old/*（Danger！）→ ⛔ 面板弹确认，等待
       ├─ [用户确认/拒绝]
       └─ 完成 → 面板输出总结："清理了 2.3GB：/var/log/old（1.8G）、/tmp/cache（0.5G）"
[用户] 随时 Esc / 停止按钮 → SIGINT 中断循环
```

- 面板显示循环状态：思考中 / 执行中 / 等待确认 / 已完成 / 已中断
- 命令执行过程在终端里实时可见（沿用现有"插入终端输入"通道），不是黑盒
- 每步执行后，AI 只拿到**输出摘要**（截断 + 清洗），不是原始全部输出

## 3. 安全模型（核心设计）

| # | 护栏 | 规则 |
|---|---|---|
| 1 | **总开关** | 自动执行默认关闭；per-host 设置；打了 `prod` 标签的主机**强制关闭**（或强制每步确认，见设置项） |
| 2 | **命令风险分级** | 沿用现有 `CommandRiskClassifier`（正则启发式，Danger/Warn/None）：**Danger → 永远人工确认**（即便 auto 模式）；Warn → 自动执行但面板醒目提示；None → 直接执行 |
| 3 | **输出注入防护** | 模型输出不可信（沿用 `AiReplyParser.sanitizeCommand` 单行化）；**终端输出作为模型输入前同样清洗**：剥离控制字符/ANSI，截断到 N 字符（默认 4000），防恶意输出诱导模型执行危险命令 |
| 4 | **步数/时长上限** | 默认每任务 ≤ 20 步、≤ 10 分钟，设置里可调；超限自动停止并输出报告 |
| 5 | **循环上下文** | 每次只喂"上一步输出摘要 + 任务 + 已执行历史（命令列表，不含完整输出）"，防止上下文膨胀和注意力丢失 |
| 6 | **可中断** | Esc / 停止按钮 → 发送 SIGINT（现有 Ctrl+C 通道），循环进入 Interrupted 状态 |
| 7 | **敏感信息** | 沿用 `SecretRedactor`：Balanced 策略下输出摘要先脱敏再上云 |

**核心原则**：自动执行 ≠ 无确认。风险分级是"启发式"，永远可能漏判——所以 Danger 人工确认 + prod 禁自动 + 可中断三件套兜底。与现有安全测试（`CommandRiskClassifierTest` 等）不冲突，auto 模式不得绕过任何现有检查。

## 4. 技术方案（最大化复用现有组件）

### 4.1 执行引擎：复用 Runbook 机制

项目已有 **Runbook（运行手册）**：`step by step run of a procedure in a live session: a step is a command or an SFTP transfer, a pause for confirmation, a stop on a non-zero exit code. Run log: state, duration and output of every step`。

- `RunbookRunner` + `TerminalScreenState` 输出 buffer（"Batches of PTY output fed so far"，已有 watchdog）＝ 现成的"发命令 → 等输出 → 判定"引擎
- Agent 循环 = Runbook 的**动态版**：步骤不是预先写好的，而是每步由模型生成
- 命令完成判定：沿用 runbook 的机制（提示符检测 / 静默超时）

### 4.2 状态机（shared/commonMain，纯 Kotlin 可测）

```
AgentLoopState
├── Idle
├── Thinking          # 模型生成中（流式）
├── AwaitingExecution # 命令已清洗+分级，准备发送
├── AwaitingConfirm   # Danger 命令，等用户
├── Executing         # 已发送，等输出/完成判定
├── Evaluating        # 输出摘要喂回模型
├── Done              # 模型返回 DONE（带总结）
├── Failed            # 错误/超步数/超时
└── Interrupted       # 用户 Esc/停止
```

转移：Thinking → AwaitingExecution →（None/Warn）→ Executing → Evaluating → Thinking …（Danger）→ AwaitingConfirm → Executing；任一步可 → Interrupted / Failed。

### 4.3 模型协议（扩展现有 CMD:/ASK: 协议，向后兼容）

模型回复扩展为三种 ACTION（单条命令模式 `CMD:` 保持不变，非 agent 模式不受影响）：

```
ACTION: CMD <command>        # 执行一步（替代 CMD:，携带更多上下文）
ACTION: DONE <总结>          # 任务完成，附带做了什么/结果
ACTION: ASK <问题>           # 需要用户澄清/确认（同现有 ASK）
```

Agent 模式 system prompt 增加：任务执行规范（每次只给一条命令、基于上一步输出决策、完成时给 DONE 总结、不确定时 ASK）。

### 4.4 输出捕获与完成判定

- 复用 `TerminalScreenState` 输出 buffer，agent 执行期间标记"输出窗口"（从发送命令到完成判定之间的增量）
- 完成判定：优先复用 runbook 的提示符检测；无提示符时静默超时（默认 8s，设置可调）——**超时后把已捕获输出喂回模型，由模型判断是否还需等待**（比硬编码更鲁棒）

### 4.5 UI（composeApp/ui/ai）

- `AgentPanel` 状态视图：循环状态徽标、当前命令、最近输出摘要（可折叠）、步数计数、停止按钮
- Danger 确认：复用现有 `AssistantMessage` 的双击确认交互（"Run anyway" → "Confirm run"）
- 设置项（沿用现有 settings 结构）：`ai.agent.enabled`（全局默认）、per-host 覆盖、`ai.agent.maxSteps`（默认 20）、`ai.agent.maxMinutes`（默认 10）、`ai.agent.outputContextChars`（默认 4000）、prod 行为（禁用 / 每步确认）

## 5. 改动范围

| 模块 | 改动 | 量级 |
|---|---|---|
| `shared/ai` | `AgentLoopState` 状态机 + `AgentLoopController`（纯逻辑）、prompt 模板扩展、输出清洗函数（`sanitizeOutputContext`）、`AgentLoopTest` | 中（核心） |
| `shared/terminal` | 暴露"最近输出窗口"API（runbook 机制薄封装） | 小 |
| `composeApp/ui/ai` | Agent 面板 UI、Danger 确认流、状态渲染 | 中 |
| `composeApp/ui/terminal` | 执行通道接线（发送命令 + Esc 中断路由）、agent 期间命令高亮 | 小 |
| `composeApp/ui/settings` | agent 设置项（开关/上限/prod 行为） | 小 |
| 文档 | README 中文更新（v0.4.0 说明） | 小 |

## 6. 测试策略

- **单元**（commonTest）：`AgentLoopControllerTest` 状态转移矩阵（含每步可中断）、`sanitizeOutputContext` 清洗（ANSI/控制字符/超长截断）、风险拦截矩阵（None 自动/Warn 自动+Danger 拦截/prod 强制确认）
- **安全回归**：现有 `CommandRiskClassifierTest`、`AiReplyParserTest` 全量保留，新增"auto 模式不得绕过"用例
- **集成**（desktopTest）：FakeShell 下跑完整 agent 会话（3 步任务 + 1 次 Danger 拦截），断言输出喂回模型、Danger 时暂停

## 7. 版本规划

- **v0.4.0**：Agent 模式 v1 —— 单会话线性循环、Danger 人工确认、步数/时长上限、Esc 中断、输出摘要清洗、prod 禁自动
- **v0.4.x**：多会话并行任务、任务历史记忆（跨会话续跑）、undo 建议（基于历史命令生成回滚命令）、只读审计日志（agent 做了什么全记录）

## 8. 明确不做（v0.4.0 范围外）

- 不做沙箱/容器隔离执行（代理模式跑在用户已连接的终端里，这是 SSH 客户端的本质）
- 不做无人值守后台任务（agent 循环始终在用户可见的会话中）
- 不改变现有单条命令确认模式（非 agent 场景行为不变）
