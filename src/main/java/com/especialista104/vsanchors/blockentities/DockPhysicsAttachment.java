package com.especialista104.vsanchors.blockentities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.ships.ShipForcesInducer;
import org.valkyrienskies.core.impl.game.ships.PhysShipImpl;

/**
 * Ship attachment that applies docking forces via VS2's physics pipeline.
 *
 * VS2 automatically calls applyForces() on every ShipForcesInducer attached to a ship
 * during each physics step (run on VS2's physics thread, NOT the Minecraft server tick).
 * This is the only correct place to call applyInvariantForce / applyInvariantTorque.
 *
 * Forces applied:
 *  1. Linear pull toward (targetX, targetY, targetZ).
 *  2. Strong damping on all three rotation axes (X/Z to prevent tumbling, Y to slow spin).
 *  3. Corrective Y-axis torque: rotates the ship toward the nearest 90° yaw so it
 *     axis-aligns with the dock face. The ship-to-world quaternion is retrieved via
 *     reflection because the exact vscore API for rotation may vary; the fallback
 *     (if reflection fails) is strong Y damping only.
 */
public class DockPhysicsAttachment implements ShipForcesInducer {

    /** Whether this dock is actively pulling its ship. */
    @JsonIgnore public volatile boolean active = false;

    /** World-space target position. Updated each server tick. */
    @JsonIgnore public volatile double targetX, targetY, targetZ;

    /** Pull acceleration in m/s². Updated each server tick. */
    @JsonIgnore public volatile double pullAccel = 5.0;

    // Pre-allocated to avoid GC pressure on the physics thread
    @JsonIgnore private final Vector3d force  = new Vector3d();
    @JsonIgnore private final Vector3d torque = new Vector3d();

    public DockPhysicsAttachment() {}

    @Override
    public void applyForces(@NotNull PhysShip physShip) {
        if (!active) return;

        PhysShipImpl ship = (PhysShipImpl) physShip;

        double mass = ship.getInertia().getShipMass();
        if (mass <= 0) mass = 1000.0;

        double sx = ship.getPoseVel().getPos().x();
        double sy = ship.getPoseVel().getPos().y();
        double sz = ship.getPoseVel().getPos().z();

        double dx = targetX - sx;
        double dy = targetY - sy;
        double dz = targetZ - sz;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (distance < 0.01) return;

        // --- Linear pull force ---
        // Constant magnitude = mass × pullAccel, directed toward target.
        double scale = mass * pullAccel / distance;
        force.set(dx * scale, dy * scale, dz * scale);
        ship.applyInvariantForce(force);

        // --- Rotation stabilisation ---
        var omega = ship.getPoseVel().getOmega();
        double ox = omega.x(), oy = omega.y(), oz = omega.z();

        // Strong damping on X and Z axes: prevents the ship from tumbling / pitching.
        // Using a fixed per-axis coefficient rather than direction-normalised to
        // avoid zero-division when one component is dominant.
        double dampXZ = mass * 40.0;
        double dampY  = mass * 60.0;  // Y gets stronger damping (+ corrective below)
        torque.set(-ox * dampXZ, -oy * dampY, -oz * dampXZ);

        // Corrective Y-axis torque: rotate toward nearest 90° yaw so the ship
        // ends up axis-aligned against the dock face.
        double yawCorrection = computeYawCorrection(ship, mass);
        torque.y += yawCorrection;

        ship.applyInvariantTorque(torque);
    }

    /**
     * Returns a corrective torque (N·m) around the world Y axis to rotate the ship
     * toward the nearest 90° yaw multiple.
     *
     * The ship-to-world rotation quaternion is obtained via reflection because the
     * exact vscore method name may vary. Returns 0 if the quaternion cannot be read.
     */
    private double computeYawCorrection(PhysShipImpl ship, double mass) {
        try {
            var poseVel = ship.getPoseVel();

            // Try known method names for the ship-to-world rotation quaternion.
            Object q = null;
            for (String name : new String[]{"getShipToWorldRotation", "getOrientation", "getRotation"}) {
                try {
                    q = poseVel.getClass().getMethod(name).invoke(poseVel);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (q == null) return 0;

            // Extract quaternion components via reflection (JOML Quaterniondc / Quaternionfc).
            double qx = ((Number) q.getClass().getMethod("x").invoke(q)).doubleValue();
            double qy = ((Number) q.getClass().getMethod("y").invoke(q)).doubleValue();
            double qz = ((Number) q.getClass().getMethod("z").invoke(q)).doubleValue();
            double qw = ((Number) q.getClass().getMethod("w").invoke(q)).doubleValue();

            // Yaw = rotation around world Y axis (right-handed, Y-up).
            // Formula: atan2(2*(qw*qy + qx*qz), 1 - 2*(qy² + qz²))
            // (matches the JOML getEulerAnglesYXZ Y component convention)
            double yaw = Math.atan2(2.0 * (qw * qy + qx * qz),
                                    1.0 - 2.0 * (qy * qy + qz * qz));

            // Target = nearest 90° multiple
            double targetYaw = Math.round(yaw / (Math.PI / 2.0)) * (Math.PI / 2.0);

            // Shortest-path error, clamped to (-π, π]
            double err = targetYaw - yaw;
            if (err >  Math.PI) err -= 2.0 * Math.PI;
            if (err < -Math.PI) err += 2.0 * Math.PI;

            // PD-style corrective torque: proportional to error, derivative already
            // handled by the strong Y damping applied above.
            return err * mass * 10.0;

        } catch (Exception ignored) {
            return 0; // Method not found or invocation failed — only damping is used
        }
    }
}
