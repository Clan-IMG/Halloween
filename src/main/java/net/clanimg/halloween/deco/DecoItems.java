package net.clanimg.halloween.deco;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Markierungen: Items aus /halloween inv tragen "deco" (beim Platzieren wird daraus eine Deko), platzierte Blöcke mit
 * eigenem Datenspeicher (Köpfe) tragen ihre Deko-ID - die bleibt beim Kopieren der Welt im Block.
 */
public final class DecoItems {
    private final NamespacedKey itemKey;
    private final NamespacedKey blockKey;
    private volatile Map<Integer, ItemStack> catalog = Map.of();

    public DecoItems(Plugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "deco");
        this.blockKey = new NamespacedKey(plugin, "deco-id");
    }

    /** Items aus /halloween setup (Slot -> Item). */
    public Map<Integer, ItemStack> catalog() {
        return catalog;
    }

    public void setCatalog(Map<Integer, ItemStack> items) {
        catalog = Map.copyOf(items);
    }

    public ItemStack tagged(ItemStack item) {
        ItemStack copy = item.clone();
        copy.editMeta(meta -> meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1));
        return copy;
    }

    public ItemStack untagged(ItemStack item) {
        ItemStack copy = item.clone();
        if (copy.hasItemMeta()) {
            copy.editMeta(meta -> meta.getPersistentDataContainer().remove(itemKey));
        }
        return copy;
    }

    public boolean isDecoItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    /** Schreibt die Deko-ID in den Block, wenn er einen eigenen Datenspeicher hat. @return true bei Erfolg */
    public boolean writeBlockId(Block block, UUID decoId) {
        BlockState state = block.getState();
        if (!(state instanceof TileState tile)) {
            return false;
        }
        tile.getPersistentDataContainer().set(blockKey, PersistentDataType.STRING, decoId.toString());
        tile.update();
        return true;
    }

    /**
     * Deko an diesem Block: bei Blöcken mit Datenspeicher über die gespeicherte ID, sonst über Koordinaten und Blocktyp.
     */
    public Optional<Deco> decoAt(Block block, DecoRegistry registry) {
        BlockState state = block.getState();
        if (state instanceof TileState tile) {
            String raw = tile.getPersistentDataContainer().get(blockKey, PersistentDataType.STRING);
            if (raw == null) {
                return Optional.empty();
            }
            try {
                return registry.byId(UUID.fromString(raw));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }
        return registry.blockDecoAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), block.getType().name());
    }
}
