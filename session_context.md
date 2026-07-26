# Minecraft 自动化 Mod — 会话上下文 / 更新日志

## 项目概况
Fabric 客户端 Mod，直接 HTTP 调用 DeepSeek/MiMo API 做任务决策，零 Python 协调器，零网络穿透。
**MC 版本**: 1.21.11（当前编译目标 1.21.1 → 待升级）
**加载器**: Fabric (>=0.19.3)
**服务端**: 生存服务器，无 /tp
**传送**: `/res tp <领地>`（无 home 参数）
**架构**: Mod → HTTP → DeepSeek/MiMo API

---

## ✅ 当前已完成 (v0.4.2)

### 构建系统
- `build.gradle` — Loom 1.8.13, Java 21
- `gradle.properties` — MC 1.21.1（待改为 1.21.11）
- `fabric.mod.json` — 声明 minecraft 1.21.11 + fabricloader >=0.19.3

### Java 文件 (9个, ~1700行)

| 文件 | 行数 | 功能 |
|---|---|---|
| `AutoMinerMod.java` | ~554 | 入口 + 主循环 + 指令调度 + 命令注册 |
| `network/LLMClient.java` | 151 | HTTP 调用 DeepSeek/MiMo |
| `player/PlayerController.java` | 128 | 视角控制 |
| `player/Pathfinder.java` | 194 | 直线插值伪A*寻路 |
| `player/BlockBreaker.java` | 126 | attackBlock + 持续挖掘 |
| `player/BlockPlacer.java` | 145 | 按物品ID选热键栏放置 |
| `chat/ChatCommandHandler.java` | 131 | 聊天命令 + 监听回复 |
| `inventory/InventoryManager.java` | 208 | 背包扫描、耐久检测 |
| `schematic/SchematicReader.java` | 228 | .litematic NBT 解析 |

### 游戏内命令
- `/am start [蓝图名]` — 启动（可选指定蓝图）
- `/am stop` — 停止
- `/am status` — 查看状态
- `/am schematics` — 列出蓝图（编号+文件名）
- `/am choose <编号/文件名>` — 选蓝图看材料清单（支持Tab补全）
- `/am task <描述>` — 设置任务

### 功能
- ✅ LLM 每 3 秒查询（空闲时），动作完成后报告结果
- ✅ 工具耐久监控 + 状态报告中
- ✅ 附近方块扫描（5格半径，11×7×11范围）
- ✅ 动作完成检测（busy→idle 时记录上次结果）
- ✅ 选择时自动更新 `selectedSchematic`

---

## 📋 已确认重构计划（待执行）

### 阶段一：编译目标升级 + 崩溃修复

| 优先级 | 问题 | 改法 | 文件 |
|--------|------|------|------|
| 🔴 H1 | 编译 1.21.1 跑 1.21.11 → 中间映射崩溃 | Loom 1.13.6 + Gradle 8.14 + 1.21.11 mappings | `build.gradle`, `gradle.properties` |
| 🔴 H2 | `handleCommand` 中 `start` 分支重复 | 合并两个分支，统一用 `selectedSchematic` | `AutoMinerMod.java:219~327` |
| 🔴 H3 | `InventoryManager.getItem().getName()` 必崩溃 | `Registries.ITEM.getId()` 替换 | `InventoryManager.java:174` |
| 🔴 H4 | LLM 看不见世界 | prompt 加入"附近方块" + `/res tp` 改描述(无home) | `LLMClient.java:31~68` |

### 阶段二：交互能力

| 优先级 | 问题 | 改法 | 文件 |
|--------|------|------|------|
| 🟡 M1 | 生存模式无法用背包物品放方块 | 打开背包 ScreenHandler 换物品 | `BlockPlacer.java` |
| 🟡 M4 | 无法右键打开工作台/熔炉 | 新增 `INTERACT:x,y,z` 指令 + `BlockInteractor.java` | 新文件 + 指令路由 |

### 阶段三：寻路优化

| 优先级 | 问题 | 改法 |
|--------|------|------|
| 🟢 M3 | Pathfinder 不绕障碍 | 加 Bresenham 射线检测 + 侧向偏移 |

---

## ⚠️ 已知问题（待开工）

- `System.getenv()` 不能在启动器 JVM 参数中生效 → 需要改用 `System.getProperty()`
- 命令注册中 `SuggestionProvider` 每次扫描文件系统
- 方块扫描 847 次/3秒，可优化缓存
- 无异常恢复（掉线/卡住/背包满无处理）
- 无箱子存取（ScreenHandler 交互）
- 通知：`/res tp` 不带 home
- 用户默认模型：`deepseek-v4-flash`
