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
 * Behaviour summary:
 *  - Scans for any loaded ship within DOCK_RANGE each server tick.
 *  - Pulls the closest ship toward the dock by setting a {@link DockPhysicsAttachment}
 *    on it. VS2 calls the attachment's applyForces() during its own physics loop.
 *  - When the ship center reaches SNAP_DISTANCE the attachment is deactivated, the
 *    ship is frozen with setStatic(true), and the dock marks itself as docked.
 *  - Right-click while docked   → releases the ship (undock).
 *  - Right-click while undocked → shows debug info about nearby ships.
 *
 * Why ShipObjectServer instead of LoadedServerShip?
 *  VS2's getAllShips() returns ShipData objects (for ALL ships, including unloaded ones).
 *  ShipData does NOT implement LoadedServerShip so instanceof checks always fail.
 *  getLoadedShips() (only on ShipObjectServerWorld) returns ShipObjectServer instances,
 *  which represent ships that are currently active in the world. All methods needed
 *  (getTransform, getId, getAttachment, saveAttachment, setStatic) are on ShipObjectServer.
 */
public class DockBlockEntity extends BlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Radius (in blocks) from the dock center within which ships are pulled. */
    private static final double DOCK_RANGE = 10.0;

    /**
     * Maximum pull acceleration (m/s²) applied when the ship is at the far edge of DOCK_RANGE.
     * Force = mass × accel, so it scales automatically with ship size.
     */
    private static final double MAX_PULL_ACCEL = 8.0;

    /** Minimum pull acceleration applied just before the snap threshold. */
    private static final double MIN_PULL_ACCEL = 2.0;

    /**
     * Distance at which the ship snaps and is locked in place.
     * Set to 3.5 because the ship CENTER is ~2.7 blocks from the dock even
     * when the ship visually appears directly adjacent to the block.
     */
    private static final double SNAP_DISTANCE = 3.5;

    /** How often to emit debug log lines (in ticks). 100 = every 5 seconds. */
    private static final int LOG_INTERVAL = 100;

    // --- Persistent state ---

    /** True when a ship is currently locked to this dock. */
    private boolean docked = false;

    /**
     * VS2 ship ID of the currently docked ship, or -1 if none.
     * Stored so we can find and release the ship on undock even across ticks.
     */
    private long dockedShipId = -1L;

    // --- Transient state (not saved, reconstructed each tick) ---

    /**
     * VS2 ship ID of the ship currently being pulled (not yet snapped).
     * Not persisted; re-established by the first server tick after a world reload.
     */
    private transient long trackedShipId = -1L;

    public DockBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DOCK_BLOCK_ENTITY.get(), pos, state);
    }

    // -------------------------------------------------------------------------
    // Server tick
    // -------------------------------------------------------------------------

    /**
     * Called every server tick. Finds the nearest ship in range, manages the
     * {@link DockPhysicsAttachment} lifecycle, and triggers snap-and-lock.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, DockBlockEntity dock) {
        if (dock.docked) return;

        boolean doLog = (level.getGameTime() % LOG_INTERVAL == 0);

        // Dock center target in world space (one block above the dock face)
        double tx = pos.getX() + 0.5;
        double ty = pos.getY() + 1.0;
        double tz = pos.getZ() + 0.5;

        ShipObjectServer ship = findClosestShip(level, pos, DOCK_RANGE);

        if (ship == null) {
            // No ship in range — deactivate any previously tracked ship's attachment
            if (dock.trackedShipId != -1L) {
                ShipObjectServer last = findShipById(level, dock.trackedShipId);
                if (last != null) {
                    DockPhysicsAttachment att = (DockPhysicsAttachment) last.getAttachment(DockPhysicsAttachment.class);
                    if (att != null) att.active = false;
                }
                dock.trackedShipId = -1L;
            }
            if (doLog) LOGGER.debug("[DockBlock] No ship within {} blocks of {}", DOCK_RANGE, pos);
            return;
        }

        // If the tracked ship changed, deactivate the old one's attachment
        if (dock.trackedShipId != -1L && dock.trackedShipId != ship.getId()) {
            ShipObjectServer last = findShipById(level, dock.trackedShipId);
            if (last != null) {
                DockPhysicsAttachment att = (DockPhysicsAttachment) last.getAttachment(DockPhysicsAttachment.class);
                if (att != null) att.active = false;
            }
        }
        dock.trackedShipId = ship.getId();

        // Get or create the physics attachment on this ship
        DockPhysicsAttachment attachment = (DockPhysicsAttachment) ship.getAttachment(DockPhysicsAttachment.class);
        if (attachment == null) {
            attachment = new DockPhysicsAttachment();
            ship.saveAttachment(DockPhysicsAttachment.class, attachment);
            LOGGER.debug("[DockBlock] Created DockPhysicsAttachment on ship {}", ship.getId());
        }

        // Measure distance from ship center to dock target
        double sx = ship.getTransform().getPositionInWorld().x();
        double sy = ship.getTransform().getPositionInWorld().y();
        double sz = ship.getTransform().getPositionInWorld().z();
        double dist = Math.sqrt(
                (tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz)
        );

        if (doLog) LOGGER.debug("[DockBlock] Ship {} distance {}", ship.getId(), String.format("%.2f", dist));

        if (dist < SNAP_DISTANCE) {
            // --- Snap and lock ---
            attachment.active = false;

            // setStatic is available directly on ShipObjectServer
            ship.setStatic(true);

            dock.docked       = true;
            dock.dockedShipId = ship.getId();
            dock.trackedShipId = -1L;
            dock.setChanged();

            LOGGER.debug("[DockBlock] Ship {} locked (setStatic) at {}", ship.getId(), pos);

            level.players().forEach(p -> {
                if (p.blockPosition().distSqr(pos) < 200) {
                    p.displayClientMessage(
                            Component.translatable("message.vsanchors.ship_docked"), true
                    );
                }
            });
        } else {
            // --- Pull toward dock ---
            // Acceleration scales linearly: MAX at DOCK_RANGE edge, MIN near SNAP_DISTANCE
            double t = Math.min((dist - SNAP_DISTANCE) / (DOCK_RANGE - SNAP_DISTANCE), 1.0);
            double accel = MIN_PULL_ACCEL + t * (MAX_PULL_ACCEL - MIN_PULL_ACCEL);

            attachment.targetX   = tx;
            attachment.targetY   = ty;
            attachment.targetZ   = tz;
            attachment.pullAccel = accel;
            attachment.active    = true;

            if (doLog) LOGGER.debug("[DockBlock] Pulling ship {} accel={} dist={}",
                    ship.getId(),
                    String.format("%.2f", accel),
                    String.format("%.2f", dist));
        }
    }

    // -------------------------------------------------------------------------
    // Right-click handler
    // -------------------------------------------------------------------------

    /**
     * Called when a player right-clicks the dock block.
     *  - If docked:     releases the ship (undock).
     *  - If not docked: shows debug info about nearby ships.
     */
    public void toggleDock(Player player) {
        if (level == null || level.isClientSide()) return;

        if (docked) {
            // --- Undock ---
            if (dockedShipId != -1L) {
                ShipObjectServer ship = findShipById(level, dockedShipId);
                if (ship != null) {
                    ship.setStatic(false);
                    LOGGER.debug("[DockBlock] Undocked ship {} at {}", dockedShipId, worldPosition);
                    DockPhysicsAttachment att = (DockPhysicsAttachment) ship.getAttachment(DockPhysicsAttachment.class);
                    if (att != null) att.active = false;
                }
            }

            docked       = false;
            dockedShipId = -1L;
            setChanged();
            player.displayClientMessage(
                    Component.translatable("message.vsanchors.ship_undocked"), true
            );
        } else {
            // --- Debug: show ship scan results ---
            ShipObjectServerWorld serverWorld = getServerWorld(level);
            if (serverWorld == null) {
                player.displayClientMessage(
                        Component.literal("[Debug] Ship world is NULL - VS2 not initialised?"), true);
                return;
            }

            int totalShips = 0;
            ShipObjectServer closest = null;
            double closestDist = Double.MAX_VALUE;

            double tx = worldPosition.getX() + 0.5;
            double ty = worldPosition.getY() + 1.0;
            double tz = worldPosition.getZ() + 0.5;

            for (var ship : serverWorld.getLoadedShips()) {
                totalShips++;
                double sx = ship.getTransform().getPositionInWorld().x();
                double sy = ship.getTransform().getPositionInWorld().y();
                double sz = ship.getTransform().getPositionInWorld().z();
                double d = Math.sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz));
                if (d < closestDist) { closestDist = d; closest = ship; }
            }

            if (closest == null) {
                player.displayClientMessage(
                        Component.literal("[Debug] No ships loaded. Create a ship with the VS2 physics infuser!"),
                        true
                );
                return;
            }

            player.displayClientMessage(
                    Component.literal(String.format(
                            "[Debug] Closest ship %.1f blocks away (dock range: %.0f) | total loaded=%d",
                            closestDist, DOCK_RANGE, totalShips)),
                    true
            );
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Casts the ship world to {@link ShipObjectServerWorld} (the impl class that exposes
     * getLoadedShips(), which returns actual ShipObjectServer instances).
     * getAllShips() returns ShipData objects that do NOT pass instanceof LoadedServerShip,
     * so we must use the impl-level getLoadedShips() instead.
     */
    @Nullable
    private static ShipObjectServerWorld getServerWorld(Level level) {
        var shipWorldCore = VSGameUtilsKt.getShipObjectWorld(level);
        if (shipWorldCore instanceof ShipObjectServerWorld w) return w;
        return null;
    }

    /**
     * Returns the closest loaded ship within {@code range} blocks of {@code pos},
     * or null if none is found.
     */
    @Nullable
    private static ShipObjectServer findClosestShip(Level level, BlockPos pos, double range) {
        ShipObjectServerWorld serverWorld = getServerWorld(level);
        if (serverWorld == null) return null;

        ShipObjectServer closest     = null;
        double           closestDist = Double.MAX_VALUE;

        for (var ship : serverWorld.getLoadedShips()) {
            double sx = ship.getTransform().getPositionInWorld().x();
            double sy = ship.getTransform().getPositionInWorld().y();
            double sz = ship.getTransform().getPositionInWorld().z();
            double dx = sx - pos.getX(), dy = sy - pos.getY(), dz = sz - pos.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist <= range && dist < closestDist) {
                closest     = ship;
                closestDist = dist;
            }
        }

        return closest;
    }

    /**
     * Finds a loaded ship by its VS2 ID, or null if not found.
     */
    @Nullable
    private static ShipObjectServer findShipById(Level level, long id) {
        ShipObjectServerWorld serverWorld = getServerWorld(level);
        if (serverWorld == null) return null;

        for (var ship : serverWorld.getLoadedShips()) {
            if (ship.getId() == id) return ship;
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // NBT persistence
    // -------------------------------------------------------------------------

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("docked", docked);
        tag.putLong("dockedShipId", dockedShipId);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        docked       = tag.getBoolean("docked");
        dockedShipId = tag.contains("dockedShipId") ? tag.getLong("dockedShipId") : -1L;
    }
}
