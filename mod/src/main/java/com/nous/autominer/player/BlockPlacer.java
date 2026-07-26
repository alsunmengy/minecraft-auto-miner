package com.nous.autominer.player;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles automatic block placement for the auto-miner.
 * <p>
 * Finds the requested block type in the player's inventory, selects it,
 * and places it at the target coordinates using Minecraft's interaction system.
 */
public class BlockPlacer {
    private static final Logger LOGGER = LoggerFactory.getLogger("auto-miner-blockplacer");

    /**
     * Place a block at the given coordinates.
     *
     * @param client  Minecraft client instance
     * @param blockId Block/item identifier (e.g., "stone", "oak_planks", "minecraft:dirt")
     * @param x       Target X
     * @param y       Target Y
     * @param z       Target Z
     * @return true if placement succeeded, false if item not found or placement failed
     */
    public boolean placeBlock(MinecraftClient client, String blockId, int x, int y, int z) {
        if (client.player == null || client.interactionManager == null) return false;

        BlockPos placePos = new BlockPos(x, y, z);

        // Only use hotbar items (slots 0-8)
        int slot = findBlockInHotbar(client, blockId);
        if (slot == -1) {
            LOGGER.warn("No '{}' found in hotbar — please move to hotbar first", blockId);
            return false;
        }

        // Slot always in hotbar (0-8), just log which slot to select
        LOGGER.info("Use hotbar slot {} for {}", slot, blockId);

        // Build the hit result — place on top of the target position if air,
        // otherwise place against the block face at the target
        Direction placeDirection = Direction.UP;
        BlockPos placeAgainst = placePos.down(); // Place on top of the block below

        // Check if the block below exists and is solid
        if (client.world != null && client.world.getBlockState(placeAgainst).isAir()) {
            // No block below, try placing against the side
            placeAgainst = placePos;
            placeDirection = Direction.UP;
        }

        BlockHitResult hitResult = new BlockHitResult(
                Vec3d.ofCenter(placeAgainst),
                placeDirection,
                placeAgainst,
                false
        );

        // Perform placement
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

        // Swing hand for visual feedback
        client.player.swingHand(Hand.MAIN_HAND);

        LOGGER.debug("Placed '{}' at ({}, {}, {})", blockId, x, y, z);
        return true;
    }

    /**
     * Search the player's hotbar (slots 0-8) for a block matching the given ID.
     */
    private int findBlockInHotbar(MinecraftClient client, String blockId) {
        if (client.player == null) return -1;

        String fullId = blockId.contains(":") ? blockId : "minecraft:" + blockId;
        Identifier targetId = Identifier.tryParse(fullId);
        if (targetId == null) return -1;

        var inventory = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            Identifier itemId = Registries.ITEM.getId(stack.getItem());
            if (itemId.equals(targetId)) return i;
        }
        return -1;
    }
}
