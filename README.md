# MCP Selenium Automation Test

> End-to-end automation via **Model Context Protocol (MCP)** + **Selenium**.  
> 目标：用自然语言（或结构化步骤）驱动浏览器执行用例，生成结构化执行报告（Markdown / JSON），并支持“失败后重新开始一个全新 session”。

## ✨ Features

- **MCP + Selenium**：使用 `mcp-selenium` 工具，通过 MCP 统一协议调度浏览器动作
- **多步指令执行**：支持按步骤顺序执行（Step1/Step2/...），并统计通过/失败/原因
- **一键重开会话**：失败后可重置 WebDriver，开始**全新会话**再跑（避免脏状态）
- **Markdown 执行报告**：针对每次执行生成 Markdown（含步骤状态与错误摘要）
- **（可选）Spring Boot 客户端整合**：提供 REST 接口触发执行与查看报告

## 🧱 Tech Stack

- Java 17+ / 21+
- Spring Boot（可选，若提供 Web 接口）
- Selenium WebDriver (Chrome)
- MCP（`@modelcontextprotocol` 生态） / `mcp-selenium`
- Maven

## 🗂 Project Structure（示例）

```
mcp-selenium-automationTest/
├── mcp-selenium-client/         # Spring Boot 客户端，暴露 Web UI + REST
│   ├── src/main/java/com/example/mcp/client/
│   └── src/main/resources/templates/
├── mcp-selenium-server/         # STDIO/HTTP 方式的 MCP Server，实现 Selenium 动作
│   ├── src/main/java/com/example/mcp/server/
│   └── ...
└── ...
```

## 🧪 Demo Web Flow（登录 + 仪表盘）

为了方便演示丰富的 Selenium 场景，客户端内置了一个两步 Demo 站点：

| 页面 | 路径 | 亮点动作 |
| --- | --- | --- |
| 登录入口 | `http://localhost:19101/demo/login` | 邮箱/密码输入、显示密码、记住我、多因子勾选、第三方登录提示、登录状态条 |
| 自动化仪表盘 | `http://localhost:19101/demo/dashboard` | 侧边导航、搜索输入+回车、过滤按钮、结果标签、动态表格、弹出「新建任务」模态、表单填写、滚动、Toast、截图点位 |

### 示例指令（可直接喂给 LLM planner）

```
Launch Chrome (headless=false) and go to http://localhost:19101/demo/login.
Wait for the email input (data-testid="login-email"), fill demo credentials, toggle “Show” to expose the password, enable “Remember me”, then submit.
Verify the success banner appears and follow the “Continue to automation dashboard” link.
On the dashboard, search for "UI regression", press Enter, ensure pills render.
Open the “Create scripted task” modal, fill title/owner/date, set priority High, add a note, save, expect toast message.
Scroll to the insights section and take a screenshot named dashboard-demo.png.
```

> 小贴士：页面上的主要元素都带有 `data-testid` 或语义化 `aria-*` 属性，便于生成稳定 selector；也可以配合前面实现的 runtime selector hints 自动推理。
