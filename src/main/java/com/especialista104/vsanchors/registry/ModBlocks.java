package com.especialista104.vsanchors.registry;

import com.especialista104.vsanchors.blocks.AnchorBlock;
import com.especialista104.vsanchors.blocks.DockBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Deferred register for all mod blocks. Add new block entries here. */
public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, "vsanchors");

    /** Anchor block — freezes the ship it is placed on when activated. */
    public static final RegistryObject<Block> ANCHOR_BLOCK =
            BLOCKS.register("anchor_block", () -> new AnchorBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(5.0f, 6.0f)
                            .sound(SoundType.ANVIL)
                            .requiresCorrectToolForDrops()
            ));

    /** Dock block — pulls nearby ships in and locks them at the docking station. */
    public static final RegistryObject<Block> DOCK_BLOCK =
            BLOCKS.register("dock_block", () -> new DockBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(4.0f, 6.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()
            ));
}
