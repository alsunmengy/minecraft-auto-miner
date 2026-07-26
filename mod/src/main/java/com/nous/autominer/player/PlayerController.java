package com.nous.autominer.player;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controls player movement and camera.
 * <p>
 * Handles walking, jumping, sneaking, looking at specific coordinates,
 * and managing movement state.
 */
public class PlayerController {
    private static final Logger LOGGER = LoggerFactory.getLogger("auto-miner-player");

    private boolean moving = false;
    private Vec3d targetPosition = null;
    private double movementSpeed = 0.5; // 0.0 - 1.0

    /**
     * Make the player look at a specific world coordinate.
     * Calculates the correct yaw/pitch angles and applies them.
     */
    public void lookAt(ClientPlayerEntity player, double x, double y, double z) {
        if (player == null) return;

        double dx = x - player.getX();
        double dy = y - (player.getY() + player.getEyeHeight(player.getPose()));
        double dz = z - player.getZ();

        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        // Yaw: horizontal angle (degrees)
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        // Pitch: vertical angle (degrees)
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));

        player.setYaw(yaw);
        player.setPitch(pitch);

        // Also update the head yaw for proper body rotation
        player.setHeadYaw(yaw);
    }

    /**
     * Start moving toward a target position.
     * Call {@link #tickMovement(ClientPlayerEntity)} each tick to continue.
     */
    public void startMoving(ClientPlayerEntity player, double x, double y, double z) {
        this.targetPosition = new Vec3d(x, y, z);
        this.moving = true;
        lookAt(player, x, y, z);
    }

    /**
     * Process one tick of movement toward the target.
     * Should be called every tick while moving is true.
     *
     * @return true if still moving, false if arrived
     */
    public boolean tickMovement(ClientPlayerEntity player) {
        if (!moving || targetPosition == null || player == null) {
            stopMoving(player);
            return false;
        }

        double dx = targetPosition.x - player.getX();
        double dy = targetPosition.y - player.getY();
        double dz = targetPosition.z - player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (distance < 1.5) {
            // Arrived
            stopMoving(player);
            return false;
        }

        // Calculate direction and apply movement
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0) {
            dx = dx / len * movementSpeed;
            dz = dz / len * movementSpeed;
        }
        player.setVelocity(dx, player.getVelocity().y, dz);

        // Update look direction
        lookAt(player, targetPosition.x, targetPosition.y, targetPosition.z);

        // Jump if there's a block in front (small obstacle)
        if (player.horizontalCollision && distance > 2.0) {
            player.jump();
        }

        return true;
    }

    /**
     * Stop all movement and reset velocity.
     */
    public void stopMoving(ClientPlayerEntity player) {
        this.moving = false;
        this.targetPosition = null;
        if (player != null) {
            player.setVelocity(0, player.getVelocity().y, 0);
        }
    }

    /**
     * Quick jump action.
     */
    public void jump(ClientPlayerEntity player) {
        if (player != null) {
            player.jump();
        }
    }

    /**
     * Toggle sneaking.
     */
    public void setSneaking(ClientPlayerEntity player, boolean sneaking) {
        if (player != null) {
            player.setSneaking(sneaking);
        }
    }

    // --- Getters ---

    public boolean isMoving() { return moving; }
    public Vec3d getTargetPosition() { return targetPosition; }
    public void setMovementSpeed(double speed) { this.movementSpeed = Math.min(1.0, Math.max(0.1, speed)); }
}
