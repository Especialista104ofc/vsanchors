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
 * This is the only correct place to call applyInvariantForce / applyInvariantTorque,
 * which live on PhysShipImpl (the internal implementation class).
 *
 * The server tick in DockBlockEntity sets target position and pullAccel each game tick.
 * Fields are volatile so the physics thread always sees the latest values.
 *
 * Attach to a ship via:
 *   ship.saveAttachment(DockPhysicsAttachment.class, new DockPhysicsAttachment())
 * Retrieve via:
 *   ship.getAttachment(DockPhysicsAttachment.class)
 */
public class DockPhysicsAttachment implements ShipForcesInducer {

    /**
     * Whether this dock is actively pulling its ship.
     * Set to false to stop all force application (e.g. after snap or undock).
     */
    @JsonIgnore
    public volatile boolean active = false;

    /** World-space target position (dock block center). Updated each server tick. */
    @JsonIgnore
    public volatile double targetX, targetY, targetZ;

    /**
     * Desired pull acceleration in m/s².
     * Updated each server tick based on distance (farther = stronger pull).
     */
    @JsonIgnore
    public volatile double pullAccel = 5.0;

    // Pre-allocated to avoid GC pressure on the physics thread — not persisted
    @JsonIgnore private final Vector3d force  = new Vector3d();
    @JsonIgnore private final Vector3d torque = new Vector3d();

    /** Required no-arg constructor for Jackson serialization. */
    public DockPhysicsAttachment() {}

    /**
     * Called by VS2 on each physics tick for the ship this is attached to.
     * Applies a pull force toward the target and a counter-torque to dampen spin.
     */
    @Override
    public void applyForces(@NotNull PhysShip physShip) {
        if (!active) return;

        // PhysShipImpl is the only class with applyInvariantForce/Torque
        // and with getInertia() / getPoseVel() for mass and velocity data
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

        if (distance < 0.01) return; // Already at target, nothing to do

        // Force = mass × acceleration, direction toward target (normalized by distance)
        double scale = mass * pullAccel / distance;
        force.set(dx * scale, dy * scale, dz * scale);
        ship.applyInvariantForce(force);

        // Counter-torque: dampen angular velocity to prevent spinning during approach
        var omega = ship.getPoseVel().getOmega();
        double ox = omega.x(), oy = omega.y(), oz = omega.z();
        double omegaLen = Math.sqrt(ox * ox + oy * oy + oz * oz);
        if (omegaLen > 0.01) {
            double damp = Math.min(omegaLen * mass * 2.0, mass * 10.0);
            torque.set(
                    -(ox / omegaLen) * damp,
                    -(oy / omegaLen) * damp,
                    -(oz / omegaLen) * damp
            );
            ship.applyInvariantTorque(torque);
        }
    }
}
