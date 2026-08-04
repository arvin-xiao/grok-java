# Grok Java - AI Coding Agent

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9+-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/Tests-145+-brightgreen.svg)](#测试)

Grok Java 是一个基于 Java 21 构建的 AI 编程助手框架，采用模块化架构设计，与 Rust 上游项目保持功能同步。

## 特性

- **🤖 智能 Agent 系统** - 基于 LangChain4j 的对话式 AI 代理
- **🔧 工具生态系统** - 内置 Bash、文件操作、搜索、任务协调等工具
- **🔒 权限管理** - 细粒度的命令风险评估和自动审批策略
- **📦 模块化架构** - 16 个 Maven 模块，清晰的职责分离
- **🔄 会话管理** - 多轮对话上下文跟踪和历史记录
- **⚡ 流式响应** - 支持实时流式输出
- **🛡️ 安全机制** - MCP Kill Switch、命令黑名单、风险评估

## 系统要求

- **Java**: 21+ (需要预览特性支持)
- **Maven**: 3.9+
- **操作系统**: Windows / Linux / macOS

## 快速开始

### 构建项目

```bash
# 设置 JAVA_HOME (Windows PowerShell)
$env:JAVA_HOME="D:\Program Files\Java\java21"

# 编译项目
mvn clean compile

# 运行测试
mvn test

# 打包
mvn package
```

### 配置模型

在 `grok-app/grok-bin/src/main/resources/application.yml` 中配置：

```yaml
grok:
  model:
    base-url: https://api.deepseek.com
    model-name: deepseek-v4-pro
    api-key: ${OPENAI_API_KEY}
```

或通过环境变量：

```bash
export OPENAI_API_KEY=your-api-key
export OPENAI_BASE_URL=https://api.deepseek.com
export GROK_MODEL=deepseek-v4-pro
```

## 项目架构

```
grok-java/
├── grok-api/                    # API 定义层
│   ├── grok-tool-api/          # 工具 API 接口
│   └── grok-session-api/       # 会话 API 接口
├── grok-core/                   # 核心实现层
│   ├── grok-agent/             # Agent 实现
│   ├── grok-session/           # 会话管理
│   ├── grok-config/            # 配置加载
│   ├── grok-permission/        # 权限系统
│   ├── grok-workflow/          # 工作流引擎
│   └── grok-tool-registry/     # 工具注册中心
├── grok-tools/                  # 工具实现层
│   ├── grok-tool-bash/         # Bash 命令执行
│   ├── grok-tool-file/         # 文件读写操作
│   ├── grok-tool-search/       # 代码搜索
│   ├── grok-tool-task/         # 子任务协调
│   └── grok-tool-scheduler/    # 定时任务调度
├── grok-ui/                     # UI 层
│   └── grok-headless/          # 无头模式
└── grok-app/                    # 应用层
    └── grok-bin/               # 可执行入口
```

## 核心模块说明

### grok-agent

Agent 核心实现，负责：
- 与 LLM 模型交互
- 工具调用循环 (Tool Loop)
- 流式响应处理
- 取消控制

```java
// 创建 Agent
GrokAgent agent = AgentBuilder.builder()
    .id("agent-1")
    .name("Grok Assistant")
    .systemPrompt("You are a helpful coding assistant.")
    .tools(toolRegistry)
    .config(config)
    .build();

// 执行对话
AgentResponse response = agent.turn(request);
```

### grok-permission

权限管理系统，提供：
- **ExecRiskAssessor** - 命令风险评估（支持 git 只读命令自动批准）
- **AutoModePolicy** - 自动审批策略（Permissive/Balanced/Strict/Lockdown）
- **PermissionState** - 会话级权限状态（支持 BashGlob 模式匹配）
- **McpKillSwitch** - MCP 工具紧急禁用机制
- **RequestPathContext** - 路径上下文感知

```java
// 风险评估
ExecRiskAssessor assessor = new ExecRiskAssessor();
ExecRisk risk = assessor.assess("git status", context);
// risk.level() == RiskLevel.SAFE (git 只读命令自动批准)

// 权限状态
PermissionState state = new PermissionState();
state.allowBashGlob("cargo *");  // 通配符模式
boolean allowed = state.isBashCommandMatchedByGlob("cargo build");
```

### grok-tool-task

子任务协调器，特性：
- 并发控制（最大并发数限制）
- 预算控制（总任务数限制）
- **防复活机制** - 已完成任务不会被意外恢复
- 取消传播

```java
SubagentCoordinator coordinator = new SubagentCoordinator(
    4,   // maxConcurrent
    10   // budget
);

SubagentHandle handle = coordinator.spawn("Analyze code");
coordinator.complete(handle.id(), result);
// 重复调用 complete 是安全的（防复活）
```

### grok-tools

内置工具集：

| 工具 | 模块 | 功能 |
|------|------|------|
| BashTool | grok-tool-bash | 执行 shell 命令 |
| FileTool | grok-tool-file | 文件读写操作 |
| SearchTool | grok-tool-search | 代码搜索 |
| TaskTool | grok-tool-task | 子任务管理 |
| SchedulerTool | grok-tool-scheduler | 定时任务 |

## 测试

项目包含 **145+ 个测试用例**，覆盖所有核心模块：

```bash
# 运行所有测试
mvn test

# 运行特定模块测试
mvn test -pl grok-core/grok-permission

# 运行真实模型测试（需要 API Key）
export OPENAI_API_KEY=your-key
mvn test -pl grok-core/grok-agent -Dtest=RealModelE2ETest
```

### 测试覆盖

| 模块 | 测试数 | 覆盖内容 |
|------|--------|---------|
| ExecRiskAssessor | 25+ | git 只读、unsafe 选项、风险评估 |
| PermissionState | 13 | BashGlob、MCP、域名管理 |
| RequestPathContext | 7 | 路径解析、cwd 感知 |
| McpKillSwitch | 9 | 全局/服务器级 kill switch |
| TaskLogTruncator | 13 | 日志截断、边界条件 |
| SubagentCoordinator | 20 | 防复活、生命周期 |
| StreamHeaderCollector | 14 | HTTP 头保留、x-should-retry |
| Agent E2E | 12 | Mock 模型端到端测试 |
| Session E2E | 7 | 会话流程测试 |

## 与 Rust 上游同步

本项目与 [xai-org/grok-build](https://github.com/xai-org/grok-build) Rust 实现保持功能同步。

### 最近同步 (2026-08-03)

- ✅ Git 只读查询自动批准（`git status`、`git log` 等）
- ✅ BashGlob 模式支持（通配符权限匹配）
- ✅ MCP Kill Switch 强制执行
- ✅ 任务日志截断（防止过长输出）
- ✅ 后台任务防复活机制
- ✅ x-should-retry 头保留
- ✅ RequestPathContext 路径上下文

## 开发指南

### 添加新工具

1. 实现 `Tool` 接口：

```java
public class MyTool implements Tool {
    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
            .id(ToolId.of("my-tool"))
            .name("My Tool")
            .description("Does something useful")
            .build();
    }
    
    @Override
    public ToolResult execute(ToolCall call, ToolCallContext context) {
        // 实现逻辑
        return ToolResult.success("Done!");
    }
}
```

2. 注册到 `ToolRegistry`

### 代码规范

- 使用 Java 21 特性（Records、Pattern Matching、Virtual Threads）
- 启用预览特性：`--enable-preview`
- 遵循现有代码风格和命名约定
- 新功能必须包含单元测试

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 21 |
| 构建 | Maven | 3.9+ |
| AI 框架 | LangChain4j | 0.35.0 |
| 日志 | SLF4J + Logback | - |
| 测试 | JUnit 5 | - |
| 终端 | JLine | 3.26.3 |
| Git | JGit | 6.10.0 |

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request！

## 相关链接

- [上游 Rust 项目](https://github.com/xai-org/grok-build)
- [LangChain4j](https://github.com/langchain4j/langchain4j)
- [Java 21 文档](https://openjdk.org/projects/jdk/21/)
