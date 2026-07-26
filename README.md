# Minecraft Auto Miner

一个客户端 Fabric Mod，自动在生存服务器中挖矿、建造、发展。Mod 直接通过 HTTP 调用 DeepSeek/MiMo 等 AI 模型做决策，不需要 Python 协调器，零网络穿透需求。

## 工作原理

```
你的电脑 (MC)
┌─────────────────────────────┐     HTTPS        ┌──────────────────┐
│ Fabric Mod                  │ ───────────────►  │ DeepSeek / MiMo  │
│                             │                   │                  │
│ 每 0.5 秒: 读取状态 →       │                   │  返回下一条指令   │
│ 调用 LLM → 执行指令          │ ◄────────────────  │                  │
└─────────────────────────────┘                   └──────────────────┘
```

Mod 每 10 tick (0.5 秒) 向 LLM 发送当前状态（坐标、血量、饥饿、手持物品耐久、背包物品），LLM 返回一条指令，Mod 执行。

## 安装

1. **下载**: 从 [Releases](https://github.com/alsunmengy/minecraft-auto-miner/releases) 下载最新 `.jar`
2. **放入**: 复制到 `.minecraft/mods/` 文件夹
3. **启动**: 确保已安装 Fabric Loader 0.19.3+ 和 Fabric API

## 配置

通过**环境变量**配置 LLM：

```bash
# 必填 — 你的 API Key
LLM_API_KEY=sk-your-key-here

# 模型 (默认 deepseek-chat)
LLM_MODEL=deepseek-chat

# API 地址 (默认 DeepSeek)
LLM_API_URL=https://api.deepseek.com/v1/chat/completions
```

在 HMCL/PCL 等启动器中，在「版本设置」→「自定义参数」或「JVM 参数」中添加：

```
-DLLM_API_KEY=sk-your-key -DLLM_MODEL=deepseek-chat
```

或在启动命令前 export：

```bash
LLM_API_KEY=sk-xxx LLM_MODEL=deepseek-chat java -jar ...
```

### 支持的模型

| 模型 | API URL | 推荐 |
|------|---------|------|
| DeepSeek Chat | `https://api.deepseek.com/v1/chat/completions` | ✅ 默认 |
| DeepSeek Reasoner | `https://api.deepseek.com/v1/chat/completions` | 复杂决策 |
| MiMo | `https://api.mimo.com/v1` | 备用 |
| 任何 OpenAI 兼容 API | 你的 API 地址 | 通用 |

## LLM 指令

LLM 每次返回一条指令，格式 `指令:参数`：

| 指令 | 格式 | 示例 | 说明 |
|------|------|------|------|
| MOVE_TO | `MOVE_TO:x,y,z` | `MOVE_TO:100,64,200` | 寻路到坐标 |
| MINE | `MINE:x,y,z` | `MINE:105,63,198` | 挖掘指定方块 |
| PLACE | `PLACE:blockId,x,y,z` | `PLACE:oak_planks,100,64,200` | 放置方块 |
| CHAT | `CHAT:/command` | `CHAT:/res tp home` | 发送聊天/命令 |
| CRAFT | `CRAFT:item` | `CRAFT:stone_pickaxe` | 合成物品 |
| BUILD | `BUILD:name` | `BUILD:my_house` | 开始建造蓝图 |
| LIST_SCHEMATICS | `LIST_SCHEMATICS` | `LIST_SCHEMATICS` | 列出可用蓝图 |
| LOOK_AT | `LOOK_AT:x,y,z` | `LOOK_AT:100,64,200` | 看向坐标 |
| TASK | `TASK:desc` | `TASK:Mine 10 iron ore` | 设定当前任务 |
| WAIT | `WAIT:ticks` | `WAIT:20` | 等待 (20 tick = 1秒) |
| STOP | `STOP` | `STOP` | 停止自动化 |

## 自动发展流水线

LLM 的 System Prompt 指导五阶段发展：

1. **徒手→木** — 徒手撸树 → 合成木板/木棍/工作台
2. **木→石** — 合成木镐 → 挖圆石 → 石工具
3. **石→铁** — 挖铁矿 → 熔炼 → 铁工具
4. **铁→钻石** — 挖钻石 → 钻石工具
5. **建造** — 收集材料 → 按蓝图建造

工具耐久低于 20% 时，LLM 会自动决策回城合成/修理。

## 传送支持

服务器无 `/tp` 权限时，LLM 会根据情况选择：
- `/res tp home` — 回城
- `/res tp <name>` — 传送到其他领地
- `/cd` — 打开服务器面板
- `/tpa <player>` — 请求传送到玩家

## 蓝图 (Litematica)

将 `.litematic` 文件放入 `.minecraft/schematics/` 目录，LLM 可：
- 列出可用蓝图
- 解析材料清单
- 分阶段采集材料
- 按坐标逐块建造

## 从源码构建

```bash
# 环境要求: JDK 21+, Gradle 8.10+
cd mod
./gradlew build
# 产物: build/libs/auto-miner-0.3.0.jar
```

## 文件结构

```
mod/src/main/java/com/nous/autominer/
├── AutoMinerMod.java          # 入口 + 主循环 + 指令调度
├── network/
│   └── LLMClient.java         # HTTP 调用 LLM API
├── player/
│   ├── PlayerController.java  # 视角控制、移动
│   ├── Pathfinder.java        # 地面寻路 (A*)
│   ├── BlockBreaker.java      # 自动挖掘
│   └── BlockPlacer.java       # 方块放置
├── chat/
│   └── ChatCommandHandler.java # 聊天命令 + 监听回复
├── inventory/
│   └── InventoryManager.java  # 背包扫描、耐久检测
└── schematic/
    └── SchematicReader.java   # 读取 .litematic 蓝图
```

## 常见问题

**Q: 启动时崩溃 "Incompatible mods found"**
A: 确保下载的是最新版本，检查 fabric.mod.json 中声明为 `minecraft: 1.21.11` + `fabricloader: >=0.19.3`

**Q: Mod 不执行任何操作**
A: 检查环境变量 `LLM_API_KEY` 是否设置正确，查看游戏日志是否有 "Auto Miner initialized"

**Q: 如何提交错误报告？**
A: 将 `.minecraft/crash-reports/` 或启动器导出的崩溃 zip 文件提交到 GitHub Issues
