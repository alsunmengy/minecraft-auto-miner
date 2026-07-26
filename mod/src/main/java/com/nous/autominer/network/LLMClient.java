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

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final Gson gson;

    // System prompt that defines the mod's capabilities
    private static final String SYSTEM_PROMPT = """
            You are controlling a Minecraft survival automation mod. You receive the player's current
            state (position, health, hunger, held item with tool durability, current task, and inventory summary)
            and must respond with ONE action to progress the task.

            PROGRESSION (fist to diamond):
            Phase 1 — Wood: Punch trees (MINE) → get logs → CHAT:/craft planks → CHAT:/craft crafting_table
            Phase 2 — Stone: CHAT:/craft wooden_pickaxe → MINE stone → stone tools
            Phase 3 — Iron: MINE iron_ore → smelt in furnace → CHAT:/craft iron_pickaxe
            Phase 4 — Diamond: MINE deepslate_diamond_ore → diamond tools
            Phase 5 — Build: Gather materials → BUILD:schematic_name

            Available commands:
            - MOVE_TO:x,y,z — Pathfind to coordinates
            - MINE:x,y,z — Break the block at the given position
            - PLACE:blockId,x,y,z — Place a block at position (e.g. PLACE:oak_planks,100,64,200)
            - CHAT:/command — Send a chat command (/res tp home, /cd, /tpa PlayerName, /craft item)
            - CRAFT:item — Request crafting via /craft command
            - BUILD:schematic_name — Start building a schematic (gather materials then place)
            - LIST_SCHEMATICS — List available .litematic files
            - LOOK_AT:x,y,z — Snap camera to look at coordinates
            - TASK:description — Set a new current task
            - WAIT:ticks — Wait N ticks (20 ticks = 1 second)
            - STOP — Stop automation

            Tool durability: the state report shows your tool durability as "Tool耐久: current/max (%)".
            If durability drops below 20%, go back to base (CHAT:/res tp home) and craft a replacement
            before continuing.

            Teleport: use /res tp home to return to base, /res tp <name> for other locations,
            /cd to open the server panel, /tpa <player> to request teleport to another player.
            After sending a teleport command, WAIT:40 (2 seconds) for the server to respond.

            Move step by step. For mining a block, first MOVE_TO then MINE.
            For building, MOVE_TO the build area then PLACE each block.
            Use CHAT for server commands, CRAFT for crafting items.
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
            body.addProperty("max_tokens", 120);
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

            return content;

        } catch (Exception e) {
            LOGGER.error("LLM API call failed", e);
            return null;
        }
    }

    // --- Getters for config ---

    public String getApiUrl() { return apiUrl; }
    public String getModel() { return model; }
    public boolean isConfigured() { return apiKey != null && !apiKey.isEmpty(); }
}
