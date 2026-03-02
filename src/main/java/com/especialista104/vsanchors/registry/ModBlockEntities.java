package com.especialista104.vsanchors.registry;

import com.especialista104.vsanchors.blockentities.AnchorBlockEntity;
import com.especialista104.vsanchors.blockentities.DockBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Deferred register for all mod block entities. Add new block entity entries here. */
public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "vsanchors");

    /** Block entity type for the anchor block — stores anchored state. */
    public static final RegistryObject<BlockEntityType<AnchorBlockEntity>> ANCHOR_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("anchor_block_entity", () ->
                    BlockEntityType.Builder
                            .of(AnchorBlockEntity::new, ModBlocks.ANCHOR_BLOCK.get())
                            .build(null)
            );

    /** Block entity type for the dock block — handles ship detection and docking logic. */
    public static final RegistryObject<BlockEntityType<DockBlockEntity>> DOCK_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("dock_block_entity", () ->
                    BlockEntityType.Builder
                            .of(DockBlockEntity::new, ModBlocks.DOCK_BLOCK.get())
                            .build(null)
            );
}
