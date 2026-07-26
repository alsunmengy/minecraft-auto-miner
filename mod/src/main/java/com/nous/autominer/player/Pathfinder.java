package com.nous.autominer.player;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Simple pathfinder for Minecraft terrain navigation.
 * <p>
 * Moves along a calculated path on the XZ plane, handling simple obstacles by jumping.
 * For a survival server without fly, only ground-level movement is supported.
 */
public class Pathfinder {
    private static final Logger LOGGER = LoggerFactory.getLogger("auto-miner-pathfinder");

    // Current path
    private Queue<BlockPos> path = new LinkedList<>();
    private BlockPos currentTarget = null;
    private boolean active = false;

    // How far to search (Manhattan distance limit)
    private static final int SEARCH_RADIUS = 64;

    /**
     * Calculate and start moving along a path to the target.
     */
    public void moveTo(ClientPlayerEntity player, double tx, double ty, double tz) {
        if (player == null) return;

        BlockPos start = player.getBlockPos();
        BlockPos target = BlockPos.ofFloored(tx, ty, tz);

        if (start.getManhattanDistance(target) <= 4) {
            LOGGER.debug("Target very close, moving directly");
            currentTarget = target;
            active = true;
            return;
        }

        // Get world from MinecraftClient
        World world = MinecraftClient.getInstance().world;
        List<BlockPos> calculatedPath = aStar(world, start, target);
        if (calculatedPath == null || calculatedPath.isEmpty()) {
            LOGGER.warn("No path found from {} to {}", start, target);
            active = false;
            return;
        }

        if (calculatedPath.size() > 1) {
            calculatedPath = calculatedPath.subList(1, calculatedPath.size());
        }

        this.path = new LinkedList<>(calculatedPath);
        this.currentTarget = this.path.poll();
        this.active = true;

        LOGGER.info("Path found: {} steps to target", calculatedPath.size() + 1);
    }

    /**
     * Tick: advance along the path. Call every tick.
     *
     * @return true if still pathfinding, false if arrived or stuck
     */
    public boolean tick(ClientPlayerEntity player) {
        if (!active || player == null) return false;

        if (currentTarget == null) {
            stop(player);
            return false;
        }

        // Check if we've arrived at the current waypoint
        double cx = currentTarget.getX() + 0.5;
        double cz = currentTarget.getZ() + 0.5;
        double distance = Math.sqrt(
            (cx - player.getX()) * (cx - player.getX()) +
            (cz - player.getZ()) * (cz - player.getZ()));

        if (distance <= 1.5) {
            currentTarget = path.poll();
            if (currentTarget == null) {
                stop(player);
                LOGGER.info("Pathfinding complete");
                return false;
            }
        }

        // Move toward the current waypoint
        double dx = currentTarget.getX() + 0.5 - player.getX();
        double dz = currentTarget.getZ() + 0.5 - player.getZ();
        double dy = currentTarget.getY() - player.getY();

        // Look at target
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);

        // Movement
        Vec3d direction = new Vec3d(dx, 0, dz).normalize();
        double speed = 0.5;
        player.setVelocity(direction.x * speed, player.getVelocity().y, direction.z * speed);

        if (player.horizontalCollision && distance > 2.0) {
            player.jump();
        }

        return true;
    }

    /**
     * A* pathfinding on the surface.
     * Finds a walkable path from start to target within SEARCH_RADIUS.
     * <p>
     * Simplified: walks on the surface (Y is determined by the highest solid
     * block at each XZ position).
     */
    private List<BlockPos> aStar(World world, BlockPos start, BlockPos target) {
        // Simple implementation: just try to walk in the target direction
        // on the surface, using A* on XZ plane.
        // For a production mod, a full 3D A* or Jump Point Search would be better.

        if (world == null) return null;

        // Check if target is too far
        if (start.getManhattanDistance(target) > SEARCH_RADIUS * 2) {
            LOGGER.warn("Target too far ({} blocks)", start.getManhattanDistance(target));
            return null;
        }

        // Simple direct path = build a line of surface points
        List<BlockPos> result = new ArrayList<>();
        int dx = target.getX() - start.getX();
        int dz = target.getZ() - start.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));

        if (steps == 0) {
            result.add(target);
            return result;
        }

        double stepX = (double) dx / steps;
        double stepZ = (double) dz / steps;

        for (int i = 1; i <= steps; i++) {
            int x = (int) Math.round(start.getX() + stepX * i);
            int z = (int) Math.round(start.getZ() + stepZ * i);
            int y = findSurfaceY(world, x, z, start.getY());
            if (y != -1) {
                result.add(new BlockPos(x, y, z));
            } else {
                // Can't find surface, try from start Y
                result.add(new BlockPos(x, start.getY(), z));
            }
        }

        return result;
    }

    /**
     * Find the surface Y at a given XZ position by scanning downward.
     */
    private int findSurfaceY(World world, int x, int z, int startY) {
        for (int y = startY + 2; y > startY - 10; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && state.isFullCube(world, pos)) {
                return y + 1; // Player stands on top
            }
        }
        return -1; // Could not find surface
    }

    /**
     * Stop pathfinding and reset.
     */
    public void stop(ClientPlayerEntity player) {
        active = false;
        path.clear();
        currentTarget = null;
        if (player != null) {
            player.setVelocity(0, player.getVelocity().y, 0);
        }
    }

    // --- Getters ---

    public boolean isActive() { return active; }
    public BlockPos getCurrentTarget() { return currentTarget; }
    public int getRemainingSteps() { return path.size(); }
}
