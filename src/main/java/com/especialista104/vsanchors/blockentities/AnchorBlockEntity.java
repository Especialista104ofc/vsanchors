package com.especialista104.vsanchors.blockentities;

import com.especialista104.vsanchors.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Block entity for the Anchor Block.
 * Stores the anchored state and controls whether the parent ship is frozen (static) or free.
 */
public class AnchorBlockEntity extends BlockEntity {

    private boolean anchored = false;

    public AnchorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ANCHOR_BLOCK_ENTITY.get(), pos, state);
    }

    /**
     * Toggles the anchor on/off. When anchored, the ship is set to static (frozen in place).
     * When released, the ship resumes normal physics.
     *
     * @param player the player who toggled the anchor, used for feedback messages
     */
    public void toggleAnchor(Player player) {
        if (level == null || level.isClientSide()) return;

        ServerShip ship = (ServerShip) VSGameUtilsKt.getShipManagingPos(level, worldPosition);

        if (ship != null) {
            anchored = !anchored;
            ship.setStatic(anchored);
            setChanged();

            player.displayClientMessage(
                    Component.translatable(anchored
                            ? "message.vsanchors.anchor_locked"
                            : "message.vsanchors.anchor_raised"),
                    true
            );
        } else {
            player.displayClientMessage(
                    Component.translatable("message.vsanchors.not_on_ship"),
                    true
            );
        }
    }

    public boolean isAnchored() {
        return anchored;
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("anchored", anchored);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        anchored = tag.getBoolean("anchored");
    }
}
