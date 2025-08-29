package net.errantwanderer.omnipackmod.shared;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.errantwanderer.omnipackmod.OmniPackMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Arrays;
import java.util.List;

public class SharedInventoryData extends SavedData {
    public static final String DATA_NAME = "omnipack_shared_inventory";
    private static final int TOTAL_SIZE = 41; // 0..35 main, 36..39 armor, 40 offhand

    // NOTE: array kept for indexing; codec exposes it as a list
    private final ItemStack[] contents = new ItemStack[TOTAL_SIZE];
    private int version = 0;
    private int lastHash = 0;

    /** Disk codec (1.21+). If OPTIONAL_CODEC is missing in your mappings, use ItemStack.CODEC. */
    public static final Codec<SharedInventoryData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            // Persist the 41 slots as a list of ItemStacks
            // Use OPTIONAL_CODEC so empty stacks serialize cleanly in 1.21+
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items")
                    .forGetter(d -> Arrays.asList(d.contents)),
            Codec.INT.optionalFieldOf("ver", 0).forGetter(d -> d.version)
    ).apply(inst, (items, ver) -> {
        SharedInventoryData d = new SharedInventoryData();
        Arrays.fill(d.contents, ItemStack.EMPTY);
        int n = Math.min(items.size(), TOTAL_SIZE);
        for (int i = 0; i < n; i++) d.contents[i] = items.get(i);
        d.version = ver;
        d.recalcHash();
        return d;
    }));

    /** SavedDataType registration (no Factory/create() in 1.21.x). */
    public static final SavedDataType<SharedInventoryData> TYPE =
            new SavedDataType<>(
                    OmniPackMod.MOD_ID + "/" + DATA_NAME, // becomes <world>/<dim>/data/<this>.dat
                    SharedInventoryData::new,              // default constructor when no file exists
                    CODEC                                   // how to read/write the file
            );

    public SharedInventoryData() {
        Arrays.fill(contents, ItemStack.EMPTY);
        recalcHash();
    }

    /** Attach or load from this level’s data storage (use Overworld if you want it global). */
    public static SharedInventoryData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /* ---------------- sync logic ---------------- */

    /** Merge player's view into shared; returns true if shared changed. */
    public boolean mergeFromPlayer(ServerPlayer sp) {
        boolean changed = false;
        Inventory inv = sp.getInventory();

        // main inventory 0..35
        for (int i = 0; i < 36; i++) {
            changed |= setIfDifferent(i, inv.getItem(i));
        }

        // armor (HEAD,CHEST,LEGS,FEET) -> 36..39
        changed |= setIfDifferent(36, sp.getItemBySlot(EquipmentSlot.HEAD));
        changed |= setIfDifferent(37, sp.getItemBySlot(EquipmentSlot.CHEST));
        changed |= setIfDifferent(38, sp.getItemBySlot(EquipmentSlot.LEGS));
        changed |= setIfDifferent(39, sp.getItemBySlot(EquipmentSlot.FEET));

        // offhand -> 40
        changed |= setIfDifferent(40, sp.getOffhandItem());

        if (changed) {
            version++;
            setDirty(); // tell SD system to write to disk later
            recalcHash();
        }
        return changed;
    }

    /** Overwrite a player's inventory from the shared snapshot. */
    public void writeToPlayer(ServerPlayer sp) {
        Inventory inv = sp.getInventory();

        for (int i = 0; i < 36; i++) inv.setItem(i, contents[i].copy());
        sp.setItemSlot(EquipmentSlot.HEAD,  contents[36].copy());
        sp.setItemSlot(EquipmentSlot.CHEST, contents[37].copy());
        sp.setItemSlot(EquipmentSlot.LEGS,  contents[38].copy());
        sp.setItemSlot(EquipmentSlot.FEET,  contents[39].copy());
        sp.setItemSlot(EquipmentSlot.OFFHAND, contents[40].copy());

        inv.setChanged();
    }

    public int version() { return version; }
    public int hash() { return lastHash; }

    /* ---------------- helpers ---------------- */

    private boolean setIfDifferent(int idx, ItemStack src) {
        ItemStack existing = contents[idx];
        if (!ItemStack.isSameItemSameComponents(existing, src) || existing.getCount() != src.getCount()) {
            contents[idx] = src.copy();
            return true;
        }
        return false;
    }

    private void recalcHash() {
        int h = 1;
        for (int i = 0; i < TOTAL_SIZE; i++) {
            ItemStack s = contents[i];
            h = 31 * h + (s.isEmpty() ? 0 : (s.getItem().hashCode() * 37 + s.getCount()));
        }
        this.lastHash = h;
    }
}