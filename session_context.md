# Minecraft 自动化 Mod — 会话上下文 / 更新日志

## 项目概况
Fabric 客户端 Mod，直接 HTTP 调用 DeepSeek/MiMo API 做任务决策，无需 Python 协调器，零网络穿透需求。

**MC 版本**: 1.21.11（编译目标 1.21.1，API 兼容）
**加载器**: Fabric (>=0.19.3)
**服务端**: 生存服务器，无 /tp 权限
**传送方式**: /res tp、/cd 面板、/tpa（LLM 根据上下文选择）
**架构**: Mod → HTTP → DeepSeek/MiMo API（直连，无中间层）

## 完成状态 (2026-07-26)

### ✅ 构建系统
- `settings.gradle` — Fabric + Gradle 8.10
- `build.gradle` — Loom 1.8.13, Java 21, 无外部依赖（JDK HttpClient）
- `gradle.properties` — MC 1.21.1, Fabric 0.102.0, Yarn 1.21.1+build.3
- `fabric.mod.json` — 声明 minecraft 1.21.11 + fabricloader >=0.19.3
- Gradle Wrapper — 8.10 (Alibaba 镜像)
- `build.gradle` — 去掉了 splitEnvironmentSourceSets（纯客户端 Mod）

### ✅ Java 源文件 (8个, 1286行)

| 文件 | 行数 | 功能 |
|---|---|---|
| `AutoMinerMod.java` | 222 | Mod 入口 + 主循环 + LLM 指令调度 |
| `network/LLMClient.java` | 132 | HTTP 调用 DeepSeek/MiMo (OpenAI 兼容 API) |
| `player/PlayerController.java` | 128 | 视角控制、移动、跳跃、潜行 |
| `player/Pathfinder.java` | 194 | 地面寻路 (A*) + 逐段推进 |
| `player/BlockBreaker.java` | 126 | 自动挖掘 (attackBlock + updateBlockBreakingProgress) |
| `player/BlockPlacer.java` | 145 | 按物品 ID 选背包 → 放置 |
| `chat/ChatCommandHandler.java` | 131 | 发送聊天命令 + 监听回复 |
| `inventory/InventoryManager.java` | 208 | 背包扫描、查找、耐久检测、容量检查 |

### ✅ LLM 指令协议
`MOVE_TO:x,y,z`、`MINE:x,y,z`、`PLACE:blockId,x,y,z`、`CHAT:/cmd`、`LOOK_AT:x,y,z`、`WAIT:N`、`TASK:desc`、`STOP`

### ✅ 构建验证
- `./gradlew build --no-daemon` → **BUILD SUCCESSFUL in 19s**
- 产物：`build/libs/auto-miner-0.1.0.jar` (26KB)
- 运行时配置：`LLM_API_KEY` + `LLM_MODEL` + `LLM_API_URL` 环境变量

### ⏳ 待做
- Litematica 蓝图读取 + 解析（`schematic/SchematicReader.java`）
- 自动箱子存取（screen handler 交互）
- 工具耐久低于阈值自动回城/合成
- 完整的 "徒手→钻石" 发展流水线
- 异常处理（掉线、卡住、背包满）
