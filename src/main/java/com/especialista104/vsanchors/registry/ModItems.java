package com.especialista104.vsanchors.registry;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Deferred register for all mod items. Add new item entries here. */
public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, "vsanchors");

    /** Block item for the anchor block — allows it to be carried in inventory. */
    public static final RegistryObject<Item> ANCHOR_BLOCK_ITEM =
            ITEMS.register("anchor_block", () ->
                    new BlockItem(ModBlocks.ANCHOR_BLOCK.get(), new Item.Properties())
            );

    /** Block item for the dock block — allows it to be carried in inventory. */
    public static final RegistryObject<Item> DOCK_BLOCK_ITEM =
            ITEMS.register("dock_block", () ->
                    new BlockItem(ModBlocks.DOCK_BLOCK.get(), new Item.Properties())
            );
}
