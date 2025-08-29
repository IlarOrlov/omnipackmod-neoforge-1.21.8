package net.errantwanderer.omnipackmod.shared;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class SharedInventoryManager {
    private final Map<UUID, Integer> lastSeenHash = new HashMap<>();

    public SharedInventoryManager() {
        NeoForge.EVENT_BUS.register(this);
    }

    private SharedInventoryData data(ServerLevel level) {
        return SharedInventoryData.get(level.getServer().overworld());
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post e) {
        if (e.getEntity().level().isClientSide()) return;
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;

        ServerLevel level = sp.level();
        SharedInventoryData shared = data(level);

        int currentHash = hashOf(sp);
        Integer prev = lastSeenHash.put(sp.getUUID(), currentHash);

        boolean playerChanged = prev == null || prev != currentHash;
        boolean sharedChanged = false;

        if (playerChanged) {
            sharedChanged = shared.mergeFromPlayer(sp);
        }

        if (sharedChanged) {
            for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
                shared.writeToPlayer(other);
                other.containerMenu.broadcastChanges();
                lastSeenHash.put(other.getUUID(), hashOf(other));
            }
        } else if (!Objects.equals(prev, shared.hash())) {
            // player is out of date compared to shared snapshot
            shared.writeToPlayer(sp);
            sp.containerMenu.broadcastChanges();
            lastSeenHash.put(sp.getUUID(), hashOf(sp));
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        ServerLevel level = sp.level();
        SharedInventoryData shared = data(level);
        shared.writeToPlayer(sp);
        sp.containerMenu.broadcastChanges();
        lastSeenHash.put(sp.getUUID(), hashOf(sp));
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        ServerLevel level = sp.level();
        SharedInventoryData shared = data(level);
        shared.writeToPlayer(sp);
        sp.containerMenu.broadcastChanges();
        lastSeenHash.put(sp.getUUID(), hashOf(sp));
    }

    @SubscribeEvent
    public void onChangeDim(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        ServerLevel level = sp.level();
        SharedInventoryData shared = data(level);
        shared.writeToPlayer(sp);
        sp.containerMenu.broadcastChanges();
        lastSeenHash.put(sp.getUUID(), hashOf(sp));
    }

    @SubscribeEvent
    public void onPlayerDrops(LivingDropsEvent e) {
        if (e.getEntity() instanceof ServerPlayer) {
            e.setCanceled(true);
        }
    }

    private int hashOf(ServerPlayer sp) {
        int h = 1;
        for (int i = 0; i < 36; i++) {
            h = 31 * h + stackHash(sp.getInventory().getItem(i));
        }
        h = 31 * h + stackHash(sp.getItemBySlot(EquipmentSlot.HEAD));
        h = 31 * h + stackHash(sp.getItemBySlot(EquipmentSlot.CHEST));
        h = 31 * h + stackHash(sp.getItemBySlot(EquipmentSlot.LEGS));
        h = 31 * h + stackHash(sp.getItemBySlot(EquipmentSlot.FEET));
        h = 31 * h + stackHash(sp.getOffhandItem());
        return h;
    }

    private int stackHash(ItemStack s) {
        return s.isEmpty() ? 0 : (s.getItem().hashCode() * 37 + s.getCount());
    }

    public static void ensureRegistered(MinecraftServer server) {
        // No-op for now
    }
}