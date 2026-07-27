package com.nous.autominer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import com.nous.autominer.network.LLMClient;
import com.nous.autominer.player.PlayerController;
import com.nous.autominer.player.BlockBreaker;
import com.nous.autominer.player.BlockPlacer;
import com.nous.autominer.player.Pathfinder;
import com.nous.autominer.chat.ChatCommandHandler;
import com.nous.autominer.inventory.InventoryManager;
import com.nous.autominer.schematic.SchematicReader;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Auto Miner — AI-powered Minecraft automation mod.
 */
public class AutoMinerMod implements ClientModInitializer {
    public static final String MOD_ID = "auto-miner";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Subsystems
    private static LLMClient llmClient;
    private static PlayerController playerController;
    private static Pathfinder pathfinder;
    private static BlockBreaker blockBreaker;
    private static BlockPlacer blockPlacer;
    private static ChatCommandHandler chatHandler;
    private static InventoryManager inventoryManager;

    // State
    private static boolean initialized = false;
    private static boolean autoMode = false;
    private static String currentTask = "";
    private static int llmCooldown = 0;
    private static boolean busy = false;
    private static final int LLM_INTERVAL_TICKS = 60;
    private static String availableSchematics = "";
    private static String lastActionResult = "";
    private static String lastInstruction = "";
    private static boolean wasBusy = false;
    private static String selectedSchematic = "";

