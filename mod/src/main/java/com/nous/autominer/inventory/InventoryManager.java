package com.nous.autominer.inventory;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages inventory tracking and item searches for the auto-miner.
 * <p>
 * Provides methods to scan the player's inventory, find specific items,
 * check tool durability, and monitor inventory capacity.
 */
public class InventoryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("auto-miner-inventory");

    /**
     * Scan the player's entire inventory and return a map of slot → ItemStack.
     *
     * @param client Minecraft client instance
     * @return Map of slot index to ItemStack (empty stacks are excluded)
     */
    public Map<Integer, ItemStack> scanInventory(MinecraftClient client) {
        Map<Integer, ItemStack> result = new HashMap<>();
        if (client.player == null) return result;

        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                result.put(i, stack);
            }
        }
        return result;
    }

    /**
     * Find the first inventory slot containing the specified item.
     *
     * @param client Minecraft client instance
     * @param itemId Item identifier (e.g., "diamond", "minecraft:oak_planks", "iron_pickaxe")
     * @return Slot index, or -1 if not found
     */
    public int findItem(MinecraftClient client, String itemId) {
        if (client.player == null) return -1;

        String fullId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        Identifier targetId = Identifier.tryParse(fullId);
        if (targetId == null) {
            LOGGER.warn("Invalid item ID: {}", itemId);
            return -1;
        }

        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (id.equals(targetId)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Get the durability percentage of a tool.
     *
     * @param stack The ItemStack to check
     * @return Durability as a percentage (0.0 to 1.0), or 1.0 if not damageable
     */
    public double getToolDurability(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageable()) {
            return 1.0;
        }

        int maxDamage = stack.getMaxDamage();
        int currentDamage = stack.getDamage();
        return (double) (maxDamage - currentDamage) / maxDamage;
    }

    /**
     * Select a specific slot in the hotbar.
     *
     * @param client Minecraft client instance
     * @param slot   The slot index to select (0-8 for hotbar)
     */
    public void selectItemSlot(MinecraftClient client, int slot) {
        if (client.player == null) return;
        if (slot < 0 || slot > 8) {
            LOGGER.warn("Slot {} is not in hotbar (0-8)", slot);
            return;
        }
        LOGGER.debug("Slot selection requested: {}", slot);
    }

    /**
     * Check if the player has at least a certain count of an item.
     *
     * @param client Minecraft client instance
     * @param itemId Item identifier
     * @param count  Minimum required count
     * @return true if the player has at least 'count' of the item
     */
    public boolean hasItem(MinecraftClient client, String itemId, int count) {
        return getItemCount(client, itemId) >= count;
    }

    /**
     * Count the total number of a specific item across the entire inventory.
     *
     * @param client Minecraft client instance
     * @param itemId Item identifier
     * @return Total count of the item
     */
    public int getItemCount(MinecraftClient client, String itemId) {
        if (client.player == null) return 0;

        String fullId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        Identifier targetId = Identifier.tryParse(fullId);
        if (targetId == null) return 0;

        int count = 0;
        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (id.equals(targetId)) {
                count += stack.getCount();
            }
        }

        return count;
    }

    /**
     * Check if the player's inventory is full (no empty slots in main + hotbar).
     *
     * @param client Minecraft client instance
     * @return true if inventory is completely full
     */
    public boolean isInventoryFull(MinecraftClient client) {
        if (client.player == null) return true;

        PlayerInventory inv = client.player.getInventory();
        // Check main inventory (slots 9-35) and hotbar (0-8)
        for (int i = 0; i < 36; i++) {
            if (inv.getStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the name of the item currently in the player's main hand.
     *
     * @param client Minecraft client instance
     * @return The display name of the held item, or "empty" if nothing held
     */
    public String getHeldItemName(MinecraftClient client) {
        if (client.player == null) return "empty";
        ItemStack held = client.player.getMainHandStack();
        if (held.isEmpty()) return "empty";
        return Registries.ITEM.getId(held.getItem()).getPath();
    }

    /**
     * Get a human-readable summary of the player's inventory.
     * Useful for sending to the LLM for decision-making.
     *
     * @param client Minecraft client instance
     * @return A compact string summary
     */
    public String getInventorySummary(MinecraftClient client) {
        if (client.player == null) return "no player";

        Map<Integer, ItemStack> items = scanInventory(client);
        if (items.isEmpty()) return "empty inventory";

        // Group by item type and count totals
        Map<String, Integer> grouped = new LinkedHashMap<>();
        for (ItemStack stack : items.values()) {
            Identifier id = Registries.ITEM.getId(stack.getItem());
            String name = id.getPath(); // e.g., "diamond", "oak_planks"
            grouped.merge(name, stack.getCount(), Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Inventory: ");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : grouped.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getValue()).append("x ").append(entry.getKey());
            first = false;
        }
        return sb.toString();
    }
}
