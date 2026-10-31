package net.clanimg.halloween.menu;

import java.util.Map;
import net.clanimg.halloween.Text;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** /halloween setup (Items hineinlegen) und /halloween inv (Items herausholen per Klick). */
public final class HalloweenMenu implements InventoryHolder {
    public enum Type { SETUP, ITEMS }

    public static final int SIZE = 54;

    private final Type type;
    private final Inventory inventory;

    public HalloweenMenu(Type type, Map<Integer, ItemStack> items) {
        this.type = type;
        String title = type == Type.SETUP ? "&8Halloween &7- Deko-Items festlegen" : "&8Halloween &7- Deko-Items";
        this.inventory = Bukkit.createInventory(this, SIZE, Text.parse(title));
        items.forEach((slot, item) -> {
            if (slot >= 0 && slot < SIZE) {
                inventory.setItem(slot, item.clone());
            }
        });
    }

    public Type type() {
        return type;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
