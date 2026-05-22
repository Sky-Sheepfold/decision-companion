# 人生决策伙伴 Agent — 接口文档

**版本**：v1.1
**更新时间**：2026-05-18

---

## 一、接口概述

| 项目 | 值 |
| --- | --- |
| 后端地址 | `http://localhost:8080` |
| 前端地址 | `http://localhost:5173` |
| API 前缀 | `/api` |
| 数据格式 | JSON |
| 字符编码 | UTF-8 |

除登录和注册外，业务接口都需要携带 Sa-Token JWT：

```http
Authorization: Bearer <token>
```

统一响应格式：

```json
{
  "code": 200,
  "message": "Success",
  "data": {}
}
```

---

## 二、账号认证

### 2.1 注册

**POST** `/api/auth/register`

请求体：

```json
{
  "username": "小杨",
  "password": "password123"
}
```

响应：

```json
{
  "code": 200,
  "message": "注册成功",
  "data": {
    "token": "<jwt>",
    "user": {
      "id": 1,
      "username": "小杨",
      "onboarded": false,
      "createdAt": "2026-05-18T10:00:00"
    }
  }
}
```

规则：

- `username` 支持中文，最长 50 个字符，保存前会 `trim`。
- `password` 明文长度 8-72 位，后端使用 BCrypt 加密保存。

### 2.2 登录

**POST** `/api/auth/login`

请求体：

```json
{
  "username": "小杨",
  "password": "password123"
}
```

响应同注册接口。

### 2.3 当前用户

**GET** `/api/auth/me`

请求头：

```http
Authorization: Bearer <token>
```

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "id": 1,
    "username": "小杨",
    "onboarded": true,
    "createdAt": "2026-05-18T10:00:00"
  }
}
```

### 2.4 退出登录

**POST** `/api/auth/logout`

前端退出时清除本地 JWT；后端调用 Sa-Token logout。

---

## 三、冷启动

### 3.1 获取问题列表

**GET** `/api/onboarding/questions`

返回 5 个冷启动问题。

### 3.2 获取指定问题

**GET** `/api/onboarding/questions/{step}`

`step` 范围：`1-5`。

### 3.3 提交答案

**POST** `/api/onboarding/answer`

请求体：

```json
{
  "step": 1,
  "answer": "我叫小杨，最近刚开始认真考虑职业方向。"
}
```

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "reply": "很高兴认识你...",
    "isCompleted": false,
    "currentStep": 1,
    "totalSteps": 5
  }
}
```

### 3.4 获取冷启动状态

**GET** `/api/onboarding/status`

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "onboarded": false,
    "currentStep": 1,
    "totalSteps": 5
  }
}
```

---

## 四、对话

### 4.1 普通对话

**POST** `/api/agent/chat`

请求体：

```json
{
  "conversationId": 1,
  "message": "我最近在纠结要不要考研"
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `conversationId` | number | 否 | 会话 ID，不传则创建新会话 |
| `message` | string | 是 | 用户消息内容 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "conversationId": 1,
    "reply": "听起来你正在做一个重要的决定..."
  }
}
```

### 4.2 流式对话

**GET** `/api/agent/chat/stream?message=我最近在纠结要不要考研&conversationId=1`

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `message` | string | 是 | 用户消息内容 |
| `conversationId` | number | 否 | 会话 ID，不传则创建新会话 |

响应类型：

```http
Content-Type: text/event-stream
```

响应片段：

```text
event: conversation
data: {"conversationId":1}

event: message
data: 听

event: message
data: 起

event: message
data: 来
```

### 4.3 历史会话列表

**GET** `/api/agent/conversations?limit=50`

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `limit` | number | 否 | 返回数量，默认 50，最大 100 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": [
    {
      "id": 1,
      "userId": 1,
      "title": "我最近在纠结要不要考研",
      "messageCount": 6,
      "deleted": false,
      "createdAt": "2026-05-22T10:00:00",
      "updatedAt": "2026-05-22T10:12:00"
    }
  ]
}
```

### 4.4 会话消息列表

**GET** `/api/agent/conversations/{conversationId}/messages`

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": [
    {
      "id": 1,
      "conversationId": 1,
      "userId": 1,
      "role": "user",
      "content": "我最近在纠结要不要考研",
      "createdAt": "2026-05-22T10:00:00"
    },
    {
      "id": 2,
      "conversationId": 1,
      "userId": 1,
      "role": "assistant",
      "content": "听起来你正在做一个重要的决定...",
      "createdAt": "2026-05-22T10:00:05"
    }
  ]
}
```

### 4.5 删除历史会话

**DELETE** `/api/agent/conversations/{conversationId}`

响应：

```json
{
  "code": 200,
  "message": "Success"
}
```

说明：删除为软删除，后续历史会话列表和消息详情不再返回该会话。

---

## 五、用户档案

### 5.1 获取完整档案

**GET** `/api/profile`

响应 `data`：

```json
{
  "user": {
    "id": 1,
    "username": "小杨",
    "onboarded": true,
    "createdAt": "2026-05-18T10:00:00"
  },
  "values": [],
  "decisions": [],
  "emotions": [],
  "relationships": [],
  "fears": []
}
```

### 5.2 获取单类档案

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/profile/values` | 价值观档案 |
| `GET` | `/api/profile/decisions` | 决策历史 |
| `GET` | `/api/profile/emotions` | 情绪模式 |
| `GET` | `/api/profile/relationships` | 关系图谱 |
| `GET` | `/api/profile/fears` | 恐惧与边界 |

---

## 六、错误码

| HTTP 状态码 | `code` | 说明 |
| --- | --- | --- |
| 400 | 400 | 参数错误、用户名重复、用户名或密码错误 |
| 401 | 401 | 未登录或 JWT 失效 |
| 500 | 500 | 服务器内部错误 |
