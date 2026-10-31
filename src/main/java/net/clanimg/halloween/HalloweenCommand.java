package net.clanimg.halloween;

import java.util.List;
import java.util.Locale;
import net.clanimg.halloween.menu.HalloweenMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** /halloween setup | inv | reload */
public final class HalloweenCommand implements TabExecutor {
    private final Halloween plugin;

    public HalloweenCommand(Halloween plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!plugin.isConnected() && !sub.equals("reload")) {
            plugin.send(sender, "database-offline", "%error%", plugin.databaseError());
            return true;
        }
        switch (sub) {
            case "setup" -> {
                if (sender instanceof Player player && allowed(player, "halloween.setup")) {
                    player.openInventory(new HalloweenMenu(HalloweenMenu.Type.SETUP, plugin.decoItems().catalog()).getInventory());
                }
            }
            case "inv" -> {
                if (sender instanceof Player player && allowed(player, "halloween.inv")) {
                    if (plugin.decoItems().catalog().isEmpty()) {
                        plugin.send(player, "inv-empty");
                    } else {
                        player.openInventory(new HalloweenMenu(HalloweenMenu.Type.ITEMS, plugin.decoItems().catalog()).getInventory());
                    }
                }
            }
            case "reload" -> {
                if (!(sender instanceof Player player) || allowed(player, "halloween.reload")) {
                    plugin.reloadSettings(sender);
                }
            }
            default -> plugin.send(sender, "usage");
        }
        return true;
    }

    private boolean allowed(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        plugin.send(player, "no-permission");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("setup", "inv", "reload").stream()
            .filter(sub -> sub.startsWith(prefix) && sender.hasPermission("halloween." + sub))
            .toList();
    }
}
