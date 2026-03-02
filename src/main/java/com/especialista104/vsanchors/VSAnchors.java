package com.especialista104.vsanchors;

import com.especialista104.vsanchors.registry.ModBlockEntities;
import com.especialista104.vsanchors.registry.ModBlocks;
import com.especialista104.vsanchors.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

/**
 * Main mod class for VS Anchors.
 * Provides anchor and dock blocks that integrate with Valkyrien Skies to control ship physics.
 */
@Mod(VSAnchors.MODID)
public class VSAnchors {

    public static final String MODID = "vsanchors";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Registry for the mod's creative mode tabs. */
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    /**
     * Custom creative tab for all VS Anchors items.
     * Uses the anchor block as the tab icon and automatically lists all mod items.
     */
    public static final RegistryObject<CreativeModeTab> VS_ANCHORS_TAB =
            CREATIVE_MODE_TABS.register("vs_anchors_tab", () -> CreativeModeTab.builder()
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .title(Component.translatable("itemGroup.vsanchors.vs_anchors_tab"))
                    .icon(() -> ModItems.ANCHOR_BLOCK_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        // Add all mod items here. Update this when adding new blocks/items.
                        output.accept(ModItems.ANCHOR_BLOCK_ITEM.get());
                        output.accept(ModItems.DOCK_BLOCK_ITEM.get());
                    })
                    .build());

    public VSAnchors() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);

        // Register all deferred registers on the mod event bus
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("VS Anchors initialized");
    }
}
