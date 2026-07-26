package com.nous.autominer.player;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles automatic block breaking for the auto-miner.
 * <p>
 * Uses Minecraft's ClientPlayerInteractionManager to start and sustain
 * block-breaking progress until the block is destroyed.
 */
public class BlockBreaker {
    private static final Logger LOGGER = LoggerFactory.getLogger("auto-miner-blockbreaker");

    private BlockPos currentTarget = null;
    private boolean breaking = false;

    /**
     * Start mining a block at the given coordinates.
     * Calls attackBlock to start and updateBlockBreakingProgress to sustain.
     *
     * @param client Minecraft client instance
     * @param x      Block X
     * @param y      Block Y
     * @param z      Block Z
     */
    public void mineBlock(MinecraftClient client, int x, int y, int z) {
        if (client.player == null || client.interactionManager == null) return;

        BlockPos pos = new BlockPos(x, y, z);
        Direction direction = getDirectionTowards(client, pos);

        // Start breaking
        client.interactionManager.attackBlock(pos, direction);
        currentTarget = pos;
        breaking = true;

        LOGGER.debug("Started mining block at ({}, {}, {})", x, y, z);
    }

    /**
     * Called every tick to sustain breaking progress on the current target.
     * Should be invoked from the mod's main tick loop when breaking is active.
     *
     * @param client Minecraft client instance
     * @return true if still breaking, false if finished or target was destroyed
     */
    public boolean tick(MinecraftClient client) {
        if (!breaking || currentTarget == null || client.interactionManager == null) {
            breaking = false;
            return false;
        }

        // Check if the block is still there
        if (client.world == null || client.world.getBlockState(currentTarget).isAir()) {
            LOGGER.debug("Block at {} is already broken", currentTarget);
            breaking = false;
            currentTarget = null;
            return false;
        }

        Direction direction = getDirectionTowards(client, currentTarget);
        client.interactionManager.updateBlockBreakingProgress(currentTarget, direction);

        return true;
    }

    /**
     * Quick attack (single-click) a block. Useful for entities or one-hit blocks.
     */
    public void attackBlock(MinecraftClient client, int x, int y, int z) {
        if (client.interactionManager == null) return;
        BlockPos pos = new BlockPos(x, y, z);
        Direction direction = getDirectionTowards(client, pos);
        client.interactionManager.attackBlock(pos, direction);
    }

    /**
     * Stop any current breaking action.
     */
    public void stop() {
        if (breaking) {
            LOGGER.debug("Stopped mining");
        }
        breaking = false;
        currentTarget = null;
    }

    /**
     * Calculate the best Direction from the player to the target block.
     * Uses the relative position to pick UP, DOWN, NORTH, SOUTH, EAST, or WEST.
     */
    private Direction getDirectionTowards(MinecraftClient client, BlockPos target) {
        if (client.player == null) return Direction.UP;

        double playerX = client.player.getX();
        double playerY = client.player.getY();
        double playerZ = client.player.getZ();
        double blockX = target.getX() + 0.5;
        double blockY = target.getY() + 0.5;
        double blockZ = target.getZ() + 0.5;

        double dx = blockX - playerX;
        double dy = blockY - (playerY + client.player.getEyeHeight(client.player.getPose()));
        double dz = blockZ - playerZ;

        // Pick the dominant axis
        double absDx = Math.abs(dx);
        double absDy = Math.abs(dy);
        double absDz = Math.abs(dz);

        if (absDy >= absDx && absDy >= absDz) {
            return dy >= 0 ? Direction.DOWN : Direction.UP;
        } else if (absDx >= absDz) {
            return dx >= 0 ? Direction.WEST : Direction.EAST;
        } else {
            return dz >= 0 ? Direction.NORTH : Direction.SOUTH;
        }
    }

    // --- Getters ---

    public boolean isBreaking() { return breaking; }
    public BlockPos getCurrentTarget() { return currentTarget; }
}
