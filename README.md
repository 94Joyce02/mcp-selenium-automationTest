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

