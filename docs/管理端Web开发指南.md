# 管理端 Web 前端开发指南

本文档说明如何基于后端管理接口构建管理端 Web 前端，涵盖认证流程、接口调用规范、各功能模块的数据结构与交互逻辑。

---

## 目录

- [基础配置](#基础配置)
- [认证机制](#认证机制)
- [功能模块](#功能模块)
  - [仪表盘 — 游玩统计](#仪表盘--游玩统计)
  - [用户反馈 — 建议查看](#用户反馈--建议查看)
  - [数据管理 — 游戏脚本](#数据管理--游戏脚本)
  - [模型配置 — LLM 设置](#模型配置--llm-设置)
  - [系统操作 — 重启服务](#系统操作--重启服务)
- [接口速查表](#接口速查表)
- [错误处理](#错误处理)
- [前端技术建议](#前端技术建议)

---

## 基础配置

后端默认运行在 `http://localhost:8080`，所有管理端接口前缀为 `/api/admin`。

管理员账号在 `application.yml` 中配置默认值，首次启动后可通过接口修改，修改后的凭证持久化到 `data/admin.json`：

```yaml
yuzu:
  admin:
    username: admin
    password: yuzu2024
```

---

## 认证机制

### 登录流程

```
┌──────────┐    POST /api/admin/login     ┌──────────┐
│  登录页面 │ ────────────────────────────→ │  后端API  │
│          │ ←──────────────────────────── │          │
└──────────┘    { success, token }         └──────────┘
      │
      │  存储 token 到 localStorage
      ▼
┌──────────┐    后续请求 Header             ┌──────────┐
│  管理页面 │ ─── X-Admin-Token: <token> ──→ │  后端API  │
└──────────┘                                └──────────┘
```

### 登录接口

**请求**

```
POST /api/admin/login
Content-Type: application/json

{
  "username": "admin",
  "password": "yuzu2024"
}
```

**成功响应**

```json
{
  "success": true,
  "token": "2685a12bc6b743ef927c5fe8bd54b326"
}
```

**失败响应**

```json
{
  "success": false,
  "message": "Invalid credentials"
}
```

### Token 规则

- Token 有效期 **24 小时**，过期后需重新登录
- 所有管理端接口（除登录外）必须在请求头携带 `X-Admin-Token`
- Token 无效或过期时，接口返回 **HTTP 401**
- 修改管理员账号密码后，当前 Token 立即失效，需重新登录

### 管理员账号管理

#### 查看管理员信息

```
GET /api/admin/admin/info
X-Admin-Token: <token>
```

```json
{
  "admin": {
    "username": "admin"
  }
}
```

#### 修改管理员账号密码

修改后旧 Token 立即失效，需用新账号重新登录。凭证持久化到 `data/admin.json`，重启后仍有效：

```
PUT /api/admin/admin/credentials
X-Admin-Token: <token>
Content-Type: application/json

{
  "username": "newadmin",
  "password": "newpass123"
}
```

**成功响应**

```json
{
  "success": true,
  "message": "Credentials updated, please login again"
}
```

**失败响应**（用户名或密码为空）

```json
{
  "success": false,
  "message": "Username and password must not be empty"
}
```

**页面建议**

- 在设置页或顶部栏用户菜单中提供"修改密码"入口
- 表单包含：新用户名、新密码、确认密码
- 前端校验两次密码一致后提交
- 提交成功后清除本地 Token，跳转登录页

### 前端认证拦截（伪代码）

```javascript
// 请求拦截器 — 自动附加 Token
axios.interceptors.request.use(config => {
  const token = localStorage.getItem('admin_token');
  if (token) {
    config.headers['X-Admin-Token'] = token;
  }
  return config;
});

// 响应拦截器 — 401 自动跳转登录
axios.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('admin_token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);
```

---

## 功能模块

### 仪表盘 — 游玩统计

#### 游玩人数

**接口**

```
GET /api/admin/stats/players
X-Admin-Token: <token>
```

**响应**

```json
{
  "totalPlayers": 265,
  "todayPlayers": 3,
  "thisMonthPlayers": 45
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `totalPlayers` | long | 历史总会话数 |
| `todayPlayers` | long | 今日新建会话数 |
| `thisMonthPlayers` | long | 本月新建会话数 |

**页面建议**

- 顶部三张统计卡片，分别展示总数/今日/本月
- 可搭配简单折线图展示趋势（需前端自行按日聚合）

#### 玩家进度

**接口**

```
GET /api/admin/stats/progress
X-Admin-Token: <token>
```

**响应**

```json
{
  "chapterDistribution": {
    "ch1": 208,
    "ch2": 25,
    "ch3": 11,
    "ch4": 7,
    "ch5": 3
  },
  "averageTurns": 8.97
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `chapterDistribution` | Map<String, Long> | 各章节当前停留的会话数 |
| `averageTurns` | Double | 所有会话的平均回合数 |

**页面建议**

- 章节分布：横向柱状图，按 ch1→ch5 排列，直观展示玩家流失点
- 平均回合数：单独数字卡片
- 章节名称映射（前端硬编码即可）：

```javascript
const chapterNames = {
  'ch1': '管道之歌',
  'ch2': '暗夜前厅',
  'ch3': '数据低语',
  'ch4': '封印之间',
  'ch5': '不可名状之真相'
};
```

---

### 用户反馈 — 建议查看

#### 查看反馈列表

分页查询，按提交时间倒序排列：

```
GET /api/admin/feedbacks?page=0&size=20
X-Admin-Token: <token>
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 0 | 页码，从 0 开始 |
| `size` | int | 20 | 每页条数 |

**响应**

```json
{
  "total": 4,
  "page": 0,
  "size": 20,
  "items": [
    {
      "id": 4,
      "contact": "test@example.com",
      "content": "游戏很棒，期待更多章节！",
      "sessionId": "test-session-001",
      "createdAt": "2026-05-21T10:45:01.225524Z"
    },
    {
      "id": 3,
      "contact": "114514",
      "content": "听说怪物有格调",
      "sessionId": "9dfe8533",
      "createdAt": "2026-05-18T18:53:19.447087Z"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `total` | int | 反馈总数 |
| `page` | int | 当前页码 |
| `size` | int | 每页条数 |
| `items` | Array | 反馈列表 |
| `items[].id` | Long | 反馈ID |
| `items[].contact` | String | 玩家联系方式（可为空） |
| `items[].content` | String | 反馈正文 |
| `items[].sessionId` | String | 关联的游戏会话ID（可为空） |
| `items[].createdAt` | String | 提交时间（ISO 8601） |

**页面建议**

- 表格展示：ID、联系方式、内容、关联会话、提交时间
- 内容较长时截断显示，点击展开完整内容
- 底部分页器，根据 `total` 和 `size` 计算总页数
- 可选：点击关联会话跳转到该会话详情

---

### 数据管理 — 游戏脚本

#### 列出数据文件

```
GET /api/admin/data/files
X-Admin-Token: <token>
```

```json
{
  "files": [
    "endings.json",
    "game_config.json",
    "items.json",
    "maps.json",
    "npcs.json",
    "prompts.json",
    "protagonist.json",
    "puzzles.json",
    "redemption_codes.json",
    "story.json"
  ]
}
```

#### 读取数据文件

```
GET /api/admin/data/file?name=maps.json
X-Admin-Token: <token>
```

```json
{
  "name": "maps.json",
  "content": "[\n  {\n    \"id\": \"map_sewer_b1\",\n    ...\n  }\n]"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | 文件名 |
| `content` | String | 文件完整内容（JSON 字符串） |
| `error` | String | 文件不存在时的错误信息 |

#### 修改数据文件

后端会校验文件扩展名必须为 `.json` 且内容为合法 JSON，校验不通过则拒绝写入：

```
PUT /api/admin/data/file
X-Admin-Token: <token>
Content-Type: application/json

{
  "name": "maps.json",
  "content": "[{ ... }]"
}
```

**成功响应**

```json
{
  "success": true
}
```

**失败响应**（文件名非 `.json` 或内容不是合法 JSON）

```json
{
  "success": false,
  "message": "Failed to write file: check filename extension (.json) and content format"
}
```

#### 重载游戏数据

修改文件后调用，使配置立即生效，无需重启服务：

```
POST /api/admin/data/reload
X-Admin-Token: <token>
```

```json
{
  "success": true,
  "message": "Game data reloaded"
}
```

#### 导出数据

```
GET /api/admin/data/export
X-Admin-Token: <token>
```

返回 `application/octet-stream`，`Content-Disposition: attachment; filename="game-data.zip"`，包含全部 10 个 JSON 文件。

#### 导入数据

上传 ZIP 包，其中每个 `.json` 文件会校验格式，无效 JSON 文件自动跳过不写入：

```
POST /api/admin/data/import
X-Admin-Token: <token>
Content-Type: multipart/form-data

file: <game-data.zip>
```

```json
{
  "success": true,
  "importedFiles": 10
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | boolean | 是否全部成功 |
| `importedFiles` | int | 实际导入的文件数（跳过无效文件不计入） |
| `error` | String | 失败时的错误信息 |

**页面建议**

- 左侧文件列表，右侧代码编辑器（推荐使用 Monaco Editor / CodeMirror）
- 编辑流程：选择文件 → 加载内容 → 编辑 → 保存 → 重载生效
- 顶部工具栏：导出按钮（下载 ZIP）、导入按钮（上传 ZIP）、重载按钮
- 编辑器应开启 JSON 语法校验，防止格式错误
- 保存时后端也会校验 JSON 格式，若返回 `success: false` 应提示用户检查内容

**编辑器交互流程**

```
选择文件 ──→ GET /data/file?name=xxx ──→ 编辑器展示
                                              │
                                         用户编辑
                                              │
                                         保存按钮
                                              │
                              PUT /data/file { name, content }
                                              │
                                    ┌── success: true ──┐
                                    │                    │
                               提示"保存成功"       提示"JSON格式错误"
                                    │
                              POST /data/reload
                                    │
                              提示"已生效"
```

---

### 模型配置 — LLM 设置

#### 查看配置

```
GET /api/admin/llm/config
X-Admin-Token: <token>
```

```json
{
  "llm": {
    "baseUrl": "https://api.rinne.cyou/v1",
    "model": "deepseek-ai/deepseek-v4-flash",
    "apiKey": "sk-4****p7NY"
  },
  "auditLlm": {
    "baseUrl": "https://api.moonshot.cn/v1",
    "model": "kimi-k2.5",
    "apiKey": "sk-o****aVZR"
  }
}
```

| 字段 | 说明 |
|------|------|
| `llm` | 游戏叙事 LLM 配置 |
| `auditLlm` | 输入审核 LLM 配置 |
| `apiKey` | 返回时已脱敏（仅显示前4后4），提交时若传脱敏值则不更新 |

#### 修改配置

```
PUT /api/admin/llm/config
X-Admin-Token: <token>
Content-Type: application/json

{
  "type": "llm",
  "baseUrl": "https://api.rinne.cyou/v1",
  "apiKey": "sk-xxxx",
  "model": "deepseek-ai/deepseek-v4-flash"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `type` | 否 | `llm`（游戏叙事，默认）或 `audit`（审核） |
| `baseUrl` | 否 | API 地址，传空或不传则不更新 |
| `apiKey` | 否 | API 密钥，传空或不传则不更新 |
| `model` | 否 | 模型名称，传空或不传则不更新 |

```json
{
  "success": true
}
```

**页面建议**

- 分两组表单：游戏叙事 LLM / 审核LLM
- apiKey 输入框类型为 `password`，加载时显示脱敏值
- 仅修改有变化的字段，未修改的字段不传或传空
- 保存后即时生效，无需重启

---

### 系统操作 — 重启服务

```
POST /api/admin/restart
X-Admin-Token: <token>
```

```json
{
  "success": true,
  "message": "Server is restarting"
}
```

**页面建议**

- 二次确认弹窗："确定要重启游戏服务吗？重启期间所有玩家将断开连接。"
- 调用后显示"服务正在重启..."，前端轮询健康检查接口确认恢复

---

## 接口速查表

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| POST | `/api/admin/login` | 无 | 管理员登录 |
| GET | `/api/admin/admin/info` | Token | 查看管理员信息 |
| PUT | `/api/admin/admin/credentials` | Token | 修改管理员账号密码 |
| GET | `/api/admin/stats/players` | Token | 游玩人数统计 |
| GET | `/api/admin/stats/progress` | Token | 玩家进度统计 |
| GET | `/api/admin/feedbacks` | Token | 查看用户反馈(分页) |
| GET | `/api/admin/data/files` | Token | 列出数据文件 |
| GET | `/api/admin/data/file?name=xxx` | Token | 读取数据文件 |
| PUT | `/api/admin/data/file` | Token | 修改数据文件 |
| POST | `/api/admin/data/reload` | Token | 重载游戏数据 |
| GET | `/api/admin/data/export` | Token | 导出数据(ZIP) |
| POST | `/api/admin/data/import` | Token | 导入数据(ZIP) |
| GET | `/api/admin/llm/config` | Token | 查看 LLM 配置 |
| PUT | `/api/admin/llm/config` | Token | 修改 LLM 配置 |
| POST | `/api/admin/restart` | Token | 重启服务 |

---

## 错误处理

| HTTP 状态码 | 含义 | 前端处理 |
|-------------|------|----------|
| 200 | 成功 | 正常处理响应 |
| 400 | 请求参数错误 | 检查请求头/参数是否完整 |
| 401 | 未认证/Token无效 | 清除本地Token，跳转登录页 |
| 500 | 服务器内部错误 | 提示"操作失败，请稍后重试" |

401 响应体示例：

```json
{
  "timestamp": "2026-05-21T03:15:51.983+00:00",
  "status": 401,
  "error": "Unauthorized",
  "path": "/api/admin/stats/players"
}
```

---

## 前端技术建议

### 推荐技术栈

| 层面 | 推荐 | 备选 |
|------|------|------|
| 框架 | Vue 3 + Vite | React + Next.js |
| UI库 | Element Plus | Ant Design / Naive UI |
| 代码编辑器 | Monaco Editor | CodeMirror 6 |
| HTTP客户端 | Axios | fetch |
| 图表 | ECharts | Chart.js |

### 页面结构

```
/login              登录页
/dashboard          仪表盘（统计概览）
/feedbacks          用户反馈（建议列表）
/data               数据管理（脚本编辑/导入导出）
/settings           系统设置（LLM配置/重启）
```

### 布局建议

```
┌─────────────────────────────────────────────┐
│  顶部栏: YUZU ADMIN    [管理员] [退出登录]    │
├──────────┬──────────────────────────────────┤
│          │                                  │
│  侧边栏   │         主内容区                  │
│          │                                  │
│  仪表盘   │  根据路由切换:                     │
│  用户反馈  │  - 统计卡片/图表                   │
│  数据管理  │  - 反馈列表/分页                   │
│  系统设置  │  - 文件列表+编辑器                 │
│          │  - LLM配置表单                     │
│          │                                  │
└──────────┴──────────────────────────────────┘
```

### 跨域配置

如果前端与后端不同源，需在后端添加 CORS 配置。在 `application.yml` 同级或通过 Spring 配置类：

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*");
    }
}
```

### API 调用封装示例（Axios）

```javascript
import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8080/api/admin'
});

api.interceptors.request.use(config => {
  const token = localStorage.getItem('admin_token');
  if (token) config.headers['X-Admin-Token'] = token;
  return config;
});

api.interceptors.response.use(
  res => res.data,
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('admin_token');
      window.location.href = '/login';
    }
    return Promise.reject(err);
  }
);

export const adminApi = {
  login: (username, password) =>
    api.post('/login', { username, password }),

  getAdminInfo: () =>
    api.get('/admin/info'),

  updateAdminCredentials: (username, password) =>
    api.put('/admin/credentials', { username, password }),

  getPlayerStats: () =>
    api.get('/stats/players'),

  getProgressStats: () =>
    api.get('/stats/progress'),

  getFeedbackList: (page = 0, size = 20) =>
    api.get('/feedbacks', { params: { page, size } }),

  listDataFiles: () =>
    api.get('/data/files'),

  readDataFile: (name) =>
    api.get('/data/file', { params: { name } }),

  writeDataFile: (name, content) =>
    api.put('/data/file', { name, content }),

  reloadGameData: () =>
    api.post('/data/reload'),

  exportData: () =>
    api.get('/data/export', { responseType: 'blob' }),

  importData: (file) => {
    const formData = new FormData();
    formData.append('file', file);
    return api.post('/data/import', formData);
  },

  getLlmConfig: () =>
    api.get('/llm/config'),

  updateLlmConfig: (type, baseUrl, apiKey, model) =>
    api.put('/llm/config', { type, baseUrl, apiKey, model }),

  restart: () =>
    api.post('/restart')
};
```
