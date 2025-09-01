package net.errantwanderer.omnipackmod;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;

/**
 * Server-authoritative shared inventory.
 *
 * - Detects per-player diffs and enqueues SlotChangeRequest
 * - Applies queued requests sequentially (synchronized)
 * - Broadcasts authoritative inventory to all players using vanilla inventory packets
 *
 * NOTE: This class intentionally reuses vanilla inventory slots (main + armor + offhand).
 */
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
        // TODO: persistent load/store if you want inventory to survive restarts
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

    // Call from ServerTickEvent.Post
    public void onServerTick(MinecraftServer server) {
        // 1) detect diffs from players' inventories and enqueue requests
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

    /**
     * Called right after we've invoked the player's containerMenu.clicked(...) server-side.
     * This forces immediate diff detection for that player and processes the queue so the
     * result is broadcast to all without waiting for the next tick loop.
     */
    public void handleImmediatePlayerAction(ServerPlayer player, MinecraftServer server) {
        // detect diff for just this player and enqueue resulting requests
        detectAndEnqueuePlayerDiff(player);

        // apply and broadcast immediately (safely)
        processQueuedRequests();

        if (lastProcessedTs != 0L && server != null) {
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

            // ItemStack.matches checks item, count and nbt in modern mappings
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

        int size = Math.min(SHARED_SLOT_COUNT, inv.getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            ItemStack s = sharedInventory.get(slot);
            inv.setItem(slot, s.copy());
        }

        inv.setChanged();
    }

    private void sendFullInventoryToPlayer(ServerPlayer p) {
        Inventory inv = p.getInventory();
        int size = Math.min(SHARED_SLOT_COUNT, inv.getContainerSize());

        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inv.getItem(slot);
            net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket pkt = new net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket(slot, stack);
            p.connection.send(pkt);
        }
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