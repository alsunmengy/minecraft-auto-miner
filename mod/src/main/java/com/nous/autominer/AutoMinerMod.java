package com.nous.autominer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        String mcRunDir = MinecraftClient.getInstance().runDirectory.getAbsolutePath();
        List<String> schematics = SchematicReader.scanSchematicsDir(mcRunDir + "/schematics");
        if (!schematics.isEmpty()) {
            availableSchematics = String.join(", ", schematics);
            LOGGER.info("Found schematics: {}", availableSchematics);
        }

        // Main tick — monitors busy state and calls LLM when idle
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            if (!autoMode) return;

            // Track busy state — check if any subsystem is still working
            busy = pathfinder.isActive() || blockBreaker.isBreaking();

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

        // Listen for chat messages
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (chatHandler != null) {
                chatHandler.onChatMessage(message.getString());
            }

            // Handle /am commands from the player's own chat
            MinecraftClient mc = MinecraftClient.getInstance();
            String raw = message.getString();
            if (mc.player != null && raw.startsWith("/am ")) {
                handleCommand(mc, raw.substring(4).trim());
            }
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
            if (currentTask.isEmpty()) {
                client.player.sendMessage(Text.literal("§a[AutoMiner] §f自动化已启动，当前无指定任务，将自由探索"), false);
            } else {
                client.player.sendMessage(Text.literal("§a[AutoMiner] §f自动化已启动，任务: " + currentTask), false);
            }
            LOGGER.info("Auto mode started via command");
        } else if (args.startsWith("start ")) {
            // /am start <blueprint_name> — start with a specific blueprint
            String name = args.substring(6).trim();
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
            String sChems = availableSchematics.isEmpty() ? "无" : availableSchematics;
            client.player.sendMessage(Text.literal(String.format(
                    "§e[AutoMiner] §f状态: %s | 任务: %s | LLM: %s | 模型: %s | 蓝图: %s",
                    status, task, llmOk, llmClient.getModel(), sChems)), false);
        } else if (args.equals("schematics")) {
            if (availableSchematics.isEmpty()) {
                client.player.sendMessage(Text.literal("§e[AutoMiner] §f没有找到 .litematic 蓝图文件"), false);
            } else {
                client.player.sendMessage(Text.literal("§e[AutoMiner] §f可用蓝图: " + availableSchematics), false);
            }
        } else if (args.startsWith("choose ")) {
            // /am choose <blueprint_name> — show material list for a blueprint
            String name = args.substring(7).trim();
            File schematicsDir = new File(client.runDirectory, "schematics");
            SchematicReader reader = new SchematicReader();
            if (reader.load(schematicsDir.getAbsolutePath() + "/" + name)) {
                client.player.sendMessage(Text.literal("§e[AutoMiner] §f蓝图: " + reader.getName() + " (" + reader.getSize()[0] + "x" + reader.getSize()[1] + "x" + reader.getSize()[2] + ", " + reader.getTotalBlocks() + " 方块)"), false);
                String materials = reader.getMaterialSummary();
                // Send material summary in chunks if too long
                for (String line : materials.split("\n")) {
                    client.player.sendMessage(Text.literal("§7  " + line), false);
                }
            } else {
                client.player.sendMessage(Text.literal("§c[AutoMiner] §f无法加载蓝图: " + name), false);
            }
        } else if (args.startsWith("task ")) {
            currentTask = args.substring(5).trim();
            client.player.sendMessage(Text.literal("§a[AutoMiner] §f任务已设置: " + currentTask), false);
        } else {
            client.player.sendMessage(Text.literal("§e[AutoMiner] §f命令: start [蓝图] | stop | status | schematics | choose <蓝图> | task <描述>"), false);
        }
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
     * Build a compact state report for the LLM.
     */
    private String buildStateReport(MinecraftClient client) {
        var player = client.player;
        var heldStack = player.getMainHandStack();
        String heldItem = heldStack.isEmpty() ? "empty" : heldStack.getItem().getName().getString();
        String durability = "";
        if (!heldStack.isEmpty() && heldStack.isDamageable()) {
            int maxDmg = heldStack.getMaxDamage();
            int curDmg = maxDmg - heldStack.getDamage();
            durability = String.format(" | Tool耐久: %d/%d (%.0f%%)", curDmg, maxDmg, 100.0 * curDmg / maxDmg);
        }
        String invSummary = inventoryManager.getInventorySummary(client);
        String schemInfo = availableSchematics.isEmpty() ? "" : " | Schematics: " + availableSchematics;

        return String.format(
                "Position: %.1f %.1f %.1f | Health: %.0f/%d | Hunger: %d/%d | " +
                        "Dimension: %s | Held: %s%s | Task: %s | %s%s",
                player.getX(), player.getY(), player.getZ(),
                player.getHealth(), (int) player.getMaxHealth(),
                player.getHungerManager().getFoodLevel(), 20,
                player.getWorld().getDimensionEntry().getIdAsString(),
                heldItem, durability,
                currentTask.isEmpty() ? "none" : currentTask,
                invSummary, schemInfo
        );
    }

    /**
     * Parse and execute an LLM instruction.
     */
    private void executeInstruction(MinecraftClient client, String instruction) {
        instruction = instruction.trim();
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
