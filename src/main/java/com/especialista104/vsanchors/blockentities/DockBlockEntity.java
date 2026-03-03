package com.especialista104.vsanchors.blockentities;

import com.especialista104.vsanchors.registry.ModBlockEntities;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.valkyrienskies.core.impl.game.ships.ShipObjectServer;
import org.valkyrienskies.core.impl.game.ships.ShipObjectServerWorld;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Block entity for the Dock Block.
 *
 * State machine (persisted to NBT):
 *
 *   IDLE     (!dockActive && !docked)  — no ship being pulled
 *   PULLING  (dockActive && !docked)   — actively pulling closest ship
 *   DOCKED   (docked)                  — ship is static and locked
 *
 * Transitions:
 *   IDLE    → PULLING : right-click if a ship is within DOCK_RANGE
 *   PULLING → IDLE    : right-click (cancel) OR ship leaves range
 *   PULLING → DOCKED  : ship's primary AABB face reaches within SNAP_GAP of dock face
 *   DOCKED  → IDLE    : right-click
 *
 * This state machine fixes the "re-dock immediately after undock" bug: after
 * undocking the dock goes IDLE (dockActive=false) so serverTick does nothing
 * until the player clicks again.
 *
 * Edge-aligned docking:
 *   The target is calculated so that the ship's entity AABB face that is pointing
 *   toward the dock becomes flush with the dock block's face.  The dominant axis
 *   (whichever of X or Z has the larger ship-to-dock vector component) determines
 *   which pair of faces is aligned.  The snap condition checks only the primary-axis
 *   gap (not the combined XZ gap) so centering on the secondary axis is not required
 *   for the ship to lock.
 *
 * World AABB:
 *   ship.getWorldAABB() returns org.joml.primitives.AABBdc which is NOT in
 *   Minecraft's compile classpath.  We access it via reflection (getShipWorldAABB).
 */
