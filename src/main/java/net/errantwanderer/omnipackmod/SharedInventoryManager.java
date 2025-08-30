package net.errantwanderer.omnipackmod;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;

public class SharedInventoryManager {
    // main (36) + armor (4) + offhand (1) = 41
    public static final int SHARED_SLOT_COUNT = 41;

    private final NonNullList<ItemStack> sharedInventory = NonNullList.withSize(SHARED_SLOT_COUNT, ItemStack.EMPTY);
    private final Map<UUID, ItemStack[]> lastSnapshotPerPlayer = new HashMap<>();
    private final ConcurrentLinkedQueue<SlotChangeRequest> requestQueue = new ConcurrentLinkedQueue<>();
    private final Object applyLock = new Object();
    private volatile long lastProcessedTs = 0L;

    public SharedInventoryManager() { }

    public void initOnServerStart(MinecraftServer server) {
        // TODO: load persisted inventory if/when you want to persist across restarts
    }

    public void onPlayerJoin(ServerPlayer player) {
        UUID id = player.getUUID();
        lastSnapshotPerPlayer.put(id, copySharedToArray());

        // Apply authoritative inventory server-side
        applySharedToPlayerInventory(player);

        // Send per-slot update packets so client UI shows authoritative inventory
        sendFullInventoryToPlayer(player);
    }

    public void onPlayerLeave(ServerPlayer player) {
        lastSnapshotPerPlayer.remove(player.getUUID());
    }

    // Call from ServerTickEvent.Post (server tick)
    public void onServerTick(MinecraftServer server) {
        // 1) detect diffs
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            detectAndEnqueuePlayerDiff(p);
        }

        // 2) apply queued requests sequentially
        processQueuedRequests();

        // 3) if changed, broadcast authoritative inventory to all
        if (lastProcessedTs != 0L) {
            broadcastSharedInventoryToAll(server);
            lastProcessedTs = 0L;
        }
    }

    /* -------------------------
       Diff detection -> requests
       ------------------------- */

    private void detectAndEnqueuePlayerDiff(ServerPlayer player) {
        UUID id = player.getUUID();
        ItemStack[] prev = lastSnapshotPerPlayer.get(id);
        ItemStack[] currentSnapshot = takePlayerInventorySnapshot(player);

        if (prev == null) {
            lastSnapshotPerPlayer.put(id, currentSnapshot);
            return;
        }

        for (int slot = 0; slot < SHARED_SLOT_COUNT; slot++) {
            ItemStack oldStack = prev[slot];
            ItemStack newStack = currentSnapshot[slot];

            // Use ItemStack.matches (checks item, count and components in modern mappings)
            if (!ItemStack.matches(oldStack, newStack)) {
                // enqueue the player's desired state for that slot
                requestQueue.add(new SlotChangeRequest(id, slot, newStack.copy(), System.currentTimeMillis()));
            }
        }

        lastSnapshotPerPlayer.put(id, currentSnapshot);
    }

    /* -------------------------
       Request processing
       ------------------------- */

    private void processQueuedRequests() {
        if (requestQueue.isEmpty()) return;

        synchronized (applyLock) {
            SlotChangeRequest req;
            while ((req = requestQueue.poll()) != null) {
                applyRequestToSharedInventory(req);
                lastProcessedTs = req.timestamp;
            }
        }
    }

    private void applyRequestToSharedInventory(SlotChangeRequest req) {
        int slot = req.slotIndex;
        if (slot < 0 || slot >= SHARED_SLOT_COUNT) return;

        ItemStack requestedStack = req.item;
        if (!requestedStack.isEmpty()) {
            int max = requestedStack.getMaxStackSize();
            if (requestedStack.getCount() > max) {
                requestedStack.setCount(max);
            }
        }

        sharedInventory.set(slot, requestedStack.copy());
    }

    /* -------------------------
       Sync helpers
       ------------------------- */

    private void broadcastSharedInventoryToAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            applySharedToPlayerInventory(p);
            sendFullInventoryToPlayer(p);
            lastSnapshotPerPlayer.put(p.getUUID(), copySharedToArray());
        }
    }

    private void applySharedToPlayerInventory(ServerPlayer p) {
        Inventory inv = p.getInventory(); // net.minecraft.world.entity.player.Inventory

        // Inventory implements Container-like API: use setItem/getItem
        int size = Math.min(SHARED_SLOT_COUNT, inv.getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            ItemStack s = sharedInventory.get(slot);
            inv.setItem(slot, s.copy());
        }

        // mark changed on the inventory so server knows it's been updated
        inv.setChanged(); // Inventory has setChanged() in modern mappings
    }

    private void sendFullInventoryToPlayer(ServerPlayer p) {
        Inventory inv = p.getInventory();
        int size = Math.min(SHARED_SLOT_COUNT, inv.getContainerSize());

        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inv.getItem(slot);
            ClientboundSetPlayerInventoryPacket pkt = new ClientboundSetPlayerInventoryPacket(slot, stack);
            p.connection.send(pkt);
        }
        // Note: if you ever see visual glitches when a player has an open container,
        // prefer sending the player's current open container's update packet instead.
    }

    /* -------------------------
       Snapshots & utils
       ------------------------- */

    private ItemStack[] takePlayerInventorySnapshot(ServerPlayer p) {
        Inventory inv = p.getInventory();
        int size = Math.min(SHARED_SLOT_COUNT, inv.getContainerSize());
        ItemStack[] snap = new ItemStack[SHARED_SLOT_COUNT];
        for (int slot = 0; slot < SHARED_SLOT_COUNT; slot++) {
            if (slot < size) {
                ItemStack s = inv.getItem(slot);
                snap[slot] = (s == null) ? ItemStack.EMPTY : s.copy();
            } else {
                snap[slot] = ItemStack.EMPTY;
            }
        }
        return snap;
    }

    private ItemStack[] copySharedToArray() {
        ItemStack[] arr = new ItemStack[SHARED_SLOT_COUNT];
        for (int i = 0; i < SHARED_SLOT_COUNT; i++) arr[i] = sharedInventory.get(i).copy();
        return arr;
    }

    /* -------------------------
       Helper: change request
       ------------------------- */
    private static class SlotChangeRequest {
        public final UUID player;
        public final int slotIndex;
        public final ItemStack item;
        public final long timestamp;

        public SlotChangeRequest(UUID player, int slotIndex, ItemStack item, long timestamp) {
            this.player = player;
            this.slotIndex = slotIndex;
            this.item = item;
            this.timestamp = timestamp;
        }
    }
}