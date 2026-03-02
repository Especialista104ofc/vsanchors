package com.especialista104.vsanchors.blocks;

import com.especialista104.vsanchors.blockentities.AnchorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * A block that, when placed on a Valkyrien Skies ship and right-clicked,
 * toggles the ship between static (frozen) and dynamic (free) states.
 */
public class AnchorBlock extends BaseEntityBlock {

    public AnchorBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /**
     * Handles right-click interaction. Toggles the anchor state on the server side
     * and displays a status message on the player's action bar.
     */
    @Override
    public InteractionResult use(BlockState state, Level level,
                                 BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof AnchorBlockEntity anchor) {
                anchor.toggleAnchor(player);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AnchorBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