    @Override
    public void onInitializeClient() {
        LOGGER.info("Auto Miner initializing...");

        // Create subsystems
        playerController = new PlayerController();
        pathfinder = new Pathfinder();
        blockBreaker = new BlockBreaker();
        blockPlacer = new BlockPlacer();
        chatHandler = new ChatCommandHandler();
        inventoryManager = new InventoryManager();

        // Read LLM config from env
        String apiUrl = System.getenv().getOrDefault("LLM_API_URL",
                "https://api.deepseek.com/v1/chat/completions");
        String apiKey = System.getenv().getOrDefault("LLM_API_KEY", "");
        String model = System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash");
        llmClient = new LLMClient(apiUrl, apiKey, model);

        initialized = true;
        LOGGER.info("Auto Miner initialized. LLM: {} at {}", model, apiUrl);

        // Cache schematics list
        scanSchematics(MinecraftClient.getInstance());

        // Main tick — monitors busy state and calls LLM when idle
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            if (!autoMode) return;

            // Track busy state and detect when an action completes
            boolean isBusy = pathfinder.isActive() || blockBreaker.isBreaking();
            if (wasBusy && !isBusy && !lastInstruction.isEmpty()) {
                // Action just completed!
                if (lastInstruction.startsWith("MOVE_TO:")) {
                    lastActionResult = "✓ 到达目标位置";
                } else if (lastInstruction.startsWith("MINE:")) {
                    lastActionResult = "✓ 方块已挖掘";
                } else if (lastInstruction.startsWith("PLACE:")) {
                    lastActionResult = "✓ 方块已放置";
                } else if (lastInstruction.startsWith("CHAT:")) {
                    lastActionResult = "✓ 命令已发送";
                } else {
                    lastActionResult = "✓ 动作完成";
                }
                LOGGER.info("Action completed: {}", lastActionResult);
            }
            busy = isBusy;
            wasBusy = isBusy;

            // Only call LLM when NOT busy and cooldown expired
            if (busy) {
                // Let subsystems tick (Pathfinder advances, BlockBreaker breaks)
                return;
            }

            if (llmCooldown > 0) {
                llmCooldown--;
                return;
            }

            try {
                tick(client);
                llmCooldown = LLM_INTERVAL_TICKS; // 3s cooldown after each call
            } catch (Exception e) {
                LOGGER.error("Error in automation tick", e);
                autoMode = false;
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§c[AutoMiner] §f发生错误，已停止: " + e.getMessage()), false);
                }
            }
        });

        // Listen for server chat messages (teleport confirmations, errors, etc.)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (chatHandler != null) {
                chatHandler.onChatMessage(message.getString());
            }
        });

        // Register /am as a proper client-side command
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            SuggestionProvider<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> schemProvider =
                    (context, builder) -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc == null || mc.runDirectory == null) return builder.buildFuture();
                        File dir = new File(mc.runDirectory, "schematics");
                        String[] files = dir.list((d, name) -> name.endsWith(".litematic"));
                        if (files != null) {
                            for (String f : files) {
                                builder.suggest(f);
                            }
                        }
                        return builder.buildFuture();
                    };

            dispatcher.register(ClientCommandManager.literal("am")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal("§e[AutoMiner] §f命令: start [蓝图] | stop | status | schematics | choose <蓝图> | task <描述>"));
                        return 1;
                    })
                    .then(ClientCommandManager.literal("start")
                            .executes(context -> {
                                handleCommand(MinecraftClient.getInstance(), "start");
                                return 1;
                            })
                            .then(ClientCommandManager.argument("blueprint", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                    .suggests(schemProvider)
                                    .executes(context -> {
                                        String bp = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "blueprint");
                                        handleCommand(MinecraftClient.getInstance(), "start " + bp);
                                        return 1;
                                    }))
                    )
                    .then(ClientCommandManager.literal("stop")
                            .executes(context -> {
                                handleCommand(MinecraftClient.getInstance(), "stop");
                                return 1;
                            })
                    )
                    .then(ClientCommandManager.literal("status")
                            .executes(context -> {
                                handleCommand(MinecraftClient.getInstance(), "status");
                                return 1;
                            })
                    )
                    .then(ClientCommandManager.literal("schematics")
                            .executes(context -> {
                                handleCommand(MinecraftClient.getInstance(), "schematics");
                                return 1;
                            })
                    )
                    .then(ClientCommandManager.literal("choose")
                            .then(ClientCommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                    .suggests(schemProvider)
                                    .executes(context -> {
                                        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "name");
                                        handleCommand(MinecraftClient.getInstance(), "choose " + name);
                                        return 1;
                                    }))
                    )
                    .then(ClientCommandManager.literal("task")
                            .then(ClientCommandManager.argument("description", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                    .executes(context -> {
                                        String desc = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "description");
                                        handleCommand(MinecraftClient.getInstance(), "task " + desc);
                                        return 1;
                                    }))
                    )
            );
        });
    }

    /**
     * Handle in-game /am commands.
     */
    private void handleCommand(MinecraftClient client, String args) {
        if (client.player == null) return;

        if (args.equals("start") || args.equals("on")) {
            autoMode = true;
            busy = false;
            llmCooldown = 0;
            String task;
            if (!selectedSchematic.isEmpty()) {
                task = "Build: " + selectedSchematic;
                client.player.sendMessage(Text.literal("§a[AutoMiner] §f自动化已启动，目标蓝图: " + selectedSchematic), false);
            } else if (!currentTask.isEmpty()) {
                task = currentTask;
                client.player.sendMessage(Text.literal("§a[AutoMiner] §f自动化已启动，任务: " + currentTask), false);
            } else {
                task = "";
                client.player.sendMessage(Text.literal("§a[AutoMiner] §f自动化已启动，当前无指定任务，将自由探索"), false);
            }
            currentTask = task;
            LOGGER.info("Auto mode started. Task: {}", task);
        } else if (args.startsWith("start ")) {
            // /am start <blueprint_name> — start with a specific blueprint
            String name = args.substring(6).trim();
            selectedSchematic = name;
            autoMode = true;
            busy = false;
            llmCooldown = 0;
            currentTask = "Build: " + name;
            client.player.sendMessage(Text.literal("§a[AutoMiner] §f自动化已启动，目标蓝图: " + name), false);
            LOGGER.info("Auto mode started with blueprint: {}", name);
        } else if (args.equals("stop") || args.equals("off")) {
            autoMode = false;
            pathfinder.stop(client.player);
            blockBreaker.stop();
            currentTask = "";
            client.player.sendMessage(Text.literal("§c[AutoMiner] §f自动化已停止"), false);
            LOGGER.info("Auto mode stopped via command");
        } else if (args.equals("status")) {
            String status = autoMode ? "§a运行中" : "§c已停止";
            String task = currentTask.isEmpty() ? "无" : currentTask;
            String llmOk = llmClient.isConfigured() ? "§a已配置" : "§c未配置Key";
            String sChems = String.join(", ", scanSchematics(client));
            if (sChems.isEmpty()) sChems = "无";
            String sel = selectedSchematic.isEmpty() ? "无" : selectedSchematic;
            client.player.sendMessage(Text.literal(String.format(
                    "§e[AutoMiner] §f状态: %s | 任务: %s | LLM: %s | 模型: %s | 已选蓝图: %s",
                    status, task, llmOk, llmClient.getModel(), sel)), false);
        } else if (args.equals("schematics")) {
            List<String> schematics = scanSchematics(client);
            if (schematics.isEmpty()) {
                client.player.sendMessage(Text.literal("§e[AutoMiner] §f没有找到 .litematic 蓝图文件"), false);
            } else {
                client.player.sendMessage(Text.literal("§e[AutoMiner] §f可用蓝图(输入编号或全名):"), false);
                for (int i = 0; i < schematics.size(); i++) {
                    String marker = schematics.get(i).equals(selectedSchematic) ? " §a← 已选" : "";
                    client.player.sendMessage(Text.literal("§7  " + (i + 1) + ". §f" + schematics.get(i) + marker), false);
                }
            }
        } else if (args.startsWith("choose ")) {
            String input = args.substring(7).trim();
            List<String> schematics = scanSchematics(client);
            String targetName = null;
            try {
                int idx = Integer.parseInt(input) - 1;
                if (idx >= 0 && idx < schematics.size()) {
                    targetName = schematics.get(idx);
                } else {
                    client.player.sendMessage(Text.literal("§c[AutoMiner] §f编号超出范围(1-" + schematics.size() + ")"), false);
                    return;
                }
            } catch (NumberFormatException e) {
                targetName = input;
            }
            if (targetName == null) return;
            File schematicsDir = new File(client.runDirectory, "schematics");
            SchematicReader reader = new SchematicReader();
            if (reader.load(schematicsDir.getAbsolutePath() + "/" + targetName)) {
                selectedSchematic = targetName;
                currentTask = "Build: " + targetName;
                client.player.sendMessage(Text.literal("§a[AutoMiner] §f已选择蓝图: " + targetName), false);
                client.player.sendMessage(Text.literal("§e  尺寸: " + reader.getSize()[0] + "x" + reader.getSize()[1] + "x" + reader.getSize()[2] + ", " + reader.getTotalBlocks() + " 方块"), false);
                String materials = reader.getMaterialSummary();
                for (String line : materials.split("\n")) {
                    client.player.sendMessage(Text.literal("§7  " + line), false);
                }
                client.player.sendMessage(Text.literal("§a  输入 §f/am start §a开始建造"), false);
            } else {
                client.player.sendMessage(Text.literal("§c[AutoMiner] §f无法加载蓝图: " + targetName), false);
            }
        } else if (args.startsWith("task ")) {
            currentTask = args.substring(5).trim();
            client.player.sendMessage(Text.literal("§a[AutoMiner] §f任务已设置: " + currentTask), false);
        } else {
            client.player.sendMessage(Text.literal("§e[AutoMiner] §f命令: start [蓝图] | stop | status | schematics | choose <编号/蓝图名> | task <描述>"), false);
        }
    }

    /**
     * Scan the schematics directory for .litematic files and update cache.
     */
    private List<String> scanSchematics(MinecraftClient client) {
        File schematicsDir = new File(client.runDirectory, "schematics");
        List<String> schematics = SchematicReader.scanSchematicsDir(schematicsDir.getAbsolutePath());
        availableSchematics = String.join(", ", schematics);
        return schematics;
    }

    /**
     * Main automation tick — called when idle and cooldown expired.
     */
    private void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        String state = buildStateReport(client);
        String instruction = llmClient.ask(currentTask, state);
        if (instruction == null || instruction.isBlank()) return;

        LOGGER.debug("LLM instruction: {}", instruction);
        executeInstruction(client, instruction);
    }

    /**
     * Scan blocks around the player and return a compact summary for the LLM.
     * Groups blocks by type, shows nearest instance coords and count.
     */
    private String scanNearbyBlocks(MinecraftClient client) {
        if (client.player == null || client.world == null) return "unknown";
        BlockPos p = client.player.getBlockPos();
        int R = 5; // scan radius
        Map<String, int[]> best = new java.util.LinkedHashMap<>(); // id -> [x,y,z,count]
        for (int dx = -R; dx <= R; dx++) {
            for (int dy = -1; dy <= R - 1; dy++) {
                for (int dz = -R; dz <= R; dz++) {
                    BlockPos bp = p.add(dx, dy, dz);
                    String id = Registries.BLOCK.getId(client.world.getBlockState(bp).getBlock()).toString();
                    if (id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")) continue;
                    int[] v = best.get(id);
                    if (v == null) {
                        v = new int[]{dx, dy, dz, 1};
                        best.put(id, v);
                    } else {
                        v[3]++;
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <
                                Math.abs(v[0]) + Math.abs(v[1]) + Math.abs(v[2])) {
                            v[0] = dx; v[1] = dy; v[2] = dz;
                        }
                    }
                }
            }
        }
        if (best.isEmpty()) return "空";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : best.entrySet()) {
            String shortId = e.getKey().replace("minecraft:", "");
            int[] v = e.getValue();
            sb.append(shortId).append("(").append(v[3]).append("x,near:")
              .append(v[0]).append(",").append(v[1]).append(",").append(v[2]).append(") ");
        }
        return sb.toString().trim();
    }

    /**
     * Build a compact state report for the LLM.
     */
    private String buildStateReport(MinecraftClient client) {
        var player = client.player;
        var heldStack = player.getMainHandStack();
        String heldItem = heldStack.isEmpty() ? "empty" : Registries.ITEM.getId(heldStack.getItem()).getPath();
        String durability = "";
        if (!heldStack.isEmpty() && heldStack.isDamageable()) {
            int maxDmg = heldStack.getMaxDamage();
            int curDmg = maxDmg - heldStack.getDamage();
            durability = String.format(" | Tool耐久: %d/%d (%.0f%%)", curDmg, maxDmg, 100.0 * curDmg / maxDmg);
        }
        String invSummary = inventoryManager.getInventorySummary(client);
        String schemInfo = availableSchematics.isEmpty() ? "" : " | Schematics: " + availableSchematics;
        String resultInfo = lastActionResult.isEmpty() ? "" : " | 上次动作: " + lastActionResult;
        String nearbyInfo = scanNearbyBlocks(client);

        String report = String.format(
                "Position: %.1f %.1f %.1f | Health: %.0f/%d | Hunger: %d/%d | " +
                        "Dimension: %s | Held: %s%s | Task: %s | %s%s%s\n附近方块: %s",
                player.getX(), player.getY(), player.getZ(),
                player.getHealth(), (int) player.getMaxHealth(),
                player.getHungerManager().getFoodLevel(), 20,
                client.world != null ? client.world.getDimension().toString() : "unknown",
                heldItem, durability,
                currentTask.isEmpty() ? "none" : currentTask,
                invSummary, schemInfo, resultInfo,
                nearbyInfo
        );

        // Clear the result so it only appears once
        lastActionResult = "";
        return report;
    }

    /**
     * Parse and execute an LLM instruction.
     */
    private void executeInstruction(MinecraftClient client, String instruction) {
        instruction = instruction.trim();
        lastInstruction = instruction;
        if (instruction.startsWith("MOVE_TO:")) {
            String[] parts = instruction.substring(8).split(",");
            if (parts.length >= 3) {
                try {
                    double x = Double.parseDouble(parts[0].trim());
                    double y = Double.parseDouble(parts[1].trim());
                    double z = Double.parseDouble(parts[2].trim());
                    pathfinder.moveTo(client.player, x, y, z);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid MOVE_TO coordinates: {}", instruction);
                }
            }
        } else if (instruction.startsWith("MINE:")) {
            String coords = instruction.substring(5).trim();
            String[] parts = coords.split(",");
            if (parts.length >= 3) {
                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    playerController.lookAt(client.player, x, y, z);
                    blockBreaker.mineBlock(client, x, y, z);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid MINE coordinates: {}", instruction);
                }
            }
        } else if (instruction.startsWith("PLACE:")) {
            String params = instruction.substring(6).trim();
            String[] parts = params.split(",");
            if (parts.length >= 4) {
                try {
                    String blockName = parts[0].trim();
                    int x = Integer.parseInt(parts[1].trim());
                    int y = Integer.parseInt(parts[2].trim());
                    int z = Integer.parseInt(parts[3].trim());
                    playerController.lookAt(client.player, x, y, z);
                    blockPlacer.placeBlock(client, blockName, x, y, z);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid PLACE params: {}", instruction);
                }
            }
        } else if (instruction.startsWith("CHAT:")) {
            String message = instruction.substring(5).trim();
            chatHandler.sendChat(client, message);
        } else if (instruction.startsWith("CRAFT:")) {
            String recipe = instruction.substring(6).trim();
            LOGGER.info("LLM wants to craft: {}", recipe);
            chatHandler.sendChat(client, "/craft " + recipe);
        } else if (instruction.startsWith("BUILD:")) {
            String schematic = instruction.substring(6).trim();
            LOGGER.info("LLM wants to build schematic: {}", schematic);
            currentTask = "Building: " + schematic;
        } else if (instruction.startsWith("LIST_SCHEMATICS")) {
            File schematicsDir = new File(client.runDirectory, "schematics");
            List<String> schematics = SchematicReader.scanSchematicsDir(schematicsDir.getAbsolutePath());
            if (schematics.isEmpty()) {
                LOGGER.info("No .litematic files found in schematics/");
            } else {
                LOGGER.info("Available schematics: {}", String.join(", ", schematics));
            }
        } else if (instruction.startsWith("LOOK_AT:")) {
            String[] parts = instruction.substring(8).split(",");
            if (parts.length >= 3) {
                try {
                    double x = Double.parseDouble(parts[0].trim());
                    double y = Double.parseDouble(parts[1].trim());
                    double z = Double.parseDouble(parts[2].trim());
                    playerController.lookAt(client.player, x, y, z);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid LOOK_AT coordinates: {}", instruction);
                }
            }
        } else if (instruction.startsWith("WAIT:")) {
            LOGGER.debug("LLM requested wait: {}", instruction);
        } else if (instruction.startsWith("TASK:")) {
            currentTask = instruction.substring(5).trim();
            LOGGER.info("New task: {}", currentTask);
        } else if (instruction.equalsIgnoreCase("STOP")) {
            autoMode = false;
            LOGGER.info("Auto mode stopped by LLM");
        } else {
            LOGGER.warn("Unknown instruction: {}", instruction);
        }
    }

    // --- Public API ---

    public static boolean isInitialized() { return initialized; }
    public static boolean isAutoMode() { return autoMode; }
    public static void setAutoMode(boolean mode) { autoMode = mode; }
    public static String getCurrentTask() { return currentTask; }

    public static LLMClient getLlmClient() { return llmClient; }
    public static PlayerController getPlayerController() { return playerController; }
    public static ChatCommandHandler getChatHandler() { return chatHandler; }
    public static InventoryManager getInventoryManager() { return inventoryManager; }
}
