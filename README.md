# YUZU TEXT

> 多 AI Agent 协作驱动的文字冒险游戏引擎

**YUZU TEXT** 是一款开源的多 AI Agent 协作游戏引擎，专为构建非线性叙事的文字冒险游戏而设计。引擎核心特性：

- **多 Agent 协作架构** — 导演AI、主角AI、地图AI、NPC AI、谜题AI 五大 Agent 各司其职，通过权限矩阵和标签DSL协同驱动叙事
- **控制标签 DSL** — AI 通过 `<ctrl>` 标签块与引擎通信，实现叙事文本与游戏状态的彻底解耦
- **全配置驱动** — 地图、NPC、谜题、物品、结局、提示词、数值规则全部通过 JSON 配置，无需修改代码即可创建全新游戏
- **双层 LLM 安全** — 游戏叙事 LLM 与输入审核 LLM 完全隔离，防止提示词注入攻击
- **二级缓存架构** — Redis 活跃会话缓存 + MySQL 持久化，支持断线重连与会话恢复

***

## 目录

**引擎架构**

- [项目架构总览](#项目架构总览)
- [AI Agent 系统](#ai-agent-系统)
- [控制标签 DSL](#控制标签-dsl)
- [游戏主循环](#游戏主循环)
- [数据配置体系](#数据配置体系)
- [REST API](#rest-api)
- [安全机制](#安全机制)
- [部署与运行](#部署与运行)
- [自定义与扩展](#自定义与扩展)
- [技术栈](#技术栈)

**试玩游戏：千禧塔的低语**

- [游戏截图](#游戏截图)
- [核心机制：三维属性系统](#核心机制三维属性系统)
- [五章地图流程](#五章地图流程)
- [结局规则](#结局规则)

***

## 项目架构总览

```
┌─────────────────────────────────────────────────────────┐
│                 Controller 层 (Spring Boot)               │
│           GameController · FeedbackController · AdminController │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                  Service 层 (GameService)                  │
│     会话管理 · Redis/MySQL 二级缓存 · 兑换码逻辑           │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│              Engine 层 (GameEngine 核心)                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │DirectorAI│ │MapAI     │ │NpcAI     │ │PuzzleAI  │   │
│  │ 导演AI   │ │ 地图AI   │ │ NPC AI   │ │ 谜题AI   │   │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘   │
│  ┌────┴─────┐                                           │
│  │ProtagAI  │  ← 五个 Agent 共享 LlmService              │
│  │ 柚子AI   │                                           │
│  └──────────┘                                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │         GameStateManager (标签解析与权限校验)        │  │
│  └────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────┐  │
│  │         ConditionEvaluator (条件表达式求值)          │  │
│  └────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────┐  │
│  │         GameDataLoader (JSON 配置加载)               │  │
│  │  story · maps · npcs · puzzles · items · endings    │  │
│  │  protagonist · prompts · game_config · redemption    │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                  Model 层 (数据模型)                       │
│  Player · GameSession · MapConfig · NpcConfig · Puzzle   │
│  StoryConfig · EndingRuleConfig · ItemConfig · ...       │
└─────────────────────────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│              Repository 层 (持久化)                        │
│     GameSessionRepository (JPA) · Redis Template          │
└─────────────────────────────────────────────────────────┘
```

### 后端包结构

```
com.yuzugame/
├── agent/           # 五个 AI Agent
│   ├── DirectorAI       导演AI — 开场/阶段汇报/理智警告/结局判定
│   ├── MapAI            地图AI — 环境描写/物品发现/谜题激活
│   ├── NpcAI            NPC AI — 角色对话/第N次对话给道具
│   ├── ProtagonistAI    柚子AI — 陪伴回应/物品给予/好感度
│   └── PuzzleAI         谜题AI — 多轮解谜/成功失败判定/物品消耗
├── controller/      # REST API 入口
│   ├── GameController   游戏主接口 (new/action/state/redeem)
│   ├── FeedbackController 反馈接口
│   └── AdminController   管理端接口 (login/stats/data/llm/restart)
├── engine/          # 核心引擎
│   ├── GameEngine       游戏主循环 (10步流水线)
│   ├── GameStateManager 控制标签解析/权限校验/状态执行
│   ├── ConditionEvaluator 条件表达式求值器
│   └── GameDataLoader   JSON 配置加载器
├── model/           # 数据模型
│   ├── Player           玩家 (三维属性 + 背包 + 线索)
│   ├── GameSession      游戏会话 (完整运行时状态)
│   ├── MapConfig        地图配置
│   ├── NpcConfig        NPC 配置
│   ├── PuzzleConfig     谜题配置
│   ├── StoryConfig      故事/章节/理智衰减曲线
│   ├── EndingRuleConfig 结局规则
│   ├── ItemConfig       物品配置
│   ├── ProtagonistConfig 柚子配置
│   ├── PromptsConfig    AI提示词模板 (导演/地图/NPC/柚子/谜题/审核/兑换)
│   ├── GameConfig       游戏数值规则 (关键词/奖励/阈值/记忆限制)
│   └── RedemptionCodeConfig 兑换码配置
├── repository/      # 数据持久化
│   ├── GameSessionEntity    JPA 实体
│   ├── GameSessionRepository JPA 仓库
│   └── JsonConverters       JSON 类型转换器
└── service/         # 服务层
    ├── GameService       会话管理/缓存/兑换码
    ├── LlmService        LLM 通信层 (DeepSeek)
    ├── AuditLlmService   审核LLM通信层 (Kimi)
    ├── AdminService      管理端服务 (认证/统计/数据/LLM配置/重启)
    └── InputAuditor      输入审核 (提示词注入检测)
```

***

## AI Agent 系统

游戏由五个 AI Agent 协作驱动，每个 Agent 有独立的系统提示词、对话历史视角和标签权限。所有 Agent 的提示词模板均配置在 `prompts.json` 中，实现提示词与代码彻底分离：

### Agent 职责矩阵

| Agent             | 职责                  | 调用时机                | 对话历史视角          | prompts.json 节点 |
| ----------------- | ------------------- | ------------------- | --------------- | --------------- |
| **DirectorAI**    | 开场旁白、阶段汇报、理智警告、结局判定 | 每10回合 / 理智阈值 / 回合末尾 | 全局状态摘要          | `director`      |
| **ProtagonistAI** | 柚子陪伴回应、物品给予、好感度调整   | 每回合（NPC对话时跳过）       | 最近100条全类型消息     | `protagonist`   |
| **MapAI**         | 环境描写、物品发现、谜题激活      | 探索关键词 / 首次进入 / 地图切换 | 最近30条全类型消息      | `map`           |
| **NpcAI**         | NPC角色对话、第N次对话给道具    | `@NPC名 消息` 格式       | 最近30条全类型消息      | `npc`           |
| **PuzzleAI**      | 多轮解谜交互、成功/失败判定、前置物品消耗 | 有活跃谜题时每回合           | 谜题专属对话记忆(最多20轮) | `puzzle`        |

### Agent 权限矩阵

每个 Agent 只能输出其职责范围内的控制标签，防止越权操作：

| 标签类别                        | Director | Protagonist | Map | NPC | Puzzle |
| --------------------------- | -------- | ----------- | --- | --- | ------ |
| SANITY/REVELATION/AFFECTION | ✅        | ✅           | ✅   | ✅   | ✅      |
| ITEM:GIVE                   | ✅        | ✅           | ❌   | ✅   | ❌      |
| ITEM:FOUND                  | ❌        | ❌           | ✅   | ❌   | ❌      |
| ITEM:TAKE                   | ✅        | ❌           | ❌   | ❌   | ✅      |
| ITEM:CREATE/USE             | ✅        | ✅           | ❌   | ❌   | ❌      |
| NPC:UNLOCK                  | ❌        | ❌           | ✅   | ❌   | ✅      |
| NPC:KILL/REVIVE             | ✅        | ❌           | ❌   | ❌   | ❌      |
| PUZZLE:ACTIVATE             | ✅        | ❌           | ✅   | ❌   | ❌      |
| PUZZLE:SOLVE/FAIL           | ❌        | ❌           | ❌   | ❌   | ✅      |
| MAP/CHAPTER/ENDING          | ✅        | ❌           | ❌   | ❌   | ❌      |

### 对话历史映射

所有 Agent 共享同一个 `GameSession.chatHistory`，但映射为不同的 LLM 角色：

| 消息类型            | 柚子AI          | 地图AI          | NPC AI                       | 谜题AI          |
| --------------- | ------------- | ------------- | ---------------------------- | ------------- |
| PLAYER          | user          | user          | user                         | user          |
| PROTAGONIST\_AI | **assistant** | user【柚子】      | user【柚子】                     | user【柚子】      |
| MAP\_AI         | user【环境】      | **assistant** | user【环境】                     | user【环境】      |
| NPC\_AI         | user【NPC名】    | user【NPC名】    | **assistant**(自己) / user(其他) | user【NPC名】    |
| DIRECTOR\_AI    | user【导演旁白】    | user【导演旁白】    | user【导演旁白】                   | user【导演旁白】    |
| PUZZLE\_AI      | user【谜题】      | user【谜题】      | user【谜题】                     | **assistant** |

***

## 控制标签 DSL

AI Agent 通过控制标签（Game DSL）与游戏引擎通信，实现叙事与状态的解耦。

### 标签格式

```
CATEGORY:ACTION:PARAM
```

标签可以出现在两个位置：

1. **`<ctrl>...</ctrl>`** **块**（推荐格式，AI 应优先使用）
2. **裸标签**（兜底格式，当 AI 未使用 ctrl 块时自动提取）

AI 还可使用 `<internal>...</internal>` 块记录内部判断，该内容会被剥离，不展示给玩家。

### 完整标签列表

| 标签              | 格式                      | 作用          | 示例                                   |
| --------------- | ----------------------- | ----------- | ------------------------------------ |
| SANITY          | `SANITY:Δ`              | 修改理智值       | `SANITY:-3`                          |
| REVELATION      | `REVELATION:Δ`          | 修改揭露度       | `REVELATION:+5`                      |
| AFFECTION       | `AFFECTION:Δ`           | 修改柚子好感度     | `AFFECTION:+2`                       |
| NPC\_AFFECTION  | `NPC_AFFECTION:npcId:Δ` | 调整NPC对话计数   | `NPC_AFFECTION:npc_maintenance:+1`   |
| ITEM:GIVE       | `ITEM:GIVE:id:中文名称`     | NPC/柚子给玩家物品 | `ITEM:GIVE:item_sedative:镇定剂`        |
| ITEM:FOUND      | `ITEM:FOUND:id:中文名称`    | 场景中发现物品     | `ITEM:FOUND:rusty_key:生锈的钥匙`         |
| ITEM:TAKE       | `ITEM:TAKE:id`          | 从玩家移除物品     | `ITEM:TAKE:item_data_shard_1`        |
| ITEM:CREATE     | `ITEM:CREATE:id`        | 柚子制造物品      | `ITEM:CREATE:item_signal_jammer`     |
| ITEM:USE        | `ITEM:USE:id`           | 柚子消耗物品      | `ITEM:USE:item_sedative`             |
| NPC:UNLOCK      | `NPC:UNLOCK:id`         | 解锁NPC       | `NPC:UNLOCK:npc_maintenance`         |
| NPC:KILL        | `NPC:KILL:id`           | NPC死亡       | `NPC:KILL:npc_night_guard`           |
| NPC:REVIVE      | `NPC:REVIVE:id`         | NPC复活       | `NPC:REVIVE:npc_night_guard`         |
| PUZZLE:ACTIVATE | `PUZZLE:ACTIVATE:id`    | 激活谜题        | `PUZZLE:ACTIVATE:puzzle_sewer_pipes` |
| PUZZLE:SOLVE    | `PUZZLE:SOLVE:id`       | 谜题解决        | `PUZZLE:SOLVE:puzzle_sewer_pipes`    |
| PUZZLE:FAIL     | `PUZZLE:FAIL:id`        | 谜题失败        | `PUZZLE:FAIL:puzzle_sewer_pipes`     |
| MAP             | `MAP:mapId`             | 切换地图        | `MAP:map_millennium_1f`              |
| CHAPTER         | `CHAPTER:chapterId`     | 推进章节        | `CHAPTER:ch2`                        |
| ENDING          | `ENDING:type`           | 触发结局        | `ENDING:PERFECT`                     |
| EVENT           | `EVENT:eventId`         | 触发事件(预留)    | `EVENT:boss_appear`                  |

### 标签处理流程

```
AI 原始输出
    │
    ▼
extractTags() ─── 优先提取 <ctrl> 块中的标签
    │                └── 若 <ctrl> 块为空，回退到裸标签匹配
    ▼
executeTags() ─── 逐条执行
    │
    ├── hasPermission() ── 权限校验（按 Agent 类型）
    │
    └── switch(category) ── 分发到具体 handler
         ├── handleSanity()
         ├── handleRevelation()
         ├── handleItem()
         ├── handleNpc()
         ├── handlePuzzle()
         ├── handleMap()
         ├── handleChapter()
         ├── handleEnding()
         └── ...

stripInternal() ─── 剥离 <internal>/<ctrl>/裸标签，返回纯叙事文本给玩家
```

***

## 游戏主循环

`GameEngine.processMessage()` 是每回合的核心入口，按固定 10 步流水线调度：

```
玩家输入
    │
    ▼
[步骤0] 输入审核 (InputAuditor → Kimi K2.6 语义审核)
    │  检测提示词注入 → 拦截则返回游戏风格警告
    │  审核提示词和警告叙事配置在 prompts.json → auditor
    ▼
[步骤1] 回合计数 + 理智自然衰减
    │  turn++ → 根据 sanityDecayCurve 扣除理智
    ▼
[步骤2] 首次进入新地图 → 自动环境描写 (MapAI.autoDescribe)
    │  若玩家本轮探索则跳过，避免重复
    ▼
[步骤3] 柚子回应 (ProtagonistAI.respond)
    │  NPC对话时跳过，避免抢话
    ▼
[步骤4] 探索关键词匹配 → 地图AI环境描写
    │  ├── 出口已开+移动词 → 跳过（步骤8处理）
    │  ├── 谜题已激活 → 跳过（步骤6处理）
    │  ├── 地图AI描写 + 标签解析
    │  ├── 谜题兜底激活（同地图≥5回合）
    │  ├── 自动拾取物品（最多1个/次）
    │  ├── 档案馆额外+3揭露度
    │  └── 探索+1揭露度
    ▼
[步骤5] NPC交互 (@NPC名 消息)
    │  ├── 条件评估 → 自动解锁 + 理智+15
    │  ├── NPC AI 对话 + 标签解析
    │  ├── 第N次对话自动给道具 (兜底机制)
    │  └── 对话+1揭露度
    ▼
[步骤6] 活跃谜题处理 (PuzzleAI.handle)
    │  ├── 多轮解谜交互 + 玩家道具列表上下文
    │  ├── 解决后：清理记忆 + 揭露度/理智奖励
    │  └── 前置物品消耗（AI通过 ITEM:TAKE 标签触发）
    ▼
[步骤7] 每10回合导演阶段汇报 (DirectorAI.stageReport)
    ▼
[步骤8] 出口解锁与地图切换
    │  ├── 谜题解决 → 出口开启
    │  ├── 移动关键词 → 地图切换
    │  ├── 章节推进揭露度奖励
    │  └── 过渡描写 (MapAI.transitionDescribe)
    ▼
[步骤9] 理智阈值警告 (60/30/10，每个只触发一次)
    │  → DirectorAI.sanityWarning
    ▼
[步骤10] 结局判定 (DirectorAI.determineEndingAction)
    │  → 按 endings.json 优先级逐条检查
    └── 触发结局 → 生成结局旁白 + 柚子最终台词
```

### 关键词匹配

关键词均配置在 `game_config.json` 中，可自定义扩展：

| 类型    | 配置路径                | 关键词                                                            | 触发效果                    |
| ----- | ------------------- | -------------------------------------------------------------- | ----------------------- |
| 探索    | `exploreKeywords`   | 观察/看看/周围/环境/探索/调查/检查/触碰/操作/破解/查看/搜寻/翻找/审视/打量/深入/走去/走向/靠近/接近/进入 | 地图AI环境描写 + 自动拾取 + 揭露度奖励 |
| 移动    | `moveKeywords`      | 前进/移动/离开/前往/上去/下楼/上楼/进入/出发/通过/穿过/迈向/踏上/去往                      | 出口已开时触发地图切换             |
| NPC交互 | `npcMentionPattern` | `@NPC名 消息内容`                                                   | NPC对话 + 自动解锁 + 第N次给道具   |

***

## 数据配置体系

所有游戏内容通过 JSON 文件配置，无需修改 Java 代码即可调整游戏内容。提示词模板（`prompts.json`）和数值规则（`game_config.json`）已从代码中完全提取，实现内容与代码彻底分离：

### 配置文件一览

| 文件                      | 对应模型                 | 内容                                     |
| ----------------------- | -------------------- | -------------------------------------- |
| `story.json`            | StoryConfig          | 章节、理智衰减曲线、导演提示词、最大回合数                  |
| `maps.json`             | MapConfig            | 5张地图（名称/描述/氛围/谜题/物品/NPC/出口）            |
| `npcs.json`             | NpcConfig            | 10个NPC（性格/背景/说话风格/出现条件/知晓信息）           |
| `puzzles.json`          | PuzzleConfig         | 6道谜题（难度/成功条件/最大尝试/失败惩罚/系统提示词）          |
| `items.json`            | ItemConfig           | 20个物品（名称/类型/描述）                        |
| `protagonist.json`      | ProtagonistConfig    | 柚子角色设定（性格/背景/系统提示词/初始好感度）              |
| `endings.json`          | EndingRuleConfig     | 7条结局规则（类型/优先级/条件/逻辑/叙事提示）              |
| `prompts.json`          | PromptsConfig        | 所有AI Agent提示词模板（导演/地图/NPC/柚子/谜题/审核/兑换） |
| `game_config.json`      | GameConfig           | 游戏数值规则（关键词/奖励/阈值/记忆限制/输出前缀）            |
| `redemption_codes.json` | RedemptionCodeConfig | 兑换码（口令/奖励/使用次数/状态）                     |

### 条件表达式

NPC 出现条件、对话条件、地图解锁条件使用统一的条件表达式语法：

```
类型:值[:参数]
```

| 条件类型    | 格式                          | 示例                                                                      |
| ------- | --------------------------- | ----------------------------------------------------------------------- |
| 章节匹配    | `chapter:id`                | `chapter:ch1`                                                           |
| 回合数     | `turn:数值`                   | `turn:3`                                                                |
| 揭露度     | `revelation:数值`             | `revelation:70`                                                         |
| 谜题状态    | `puzzle:id:success\|failed` | `puzzle:puzzle_sewer_pipes:success`                                     |
| 条件或     | `条件1\|条件2`                  | `puzzle:puzzle_data_decrypt:success\|puzzle:puzzle_server_room:success` |
| 持有物品    | `item:id`                   | `item:item_data_shard_1`                                                |
| 揭露度阈值   | `revelation:N`              | `revelation:50`                                                         |
| 回合数阈值   | `turn:N`                    | `turn:3`                                                                |
| NPC对话次数 | `affection:npcId:N`         | `affection:npc_maintenance:3`                                           |

逻辑组合：`|` = OR（任一满足），`&` = AND（全部满足），优先级 AND > OR。

示例：`puzzle:puzzle_data_decrypt:success|puzzle:puzzle_server_room:success` 表示任一谜题解决即可。

***

## REST API

### 创建新游戏

```
POST /api/game/new
→ { sessionId, status: "opening", output: "导演开场白...", state: {...} }
```

### 提交玩家操作

```
POST /api/game/action
Body: { "sessionId": "xxx", "message": "我看看周围" }
→ { sessionId, output: "游戏输出...", state: {...} }
```

### 查询游戏状态

```
GET /api/game/state?sessionId=xxx
→ { sessionId, state: {...} }
```

### 兑换码

```
POST /api/game/redeem
Body: { "sessionId": "xxx", "code": "uzqueen" }
→ { success, message, output, code, description, applied: {...}, state: {...} }
```

### 提交反馈

```
POST /api/feedback/submit
Body: { "contact": "可选联系方式", "content": "反馈内容（必填，≤2000字）", "sessionId": "可选游戏会话ID" }
→ { ok: true/false, message/error: "..." }
```

### 管理端 API

所有管理端接口（除登录外）需在请求头携带 `X-Admin-Token`，通过登录接口获取。

**管理员登录**

```
POST /api/admin/login
Body: { "username": "admin", "password": "yuzu2024" }
→ { "success": true, "token": "abc123..." }
```

**查看管理员信息**

```
GET /api/admin/admin/info
Header: X-Admin-Token: <token>
→ { "admin": { "username": "admin" } }
```

**修改管理员账号密码**

修改后旧 Token 立即失效，需用新账号重新登录。凭证持久化到 `admin.json`，重启后仍有效：

```
PUT /api/admin/admin/credentials
Header: X-Admin-Token: <token>
Body: { "username": "newadmin", "password": "newpass123" }
→ { "success": true, "message": "Credentials updated, please login again" }
```

**查看游玩人数**

```
GET /api/admin/stats/players
Header: X-Admin-Token: <token>
→ { "totalPlayers": 128, "todayPlayers": 12, "thisMonthPlayers": 45 }
```

**查看玩家进度统计**

```
GET /api/admin/stats/progress
Header: X-Admin-Token: <token>
→ { "chapterDistribution": { "ch1": 30, "ch2": 25, "ch3": 15, "ch4": 8, "ch5": 3 }, "averageTurns": 23.5 }
```

**查看用户反馈建议**

分页查询，按时间倒序：

```
GET /api/admin/feedbacks?page=0&size=20
Header: X-Admin-Token: <token>
→ { "total": 4, "page": 0, "size": 20, "items": [{ "id": 1, "contact": "xxx", "content": "...", "sessionId": "...", "createdAt": "..." }] }
```

**列出数据文件**

```
GET /api/admin/data/files
Header: X-Admin-Token: <token>
→ { "files": ["endings.json", "game_config.json", "items.json", ...] }
```

**读取数据文件**

```
GET /api/admin/data/file?name=maps.json
Header: X-Admin-Token: <token>
→ { "name": "maps.json", "content": "[...]" }
```

**修改数据文件**

```
PUT /api/admin/data/file
Header: X-Admin-Token: <token>
Body: { "name": "maps.json", "content": "[...]" }
→ { "success": true }
```

**重载游戏数据**

修改数据文件后调用此接口使配置生效，无需重启服务：

```
POST /api/admin/data/reload
Header: X-Admin-Token: <token>
→ { "success": true, "message": "Game data reloaded" }
```

**导出游戏数据**

将所有游戏数据 JSON 文件打包为 ZIP 下载：

```
GET /api/admin/data/export
Header: X-Admin-Token: <token>
→ Content-Type: application/octet-stream
→ Content-Disposition: attachment; filename="game-data.zip"
→ ZIP 包含: endings.json, game_config.json, items.json, maps.json, npcs.json, prompts.json, protagonist.json, puzzles.json, redemption_codes.json, story.json
```

**导入游戏数据**

上传 ZIP 文件（与导出格式一致），覆盖数据目录中的 JSON 文件。导入后需调用重载接口使配置生效：

```
POST /api/admin/data/import
Header: X-Admin-Token: <token>
Content-Type: multipart/form-data
Body: file=<game-data.zip>
→ { "success": true, "importedFiles": 10 }
```

**查看 LLM 配置**

```
GET /api/admin/llm/config
Header: X-Admin-Token: <token>
→ { "llm": { "baseUrl": "...", "model": "...", "apiKey": "sk-4****7NY" }, "auditLlm": { "baseUrl": "...", "model": "...", "apiKey": "sk-o****VZR" } }
```

**修改 LLM 配置**

运行时动态更新，无需重启。`type` 为 `llm`（游戏叙事）或 `audit`（审核）：

```
PUT /api/admin/llm/config
Header: X-Admin-Token: <token>
Body: { "type": "llm", "baseUrl": "https://...", "apiKey": "sk-...", "model": "deepseek-..." }
→ { "success": true }
```

**重启游戏服务**

```
POST /api/admin/restart
Header: X-Admin-Token: <token>
→ { "success": true, "message": "Server is restarting" }
```

### 状态快照结构

```json
{
  "turn": 15,
  "chapter": "ch2",
  "mapId": "map_millennium_1f",
  "sanity": 72,
  "revelation": 23,
  "affection": 45,
  "backpack": [
    {"id": "item_pipe_sample", "name": "管道样本", "type": "QUEST", "description": "从下水道管道上取下的金属样本"},
    {"id": "item_frequency_recording", "name": "频率录音", "type": "MISC", "description": "深渊歌者歌声的录音片段"}
  ],
  "clues": [
    {"id": "item_npc_maintenance_gift", "name": "维修工的赠礼"}
  ],
  "solvedPuzzles": ["puzzle_sewer_pipes"],
  "failedPuzzles": [],
  "activePuzzleId": null,
  "exitUnlocked": false,
  "unlockedNpcs": ["npc_maintenance", "npc_abyss_singer"],
  "ended": false,
  "endingType": null,
  "score": 152,
  "npcNames": { "npc_maintenance": "维修工", ... },
  "puzzleNames": { "puzzle_sewer_pipes": "管道迷宫", ... },
  "mapNames": { "map_sewer_b1": "下水道", ... },
  "chapterNames": { "ch1": "管道之歌", ... },
  "mapNpcIds": { "map_sewer_b1": ["npc_maintenance", "npc_abyss_singer"], ... }
}
```

***

## 安全机制

### 双层 LLM 安全架构

```
玩家输入 ──→ InputAuditor (Kimi K2.6 语义审核)
                │
                ├── 通过 → 进入 GameEngine
                └── 拦截 → 返回游戏风格警告叙事
```

- **审核 LLM**（Kimi K2.6）与**游戏叙事 LLM**（DeepSeek）完全隔离
- 检测提示词注入、控制标签伪造、越狱尝试等攻击
- 拦截时返回符合游戏风格的洛夫克拉夫特式警告，保持沉浸感
- 审核失败时采用 fail-closed 策略（默认拦截）
- 审核系统提示词、警告叙事列表、故障关闭消息均配置在 `prompts.json → auditor` 中，可自定义审核规则和拦截文案

### 标签权限校验

`GameStateManager.hasPermission()` 确保每个 AI Agent 只能输出其职责范围内的标签，防止 LLM 越权操作（如 MapAI 输出 `ENDING:PERFECT`）。

***

## 部署与运行

### 环境要求

- Java 17+
- Maven 3.8+
- MySQL 8.0+
- Redis 6.0+

### 后端启动

```bash
# 1. 配置 application.yml (数据库、Redis、LLM API Key)
cp src/main/resources/application.yml ...

# 2. 编译运行
mvn spring-boot:run

# 后端默认运行在 http://localhost:8080
```

### 配置项

| 配置项                       | 说明              |
| ------------------------- | --------------- |
| `yuzu.llm.base-url`       | 游戏叙事 LLM API 地址 |
| `yuzu.llm.api-key`        | 游戏 LLM API 密钥   |
| `yuzu.llm.model`          | 游戏叙事模型名称        |
| `yuzu.audit-llm.base-url` | 审核 LLM API 地址   |
| `yuzu.audit-llm.api-key`  | 审核 LLM API 密钥   |
| `yuzu.audit-llm.model`    | 审核模型名称          |
| `yuzu.data-dir`           | 游戏数据文件目录（支持外部路径） |
| `yuzu.admin.username`     | 管理员用户名          |
| `yuzu.admin.password`     | 管理员密码           |
| `spring.datasource.*`     | MySQL 连接配置      |
| `spring.data.redis.*`     | Redis 连接配置      |

***

## 自定义与扩展

### 添加新地图

1. 在 `maps.json` 中添加地图配置
2. 在 `story.json` 的 `chapters` 中添加章节定义
3. 添加对应的谜题到 `puzzles.json`
4. 添加 NPC 到 `npcs.json`，设置 `appearCondition`
5. 修改前一张地图的 `nextMapId` 指向新地图
6. 如需调整章节揭露度奖励，在 `game_config.json` 的 `chapterRevelationRewards` 中添加

### 添加新 NPC

1. 在 `npcs.json` 中添加 NPC 配置（id、name、personality、background、dialogueStyle、knownInfo）
2. 在对应地图的 `npcIds` 中添加 NPC ID
3. 设置 `appearCondition`（如 `chapter:ch3`、`turn:10`、`revelation:70`）
4. 第N次对话自动给道具机制会自动生效（道具ID模板和触发阈值在 `game_config.json` 的 `npcGiftItemIdTemplate` / `npcGiftDialogueThreshold` 中配置）。AI通过 `ITEM:GIVE` 标签赠礼，若AI未生成则系统自动兜底发放

### 添加新谜题

1. 在 `puzzles.json` 中添加谜题配置
2. 设置 `difficulty`（1\~5，影响揭露度奖励）
3. 设置 `requiredItemId`（如需前置物品，解谜成功时AI须通过 `ITEM:TAKE` 标签消耗该物品）
4. 编写 `systemPrompt`（谜题AI的行为规则）
5. 在对应地图的 `puzzles` 数组中添加谜题 ID

### 添加新结局

1. 在 `endings.json` 中添加结局规则
2. 设置 `priority`（数字越小越优先）
3. 设置 `conditions`（字段路径 + 运算符 + 值）
4. 设置 `logic`（AND/OR）
5. 设置 `narrativeHint`（传给导演AI的叙事提示）
6. 无需修改 Java 代码

### 添加兑换码

在 `redemption_codes.json` 中添加：

```json
{
  "code": "口令文字",
  "description": "口令描述",
  "rewards": { "sanity": 10, "revelation": 5 },
  "maxUses": 1,
  "active": true
}
```

支持的奖励属性：`sanity`、`revelation`、`affection`（范围 \[0,100]）、`turn`（正数增加回合，负数减少回合，下限1）。

### 调整游戏参数

所有游戏数值参数均已配置化，修改对应 JSON 文件即可调整，无需改动 Java 代码：

| 参数          | 配置文件                               | JSON 路径                         | 默认值                                    |
| ----------- | ---------------------------------- | ------------------------------- | -------------------------------------- |
| 理智衰减速率      | `story.json`                       | `sanityDecayCurve`              | 1/2/3 三段递增                             |
| 谜题揭露度奖励     | `game_config.json`                 | `puzzleRewards.revelation`      | 难度1:5 \~ 难度4+:11                       |
| 谜题理智奖励      | `game_config.json`                 | `puzzleRewards.sanity`          | 各难度统一 +2                               |
| 章节揭露度奖励     | `game_config.json`                 | `chapterRevelationRewards`      | ch2:3 \~ ch5:10                        |
| NPC解锁理智奖励   | `game_config.json`                 | `npcUnlockSanityReward`         | 15                                     |
| 探索揭露度       | `game_config.json`                 | `explorationRevelationBonus`    | 1                                      |
| NPC对话揭露度    | `game_config.json`                 | `npcDialogueRevelationBonus`    | 1                                      |
| 档案馆额外揭露度    | `game_config.json`                 | `archiveRevelationBonus`        | 3                                      |
| 理智警告阈值      | `game_config.json`                 | `sanityWarningThresholds`       | \[60, 30, 10]                          |
| 谜题兜底激活回合    | `game_config.json`                 | `fallbackPuzzleActivationTurns` | 5                                      |
| 自动拾取上限      | `game_config.json`                 | `autoPickupLimit`               | 1                                      |
| 阶段汇报间隔      | `game_config.json`                 | `stageReportInterval`           | 10                                     |
| 谜题最少交互轮次基数  | `game_config.json`                 | `puzzleMinRoundsBase`           | 2                                      |
| 探索关键词       | `game_config.json`                 | `exploreKeywords`               | 观察\|看看\|...                            |
| 移动关键词       | `game_config.json`                 | `moveKeywords`                  | 前进\|移动\|...                            |
| NPC提及正则     | `game_config.json`                 | `npcMentionPattern`             | `@(\S+)\s+(.+)`                        |
| 起始地图        | `game_config.json`                 | `startingMapId`                 | `map_sewer_b1`                         |
| 起始章节        | `game_config.json`                 | `startingChapter`               | `ch1`                                  |
| NPC赠礼物品ID模板 | `game_config.json`                 | `npcGiftItemIdTemplate`         | `item_{npcId}_gift`                    |
| NPC赠礼触发阈值   | `game_config.json`                 | `npcGiftDialogueThreshold`      | 2                                      |
| 地图AI记忆限制    | `game_config.json`                 | `mapAiHistoryLimit`             | 30                                     |
| NPC AI记忆限制  | `game_config.json`                 | `npcAiHistoryLimit`             | 30                                     |
| 柚子AI记忆限制    | `game_config.json`                 | `protagonistAiHistoryLimit`     | 100                                    |
| 谜题AI记忆限制    | `game_config.json`                 | `maxPuzzleMemoryRounds`         | 20                                     |
| 输出前缀        | `game_config.json`                 | `outputPrefixes`                | 【环境】【柚子】...                            |
| 最大回合数       | `story.json`                       | `maxTurns`                      | 100                                    |
| 属性范围        | `Player.java`                      | setter 钳制                       | \[0, 100]                              |
| 属性初始值       | `protagonist.json` / `Player.java` | `initialAffection` / 字段声明       | sanity=100, revelation=0, affection=30 |
| AI提示词模板     | `prompts.json`                     | 各子节点                            | 详见 prompts.json                        |
| 导演AI系统提示词   | `story.json`                       | `directorSystemPrompt`          | 洛夫克拉夫特式恐怖叙事规则                          |

***

# 试玩游戏：千禧塔的低语

> 蔚蓝档案同人 · 洛夫克拉夫特式恐怖

引擎内置了一套完整的试玩游戏《千禧塔的低语》。玩家扮演老师，与花岗柚子一同深入千禧塔，调查来自地底的异常信号。五个章节、六道谜题、十位 NPC、七种结局——每一次游玩都是独一无二的叙事体验。

                                                        试玩地址：game.rinne.cyou

| <br />          | <br />                          |
| --------------- | ------------------------------- |
| **ch1 管道之歌**    | 下水道 · 管道迷宫 · 维修工 · 深渊歌者         |
| **ch2 暗夜前厅**    | 一楼大厅 · 安保终端 · 夜班保安 · 迷路的学生      |
| **ch3 数据低语**    | 数据中心 · 数据解密+服务器房 · 数据回声 · 系统管理员 |
| **ch4 封印之间**    | 中央实验室 · 实验室实验 · 实验体零号 · 典狱官     |
| **ch5 不可名状之真相** | 档案馆 · 档案馆真相 · 档案管理员 · 深渊投影      |

### 游戏截图

**桌面端**

| <br />                            | <br />                            |
| --------------------------------- | --------------------------------- |
| ![桌面端-1](docs/screenshots/p1.png) | ![桌面端-2](docs/screenshots/p2.png) |
| ![桌面端-3](docs/screenshots/p3.png) | ![桌面端-4](docs/screenshots/p4.png) |

**移动端**

| <br />                            | <br />                            |
| --------------------------------- | --------------------------------- |
| ![移动端-1](docs/screenshots/m1.png) | ![移动端-2](docs/screenshots/m2.png) |
| ![移动端-3](docs/screenshots/m3.png) | ![移动端-4](docs/screenshots/m4.png) |

> 以下内容描述《千禧塔的低语》的具体机制与数值。这些内容均通过 JSON 配置文件定义，可替换为任意其他游戏主题。

## 核心机制：三维属性系统

玩家拥有三个核心属性，取值范围均为 **\[0, 100]**，由 `Player` 类的 setter 自动钳制：

| 属性                   | 初始值 | 作用              | 衰减/增长方式                          |
| -------------------- | --- | --------------- | -------------------------------- |
| **理智 (sanity)**      | 100 | 降至 0 触发 FAIL 结局 | 每回合自然衰减（曲线递增）；谜题失败惩罚             |
| **揭露度 (revelation)** | 0   | 达到阈值触发结局        | 探索+1、NPC对话+1、解谜+5\~11、章节推进+3\~10 |
| **好感度 (affection)**  | 30  | 影响隐藏/秘密结局       | 柚子交互、AI 标签调整                     |

### 理智衰减曲线

由 `story.json → sanityDecayCurve` 配置，模拟逐渐加速的理智恶化：

| 回合区间     | 每回合衰减 |
| -------- | ----- |
| 1 \~ 29  | -1    |
| 30 \~ 59 | -2    |
| 60 \~ 99 | -3    |

### 属性奖励表

**谜题解决奖励**（按难度递增）：

| 难度 | 揭露度 | 理智 |
| -- | --- | -- |
| 1  | +5  | +2 |
| 2  | +7  | +2 |
| 3  | +9  | +2 |
| 4  | +11 | +2 |
| 5+ | +11 | +2 |

**章节推进奖励**（进入新章节一次性获得）：

| 章节  | 揭露度 |
| --- | --- |
| ch2 | +3  |
| ch3 | +5  |
| ch4 | +7  |
| ch5 | +10 |
| 其他  | +2  |

**其他属性变化来源**：

| 触发条件       | 属性变化               |
| ---------- | ------------------ |
| 探索（每次）     | 揭露度 +1             |
| NPC 对话（每次） | 揭露度 +1             |
| NPC 解锁（首次） | 理智 +15             |
| 档案馆探索（每次）  | 揭露度 +3（额外）         |
| 理智阈值警告     | 60/30/10 各触发一次导演旁白 |

### 总分计算

```
总分 = sanity + revelation + affection + 存活NPC数 × 10
```

### 五章地图流程

```
ch1: 管道之歌 ─── map_sewer_b1 (下水道)
  │  谜题: 管道迷宫 (难度1)
  │  NPC: 维修工、深渊歌者
  ▼
ch2: 暗夜前厅 ─── map_millennium_1f (一楼大厅)
  │  谜题: 安保终端 (难度2)
  │  NPC: 夜班保安、迷路的学生
  ▼
ch3: 数据低语 ─── map_data_center_5f (数据中心)
  │  谜题: 数据解密(难度3) + 服务器房(难度2)
  │  NPC: 数据回声、系统管理员
  ▼
ch4: 封印之间 ─── map_central_lab_9f (中央实验室)
  │  谜题: 实验室实验 (难度4)
  │  NPC: 实验体零号、典狱官
  ▼
ch5: 不可名状之真相 ─ map_archive_10f (档案馆)
     谜题: 档案馆真相 (难度5)
     NPC: 档案管理员、深渊投影
```

### 结局规则

结局按 `priority` 顺序检查，首次匹配即触发：

| 优先级 | 类型      | 条件                          | 叙事提示    |
| --- | ------- | --------------------------- | ------- |
| 1   | FAIL    | 理智 ≤ 0                      | 陷入疯狂    |
| 2   | HIDDEN  | ch5 + 好感≥80 + 理智<60 + 揭露<60 | 与柚子一起迷失 |
| 3   | SECRET  | ch5 + 好感≥80                 | 羁绊超越真相  |
| 4   | PERFECT | ch5 + 揭露≥100                | 彻悟一切    |
| 5   | FINAL   | ch5 + 揭露≥60                 | 遗憾与希望   |
| 6   | FINAL   | 回合≥100 + 总分≥150             | 遗憾与希望   |
| 7   | FAIL    | 回合≥100 + 总分<150             | 迷失与崩溃   |

***

## 技术栈

### 后端

| 技术                | 用途            |
| ----------------- | ------------- |
| Spring Boot 3.2   | Web 框架        |
| Spring Data JPA   | MySQL 持久化     |
| Spring Data Redis | 一级缓存 (TTL 2h) |
| MySQL 8           | 持久存储          |
| Jackson           | JSON 序列化      |
| JDK HttpClient    | LLM API 通信    |

### AI 模型

| 模型               | 用途               |
| ---------------- | ---------------- |
| DeepSeek-V4Flash | 游戏叙事 (5个Agent共用) |
| Kimi K2.6        | 输入安全审核           |

***

## License

Apache License 2.0