public class DockBlockEntity extends BlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- Tuning constants ----

    /** Radius from the dock center within which ships are detected. */
    private static final double DOCK_RANGE = 10.0;

    /** Maximum pull acceleration (used when far away). */
    private static final double MAX_PULL_ACCEL = 15.0;

    /** Minimum pull acceleration (used when almost aligned). */
    private static final double MIN_PULL_ACCEL = 4.0;

    /**
     * Primary-axis gap (blocks) at which the ship snaps and locks.
     * This measures only the dominant-axis distance between the ship's AABB face
     * and the dock face, so the ship does not need to be centred on the secondary
     * axis to snap.
     */
    private static final double SNAP_GAP = 0.35;

    /** Log debug lines every N server ticks (100 = every 5 s). */
    private static final int LOG_INTERVAL = 100;

    // ---- Persistent state ----

    /**
     * Whether the dock is currently trying to pull a ship.
     * Toggled by right-click.  Ensures the dock does not re-pull immediately
     * after the player undocks a ship.
     */
    private boolean dockActive = false;

    /** True when a ship is locked to this dock. */
    private boolean docked = false;

    /** VS2 ID of the currently locked ship, or -1. */
    private long dockedShipId = -1L;

    // ---- Transient state (not persisted) ----

    /** VS2 ID of the ship being pulled (or -1). */
    private transient long trackedShipId = -1L;

    // -------------------------------------------------------------------------

    public DockBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DOCK_BLOCK_ENTITY.get(), pos, state);
    }

    // =========================================================================
    // Server tick
    // =========================================================================

    public static void serverTick(Level level, BlockPos pos, BlockState state, DockBlockEntity dock) {

        // Only run when the dock has been activated by the player and is not yet docked.
        if (!dock.dockActive || dock.docked) return;

        boolean doLog = (level.getGameTime() % LOG_INTERVAL == 0);

        ShipObjectServer ship = findClosestShip(level, pos, DOCK_RANGE);

        if (ship == null) {
            // No ship in range — deactivate attachment on whichever ship we were tracking.
            if (dock.trackedShipId != -1L) {
                ShipObjectServer last = findShipById(level, dock.trackedShipId);
                if (last != null) deactivateAttachment(last);
                dock.trackedShipId = -1L;
            }
            if (doLog) LOGGER.debug("[DockBlock] No ship within {} blocks of {}", DOCK_RANGE, pos);
            return;
        }

        // If the tracked ship changed, stop pulling the old one.
        if (dock.trackedShipId != -1L && dock.trackedShipId != ship.getId()) {
            ShipObjectServer last = findShipById(level, dock.trackedShipId);
            if (last != null) deactivateAttachment(last);
        }
        dock.trackedShipId = ship.getId();

        // Get or create the physics attachment on the target ship.
        DockPhysicsAttachment attachment = (DockPhysicsAttachment) ship.getAttachment(DockPhysicsAttachment.class);
        if (attachment == null) {
            attachment = new DockPhysicsAttachment();
            ship.saveAttachment(DockPhysicsAttachment.class, attachment);
            LOGGER.debug("[DockBlock] Created DockPhysicsAttachment on ship {}", ship.getId());
        }

        // ---- Edge-aligned target calculation ----
        //
        // We want the ship's nearest AABB face to be flush against the dock block face.
        // The "nearest face" is determined by whichever axis (X or Z) has the larger
        // component in the dock→ship vector.

        double sx = ship.getTransform().getPositionInWorld().x();
        double sy = ship.getTransform().getPositionInWorld().y();
        double sz = ship.getTransform().getPositionInWorld().z();

        // Retrieve world AABB via reflection — AABBdc is not on the compile classpath.
        double[] aabb = getShipWorldAABB(ship);
        if (aabb == null) {
            if (doLog) LOGGER.warn("[DockBlock] Cannot retrieve AABB for ship {}", ship.getId());
            return;
        }
        double aabbMinX = aabb[0], aabbMaxX = aabb[1];
        double aabbMinZ = aabb[2], aabbMaxZ = aabb[3];

        // Dock block faces and centre
        double dockCX        = pos.getX() + 0.5;
        double dockCZ        = pos.getZ() + 0.5;
        double dockFaceXPos  = pos.getX() + 1.0;  // +X face
        double dockFaceXNeg  = pos.getX();          // -X face
        double dockFaceZPos  = pos.getZ() + 1.0;   // +Z face
        double dockFaceZNeg  = pos.getZ();           // -Z face

        double relX = sx - dockCX;
        double relZ = sz - dockCZ;
        boolean dominantX = (Math.abs(relX) >= Math.abs(relZ));

        double tx, tz;
        if (dominantX) {
            if (relX > 0) {
                // Ship is on the +X side → its -X face (minX) touches the dock's +X face
                tx = dockFaceXPos + (sx - aabbMinX);
            } else {
                // Ship is on the -X side → its +X face (maxX) touches the dock's -X face
                tx = dockFaceXNeg - (aabbMaxX - sx);
            }
            tz = dockCZ; // centre on Z (cosmetic; does not affect snap condition)
        } else {
            if (relZ > 0) {
                // Ship is on the +Z side → its -Z face (minZ) touches the dock's +Z face
                tz = dockFaceZPos + (sz - aabbMinZ);
            } else {
                // Ship is on the -Z side → its +Z face (maxZ) touches the dock's -Z face
                tz = dockFaceZNeg - (aabbMaxZ - sz);
            }
            tx = dockCX; // centre on X (cosmetic)
        }

        double ty = sy; // buoyancy handles Y; no vertical force needed

        double dxAlign = tx - sx;
        double dzAlign = tz - sz;

        // Primary-axis gap: distance the ship still needs to travel on the dominant
        // axis before its face is flush.  We snap on this value alone so the ship
        // does not need to be perfectly centred on the secondary axis.
        double primaryGap = dominantX ? Math.abs(dxAlign) : Math.abs(dzAlign);

        // Combined gap used for pull-accel scaling and logging only.
        double totalGap = Math.sqrt(dxAlign * dxAlign + dzAlign * dzAlign);

        if (doLog) {
            LOGGER.debug("[DockBlock] Ship {} | primaryGap={} totalGap={} | tx={} tz={}",
                    ship.getId(),
                    String.format("%.3f", primaryGap),
                    String.format("%.3f", totalGap),
                    String.format("%.2f", tx),
                    String.format("%.2f", tz));
        }

        if (primaryGap < SNAP_GAP) {
            // ---- SNAP AND LOCK ----
            deactivateAttachment(ship); // stop applying forces before freezing
            ship.setStatic(true);

            dock.docked        = true;
            dock.dockActive    = false; // reset so player must click again to pull
            dock.dockedShipId  = ship.getId();
            dock.trackedShipId = -1L;
            dock.setChanged();

            LOGGER.debug("[DockBlock] Ship {} snapped and locked at {}", ship.getId(), pos);

            level.players().forEach(p -> {
                if (p.blockPosition().distSqr(pos) < 200) {
                    p.displayClientMessage(
                            Component.translatable("message.vsanchors.ship_docked"), true);
                }
            });

        } else {
            // ---- CONTINUE PULLING ----
            // Scale acceleration: stronger when far, gentler as the ship closes in.
            double t = Math.min(totalGap / DOCK_RANGE, 1.0);
            double accel = MIN_PULL_ACCEL + t * (MAX_PULL_ACCEL - MIN_PULL_ACCEL);

            attachment.targetX   = tx;
            attachment.targetY   = ty;
            attachment.targetZ   = tz;
            attachment.pullAccel = accel;
            attachment.active    = true;
        }
    }

    // =========================================================================
    // Right-click handler
    // =========================================================================

    /**
     * Called when the player right-clicks the dock block.
     *
     *   DOCKED   → undock the ship, go IDLE
     *   PULLING  → cancel pull, go IDLE
     *   IDLE     → start pulling if a ship is within range, otherwise show info
     */
    public void toggleDock(Player player) {
        if (level == null || level.isClientSide()) return;

        if (docked) {
            // ---- UNDOCK ----
            if (dockedShipId != -1L) {
                ShipObjectServer ship = findShipById(level, dockedShipId);
                if (ship != null) {
                    ship.setStatic(false);
                    deactivateAttachment(ship);
                    LOGGER.debug("[DockBlock] Undocked ship {} at {}", dockedShipId, worldPosition);
                }
            }
            docked       = false;
            dockedShipId = -1L;
            dockActive   = false;
            setChanged();
            player.displayClientMessage(
                    Component.translatable("message.vsanchors.ship_undocked"), true);

        } else if (dockActive) {
            // ---- CANCEL PULL ----
            if (trackedShipId != -1L) {
                ShipObjectServer ship = findShipById(level, trackedShipId);
                if (ship != null) deactivateAttachment(ship);
                trackedShipId = -1L;
            }
            dockActive = false;
            setChanged();
            player.displayClientMessage(
                    Component.literal("[Dock] Pull cancelled."), true);

        } else {
            // ---- ACTIVATE PULL ----
            ShipObjectServerWorld serverWorld = getServerWorld(level);
            if (serverWorld == null) {
                player.displayClientMessage(
                        Component.literal("[Dock] VS2 ship world not available."), true);
                return;
            }

            // Count total loaded ships and find nearest
            int total = 0;
            ShipObjectServer closest   = null;
            double          closestDist = Double.MAX_VALUE;

            double dockCX = worldPosition.getX() + 0.5;
            double dockCZ = worldPosition.getZ() + 0.5;

            for (var ship : serverWorld.getLoadedShips()) {
                total++;
                double sx = ship.getTransform().getPositionInWorld().x();
                double sz = ship.getTransform().getPositionInWorld().z();
                double d  = Math.sqrt((dockCX - sx) * (dockCX - sx) + (dockCZ - sz) * (dockCZ - sz));
                if (d < closestDist) { closestDist = d; closest = ship; }
            }

            if (closest == null) {
                player.displayClientMessage(
                        Component.literal("[Dock] No ships loaded. Create a ship with the VS2 physics infuser first."),
                        true);
                return;
            }

            if (closestDist > DOCK_RANGE) {
                // Show info but do not activate — ship is too far
                double[] bounds = getShipWorldAABB(closest);
                String aabbStr = bounds != null
                        ? String.format("%.1fx%.1f", bounds[1] - bounds[0], bounds[3] - bounds[2])
                        : "?";
                player.displayClientMessage(
                        Component.literal(String.format(
                                "[Dock] Nearest ship is %.1f blocks away (need ≤%.0f) | AABB: %s | ships: %d",
                                closestDist, DOCK_RANGE, aabbStr, total)),
                        true);
                return;
            }

            // Ship is in range — activate pull
            dockActive = true;
            setChanged();
            player.displayClientMessage(
                    Component.literal(String.format("[Dock] Pulling ship (%.1f blocks away)…", closestDist)),
                    true);
        }
    }

    // =========================================================================
    // Static helpers
    // =========================================================================

    /**
     * Returns the {@link ShipObjectServerWorld} implementation.
     * We need the impl class (not the public interface) because only it exposes
     * {@code getLoadedShips()}.  {@code getAllShips()} returns {@code ShipData}
     * objects (unloaded ships included), not {@code LoadedServerShip} / {@code ShipObjectServer}.
     */
    @Nullable
    private static ShipObjectServerWorld getServerWorld(Level level) {
        var core = VSGameUtilsKt.getShipObjectWorld(level);
        if (core instanceof ShipObjectServerWorld w) return w;
        return null;
    }

    /**
     * Returns the closest loaded ship whose centre is within {@code range} blocks
     * of {@code pos}, or null if none.
     */
    @Nullable
    private static ShipObjectServer findClosestShip(Level level, BlockPos pos, double range) {
        ShipObjectServerWorld sw = getServerWorld(level);
        if (sw == null) return null;

        ShipObjectServer best     = null;
        double           bestDist = Double.MAX_VALUE;

        for (var ship : sw.getLoadedShips()) {
            double sx = ship.getTransform().getPositionInWorld().x();
            double sy = ship.getTransform().getPositionInWorld().y();
            double sz = ship.getTransform().getPositionInWorld().z();
            double dx = sx - pos.getX(), dy = sy - pos.getY(), dz = sz - pos.getZ();
            double d  = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d <= range && d < bestDist) { best = ship; bestDist = d; }
        }
        return best;
    }

    /** Finds a loaded ship by VS2 ship ID, or null. */
    @Nullable
    private static ShipObjectServer findShipById(Level level, long id) {
        ShipObjectServerWorld sw = getServerWorld(level);
        if (sw == null) return null;
        for (var ship : sw.getLoadedShips()) {
            if (ship.getId() == id) return ship;
        }
        return null;
    }

    /** Stops the DockPhysicsAttachment on {@code ship} if one exists. */
    private static void deactivateAttachment(ShipObjectServer ship) {
        DockPhysicsAttachment att = (DockPhysicsAttachment) ship.getAttachment(DockPhysicsAttachment.class);
        if (att != null) att.active = false;
    }

    /**
     * Returns the world-space AABB of a ship as {@code {minX, maxX, minZ, maxZ}},
     * or null if the AABB cannot be retrieved.
     *
     * {@code ship.getWorldAABB()} returns {@code org.joml.primitives.AABBdc}, which
     * lives in the {@code joml-primitives} artifact.  That artifact is NOT on
     * Minecraft's compile classpath (it is a separate jar from {@code joml}).
     * We therefore call the method and its accessors via reflection.  At runtime
     * VS2 ensures the class is present, so this works without adding a new dependency.
     */
    @Nullable
    private static double[] getShipWorldAABB(ShipObjectServer ship) {
        try {
            Object aabb = ship.getClass().getMethod("getWorldAABB").invoke(ship);
            double minX = ((Number) aabb.getClass().getMethod("minX").invoke(aabb)).doubleValue();
            double maxX = ((Number) aabb.getClass().getMethod("maxX").invoke(aabb)).doubleValue();
            double minZ = ((Number) aabb.getClass().getMethod("minZ").invoke(aabb)).doubleValue();
            double maxZ = ((Number) aabb.getClass().getMethod("maxZ").invoke(aabb)).doubleValue();
            return new double[]{ minX, maxX, minZ, maxZ };
        } catch (Exception e) {
            LOGGER.error("[DockBlock] Reflection error getting ship AABB: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // NBT persistence
    // =========================================================================

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("docked",     docked);
        tag.putBoolean("dockActive", dockActive);
        tag.putLong   ("dockedShipId", dockedShipId);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        docked       = tag.getBoolean("docked");
        dockActive   = tag.getBoolean("dockActive");
        dockedShipId = tag.contains("dockedShipId") ? tag.getLong("dockedShipId") : -1L;
    }
}
