package com.nous.autominer.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for OpenAI-compatible LLM APIs (DeepSeek, MiMo, etc.).
 * <p>
 * Uses Java 21's built-in {@link java.net.http.HttpClient}.
 * No external dependencies needed.
 */
public class LLMClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("auto-miner-llm");

    private String apiUrl;
    private String apiKey;
    private String model;
    private final HttpClient httpClient;
    private final Gson gson;

    // System prompt that defines the mod's capabilities
    private static final String SYSTEM_PROMPT = """
            You are controlling a Minecraft survival automation mod. You receive the player's current
            state (position, health, hunger, held item with tool durability, current task, inventory summary,
            and a list of nearby blocks with their coordinates relative to the player)
            and must respond with ONE action to progress the task.

            NEARBY BLOCKS: The state report ends with "附近方块: ..." showing what blocks are around you.
            Format: block_type(count,near:dx,dy,dz) — count of that block type and coords of the nearest one.
            You can see trees (oak_log, birch_log, spruce_log), ores (coal_ore, iron_ore, deepslate_diamond_ore),
            stone, dirt, grass_block, crafting_table, furnace, chest, etc.
            Use these relative coordinates to aim your MOVE_TO and MINE commands!

            PROGRESSION (fist to diamond):
            Phase 1 — Punch nearby trees (MINE log at near:x,y,z) → craft planks/sticks/crafting_table
            Phase 2 — Craft wooden_pickaxe at crafting_table → MINE nearby stone → stone tools
            Phase 3 — Place furnace → MINE iron_ore → smelt → iron tools
            Phase 4 — Iron pickaxe → MINE deepslate_diamond_ore → diamond tools

            Available commands:
            - MOVE_TO:x,y,z — Pathfind to absolute coordinates
            - MINE:x,y,z — Break the block at the given position
            - PLACE:blockId,x,y,z — Place a block at position (e.g. PLACE:crafting_table,100,64,200)
            - CHAT:/command — Send a chat command (/res tp, /cd, /tpa PlayerName)
            - CRAFT:id — Request crafting (CRAFT:crafting_table, CRAFT:wooden_pickaxe)
            - LIST_SCHEMATICS — List available .litematic files
            - TASK:description — Set a new current task
            - WAIT:ticks — Wait N ticks (20 ticks = 1 second)
            - STOP — Stop automation

            Tool durability: use the durability info provided. If below 20%, find a way to craft replacement.
            Teleport: /res tp — teleport to your land/residence.
            /cd — server panel, /tpa <player> — request teleport.
            After teleport, WAIT:40 (2 seconds) for server response.

            IMPORTANT: Use the nearby block coords! If a block shows "near:2,-1,3", do MOVE_TO with
            player position plus those offsets. Always MOVE_TO before MINE/PLACE.
            Keep responses to a single command line. No explanations.
            """;

    public LLMClient(String apiUrl, String apiKey, String model) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.gson = new Gson();
    }

    /**
     * Ask the LLM for the next action given the current task and state.
     *
     * @param task  The current task description
     * @param state Compact state string (position, health, hunger, etc.)
     * @return The LLM's response text (a single command), or null on failure
     */
    public String ask(String task, String state) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("max_tokens", 500);
            body.addProperty("temperature", 0.3);

            JsonArray messages = new JsonArray();

            // System message
            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", SYSTEM_PROMPT);
            messages.add(systemMsg);

            // User message with state
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", "Current state: " + state
                    + "\nCurrent task: " + (task.isEmpty() ? "none — explore and gather" : task)
                    + "\nWhat should I do next?");
            messages.add(userMsg);

            body.add("messages", messages);

            String jsonBody = gson.toJson(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.warn("LLM API returned {}: {}", response.statusCode(), response.body());
                return null;
            }

            // Parse response
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            String content = json.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .get("message").getAsJsonObject()
                    .get("content").getAsString()
                    .trim();
            if (content.isEmpty()) {
                LOGGER.warn("LLM returned empty content. Full response: {}", response.body());
            }

            return content;

        } catch (Exception e) {
            LOGGER.error("LLM API call failed", e);
            return null;
        }
    }

    // --- Getters for config ---

    public String getApiUrl() { return apiUrl; }
    public String getModel() { return model; }
    public String getApiKey() { return apiKey; }
    public boolean isConfigured() { return apiKey != null && !apiKey.isEmpty(); }
    public void setApiKey(String key) { this.apiKey = key; }
    public void setModel(String m) { this.model = m; }
    public void setApiUrl(String url) { this.apiUrl = url; }
}
