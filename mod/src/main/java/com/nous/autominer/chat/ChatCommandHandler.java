package com.nous.autominer.chat;

import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Handles sending and receiving chat messages for the auto-miner.
 * <p>
 * Supports sending commands (/res tp, /cd, /tpa) and listening for
 * server responses to confirm teleportation or detect errors.
 */
public class ChatCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("auto-miner-chat");

    private final List<Consumer<String>> chatMessageListeners = new ArrayList<>();
    private String lastChatMessage = "";

    /**
     * Send a chat message or command through the player's network handler.
     *
     * @param client  Minecraft client instance
     * @param message The message or command to send (e.g., "/res tp home")
     */
    public void sendChat(MinecraftClient client, String message) {
        if (client.player == null || client.player.networkHandler == null) {
            LOGGER.warn("Cannot send chat: player or network handler is null");
            return;
        }

        client.player.networkHandler.sendChatMessage(message);
        LOGGER.info("Sent chat: {}", message);
    }

    /**
     * Called by the mod's event handler when a chat message is received from the server.
     * This is registered in AutoMinerMod via ClientReceiveMessageEvents.GAME.
     *
     * @param rawMessage The raw chat message string
     */
    public void onChatMessage(String rawMessage) {
        lastChatMessage = rawMessage;

        // Notify all registered listeners
        for (Consumer<String> listener : chatMessageListeners) {
            listener.accept(rawMessage);
        }
    }

    /**
     * Register a listener to be notified when chat messages arrive.
     *
     * @param listener Consumer that receives each chat message string
     */
    public void registerListener(Consumer<String> listener) {
        chatMessageListeners.add(listener);
    }

    /**
     * Remove a previously registered listener.
     *
     * @param listener The listener to remove
     */
    public void unregisterListener(Consumer<String> listener) {
        chatMessageListeners.remove(listener);
    }

    /**
     * Wait for a chat message containing a specific keyword.
     * Polls the stored messages over a number of ticks.
     * <p>
     * NOTE: This is a simplified blocking-poll approach. For production use,
     * a callback-based approach with CompletableFuture would be better.
     *
     * @param keyword     The keyword to look for in incoming chat messages
     * @param maxTicks    Maximum ticks to wait (20 ticks = 1 second)
     * @param clientTicks Reference to a tick counter (incremented externally)
     * @return The matching message, or null if timeout
     */
    public String waitForKeyword(String keyword, int maxTicks, int clientTicks) {
        final String[] captured = {null};
        Consumer<String> listener = msg -> {
            if (msg.contains(keyword)) {
                captured[0] = msg;
            }
        };

        registerListener(listener);

        int startTicks = clientTicks;
        while ((clientTicks - startTicks) < maxTicks) {
            if (captured[0] != null) {
                unregisterListener(listener);
                return captured[0];
            }
            // Busy-wait — in actual Minecraft integration this would be
            // driven by the tick event instead
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        unregisterListener(listener);
        return null;
    }

    /**
     * Get the most recent chat message received.
     */
    public String getLastChatMessage() {
        return lastChatMessage;
    }

    /**
     * Check if the last chat message contains a specific keyword.
     * Useful after sending teleport commands to verify success.
     *
     * @param keyword Keyword to check for
     * @return true if the last message contains the keyword
     */
    public boolean lastMessageContains(String keyword) {
        return lastChatMessage != null && lastChatMessage.contains(keyword);
    }
}
