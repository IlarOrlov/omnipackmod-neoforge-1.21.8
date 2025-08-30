package net.errantwanderer.omnipackmod;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

import net.neoforged.neoforge.event.tick.ServerTickEvent; // contains nested Pre/Post
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;

import net.minecraft.server.level.ServerPlayer;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(OmniPackMod.MOD_ID)
public class OmniPackMod {
    public static final String MOD_ID = "omnipackmod";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final SharedInventoryManager sharedInventoryManager = new SharedInventoryManager();

    public OmniPackMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this); // registers @SubscribeEvent instance methods
        modEventBus.addListener(this::addCreative);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
    }

    // This ServerStartingEvent extends ServerLifecycleEvent and DOES provide getServer()
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer(); // valid on NeoForge
        sharedInventoryManager.initOnServerStart(server);
        LOGGER.info("OmniPackMod: shared inventory manager initialized");
    }

    // Use the ServerTickEvent.Post nested class to run AFTER server tick.
    // ServerTickEvent.Post has getServer() and is fired only on the logical server.
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server != null) {
            sharedInventoryManager.onServerTick(server);
        }
    }

    // Player login / logout: use the PlayerEvent nested classes
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sharedInventoryManager.onPlayerJoin(player);
            LOGGER.info("OmniPackMod: player {} connected, synced shared inventory", player.getName().getString());
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sharedInventoryManager.onPlayerLeave(player);
            LOGGER.info("OmniPackMod: player {} disconnected", player.getName().getString());
        }
    }
}