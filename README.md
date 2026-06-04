# 人生决策伙伴 Agent

> 一个带长期记忆的 AI 决策陪伴应用：在用户反复面对职业、关系、城市、家庭和自我成长选择时，持续沉淀个人画像，并在后续对话中通过 Memory RAG 召回相关长期记忆，让建议从“一次性回答”变成“越用越懂你”。

![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-6DB33F)
![MyBatis--Plus](https://img.shields.io/badge/MyBatis--Plus-3.5.15-red)
![MySQL](https://img.shields.io/badge/MySQL-8-4479A1)
![React](https://img.shields.io/badge/React-19-61dafb)
![Chroma](https://img.shields.io/badge/Vector%20Store-Chroma-purple)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

## 项目亮点

- **长期记忆驱动的决策陪伴**：围绕价值观、历史决策、情绪模式、关系影响、恐惧与边界建立用户长期画像。
- **Memory RAG 召回链路**：生成回复前混合召回 MySQL 结构化画像和 Chroma 场景记忆，并注入系统提示词。
- **按场景意图调整召回策略**：识别重大决策、情绪倾诉、复盘、目标规划、关系压力等场景，动态调整召回数量、query 改写、memoryType 偏好和 prompt 区块顺序。
- **轻量 rerank 与可解释日志**：综合向量分、memoryType、置信度、时间新近度和正式画像关联状态排序，并记录 `rerankReason` 方便排查。
- **可治理的记忆写入**：高置信度画像进入正式档案，中置信度进入待确认候选，用户可确认、拒绝、修正或删除。
- **完整前后端闭环**：包含登录注册、冷启动引导、聊天、记忆罗盘、档案页、待确认记忆处理和 SSE 流式响应。

## 架构图

![人生决策伙伴 Agent 架构图](assets/project_architecture.svg)

## 核心流程

```text
用户消息
  -> 保存对话消息
  -> 召回长期记忆
       -> MySQL 结构化画像
       -> Chroma 场景记忆
       -> 场景意图识别 / query 改写 / rerank
  -> 注入系统提示词
  -> DashScope qwen-plus 生成回复
  -> 保存助手回复
  -> 异步提炼长期画像
       -> 高置信度写入正式画像和 Chroma
       -> 中置信度进入待确认候选
       -> 低置信度跳过
```

## Memory RAG

本项目中的 RAG 不是通用文档知识库，而是面向用户长期记忆的检索增强。

| 层级 | 存储 | 作用 |
| --- | --- | --- |
| 结构化画像 | MySQL | 保存稳定、可解释、可治理的长期结论 |
| 场景记忆 | Chroma | 保存已确认画像对应的相似场景证据 |
| 治理审计 | MySQL | 保存待确认候选、正式画像修正/删除和 Chroma document 关联 |

生成前召回会按当前 query 识别意图：

- `major_decision`：重大决策，例如 offer、城市选择、去留取舍。
- `venting`：情绪倾诉，例如焦虑、压力、崩溃、撑不住。
- `review`：复盘总结，例如回顾、历史、多次、模式。
- `goal_planning`：目标规划，例如长期目标、职业路径、未来准备。
- `relationship_pressure`：关系压力，例如父母、伴侣、朋友、同事影响。
- `general`：默认召回策略。

## 功能模块

| 模块 | 说明 |
| --- | --- |
| 认证与会话 | 注册、登录、JWT 鉴权、会话列表、消息历史、会话删除 |
| 冷启动引导 | 通过 5 个自然问题建立初始画像，支持跳过和进度查询 |
| 对话 Agent | 普通对话、SSE 流式对话、上下文保存、长期记忆注入 |
| Agent Tools | 历史决策查询、场景记忆查询、决策矩阵生成、受控画像更新 |
| 用户档案 | 价值观、决策历史、情绪模式、关系影响、恐惧与边界 |
| 记忆治理 | 待确认候选、确认/拒绝/修正、正式画像修正/删除、审计日志 |
| 召回观测 | 记录召回意图、改写 query、候选 TopK、命中摘要和 rerank 原因 |

## 技术栈

后端：

- Java 17
- Spring Boot 3.5.9
- Spring AI 1.1.2
- Spring AI Alibaba 1.1.2.2
- DashScope `qwen-plus`
- MyBatis-Plus 3.5.15
- MySQL 8
- Chroma Vector Store
- Sa-Token + JWT
- Springdoc OpenAPI

前端：

- React 19
- TypeScript
- Vite 8
- React Router 7
- Zustand
- Ant Design 6
- Tailwind CSS 4

## 快速开始

### 1. 准备环境变量

在项目根目录创建 `.env`：

```env
DASHSCOPE_API_KEY=你的_DashScope_API_Key
SERVER_IP=localhost
DB_PASSWORD=你的数据库密码
CHROMA_HOST=localhost
CHROMA_PORT=8000
SA_TOKEN_JWT_SECRET_KEY=请替换为足够长的随机字符串
```

前端开发环境：

```bash
cd frontend
cp .env.example .env
```

默认前端 API 地址：

```env
VITE_API_BASE_URL=http://localhost:8080/api
```

### 2. Docker Compose 一键启动

```bash
docker compose up -d --build
```

启动后访问：

- 前端：http://localhost:5173
- 后端：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui/index.html
- Chroma：http://localhost:8000
- MySQL：localhost:3306

### 3. 本地开发模式

只用 Docker 启动 MySQL 和 Chroma：

```bash
docker compose up -d mysql chroma
```

启动后端：

```bash
mvn -f pom.xml spring-boot:run
```

启动前端：

```bash
cd frontend
npm install
npm run dev
```

## 常用命令

后端：

```bash
# 全量测试
mvn -f pom.xml test

# Memory RAG 聚焦测试
mvn -f pom.xml -Dtest=MemoryRetrievalServiceTest,MemoryRetrievalLogServiceTest,MemoryRagEvaluationSamplesTest test

# 可选：真实 Chroma 召回测试
mvn -f pom.xml \
  -Dtest=MemoryRagLiveVectorSearchTest \
  -Dmemory.rag.live=true \
  -Dmemory.rag.user-id=你的用户ID \
  -Dmemory.rag.query="我现在纠结外地 offer 和离父母近之间怎么取舍" \
  test
```

前端：

```bash
cd frontend
npm install
npm run dev
npm run build
npm run lint
```

## 项目结构

```text
.
├── assets/                       # 架构图等资源
├── docs/                         # 项目文档
├── frontend/                     # React + Vite 前端应用
├── src/main/java/com/sky/decisioncompanion/
│   ├── advisor/                  # 长期记忆上下文注入
│   ├── common/                   # 统一响应、异常和基础 DTO
│   ├── config/                   # AI、认证、CORS、MyBatis、OpenAPI、RAG 配置
│   ├── controller/               # REST API 控制器
│   ├── model/                    # 用户、对话、画像、日志等模型
│   ├── repository/               # MyBatis-Plus 数据访问层
│   └── service/                  # 认证、对话、冷启动、画像提炼、记忆治理
├── src/test/                     # 单元测试、Memory RAG eval 样例、可选真实 Chroma 测试
├── docker-compose.yml            # 本地完整编排
├── docker-compose.prod.yml       # 生产编排
├── init.sql                      # MySQL 初始化脚本
└── pom.xml                       # Maven 后端工程配置
```

## API 调试

后端统一 API 前缀为 `/api`。除注册、登录和 Swagger 相关路径外，其余 `/api/**` 默认需要：

```text
Authorization: Bearer <token>
```

完整接口可以通过 Swagger 查看：

```text
http://localhost:8080/swagger-ui/index.html
```

## License

MIT
