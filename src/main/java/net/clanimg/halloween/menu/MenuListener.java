package net.clanimg.halloween.menu;

import java.util.Map;
import java.util.TreeMap;
import net.clanimg.halloween.Halloween;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class MenuListener implements Listener {
    private final Halloween plugin;

    public MenuListener(Halloween plugin) {
        this.plugin = plugin;
    }

    /** /halloween inv: nichts herausnehmen, ein Klick gibt eine Kopie (als Deko markiert). */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof HalloweenMenu menu) || menu.type() != HalloweenMenu.Type.ITEMS) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        if (!player.getInventory().addItem(plugin.decoItems().tagged(clicked)).isEmpty()) {
            plugin.send(player, "inv-full");
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof HalloweenMenu menu && menu.type() == HalloweenMenu.Type.ITEMS) {
            event.setCancelled(true);
        }
    }

    /** /halloween setup: beim Schließen speichern. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof HalloweenMenu menu) || menu.type() != HalloweenMenu.Type.SETUP
            || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        Map<Integer, ItemStack> items = new TreeMap<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                items.put(slot, plugin.decoItems().untagged(item));
            }
        }
        plugin.saveItems(player, items);
    }
}
