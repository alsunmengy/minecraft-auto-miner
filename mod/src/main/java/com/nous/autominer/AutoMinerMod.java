package com.nous.autominer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
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
 * <p>
 * Entry point for the client-side Fabric mod. Initializes all subsystems
 * and orchestrates LLM-driven gameplay decisions on each tick.
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
    private static int tickCounter = 0;

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

        // Read LLM config from env or use defaults
        String apiUrl = System.getenv().getOrDefault("LLM_API_URL",
                "https://api.deepseek.com/v1/chat/completions");
        String apiKey = System.getenv().getOrDefault("LLM_API_KEY", "");
        String model = System.getenv().getOrDefault("LLM_MODEL", "deepseek-chat");
        llmClient = new LLMClient(apiUrl, apiKey, model);

        initialized = true;
        LOGGER.info("Auto Miner initialized. LLM: {} at {}", model, apiUrl);

        // Register tick event — main automation loop
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!autoMode || client.player == null) return;
            tickCounter++;
            if (tickCounter % 10 != 0) return; // Run every 10 ticks (0.5s)

            try {
                tick(client);
            } catch (Exception e) {
                LOGGER.error("Error in automation tick", e);
                autoMode = false;
            }
        });

        // Listen for chat messages (teleport confirmations, errors, etc.)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (chatHandler != null) {
                chatHandler.onChatMessage(message.getString());
            }
        });
    }

    /**
     * Main automation tick — called every ~0.5 seconds when autoMode is on.
     */
    private void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // Gather current state
        String state = buildStateReport(client);

        // Ask LLM what to do next
        String instruction = llmClient.ask(currentTask, state);
        if (instruction == null || instruction.isBlank()) return;

        LOGGER.debug("LLM instruction: {}", instruction);

        // Parse and execute instruction
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
        // Add inventory summary
        String invSummary = inventoryManager.getInventorySummary(client);

        return String.format(
                "Position: %.1f %.1f %.1f | Health: %.0f/%d | Hunger: %d/%d | " +
                        "Dimension: %s | Held: %s%s | Task: %s | %s",
                player.getX(), player.getY(), player.getZ(),
                player.getHealth(), (int) player.getMaxHealth(),
                player.getHungerManager().getFoodLevel(), 20,
                player.getWorld().getDimensionEntry().getIdAsString(),
                heldItem, durability,
                currentTask.isEmpty() ? "none" : currentTask,
                invSummary
        );
    }

    /**
     * Parse an LLM instruction string and execute the corresponding action.
     * <p>
     * Expected format: {@code ACTION:params}
     * Examples:
     * <ul>
     *   <li>{@code MOVE_TO:100,64,200}</li>
     *   <li>{@code MINE:block_face_coords}</li>
     *   <li>{@code PLACE:stone,x,y,z}</li>
     *   <li>{@code CHAT:/res tp home}</li>
     *   <li>{@code LOOK_AT:100,64,200}</li>
     *   <li>{@code WAIT:20}</li>
     *   <li>{@code TASK:Chop down the oak tree}</li>
     * </ul>
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
                    // Look at the block first, then mine
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
            // Crafting in survival server is done via chat: /craft <item> or manual
            chatHandler.sendChat(client, "/craft " + recipe);
        } else if (instruction.startsWith("BUILD:")) {
            String schematic = instruction.substring(6).trim();
            LOGGER.info("LLM wants to build schematic: {}", schematic);
            // Load and report the schematic materials to the LLM on next tick
            currentTask = "Building: " + schematic;
            // The LLM will need to gather materials first, then place blocks
            // Actual block-by-block building to be implemented
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
            // WAIT is handled by the tick interval — just log that LLM wanted a pause
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

    // --- Public API for toggling/stats ---

    public static boolean isInitialized() { return initialized; }
    public static boolean isAutoMode() { return autoMode; }
    public static void setAutoMode(boolean mode) { autoMode = mode; }
    public static String getCurrentTask() { return currentTask; }

    public static LLMClient getLlmClient() { return llmClient; }
    public static PlayerController getPlayerController() { return playerController; }
    public static ChatCommandHandler getChatHandler() { return chatHandler; }
    public static InventoryManager getInventoryManager() { return inventoryManager; }
}
