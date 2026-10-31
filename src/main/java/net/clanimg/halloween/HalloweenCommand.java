package net.clanimg.halloween;

import java.util.List;
import java.util.Locale;
import net.clanimg.halloween.menu.HalloweenMenu;
import net.clanimg.halloween.storage.HalloweenRepository.Adjustment;
import org.bukkit.Bukkit;
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
            case "set", "add", "remove" -> {
                if (sender instanceof Player player && !allowed(player, "halloween.points")) {
                    return true;
                }
                Integer amount = args.length >= 3 ? parseAmount(args[2]) : null;
                if (amount == null) {
                    plugin.send(sender, "points-usage");
                    return true;
                }
                plugin.adjustPoints(sender, Adjustment.valueOf(sub.toUpperCase(Locale.ROOT)), args[1], amount);
            }
            default -> plugin.send(sender, "usage");
        }
        return true;
    }

    private static Integer parseAmount(String raw) {
        try {
            int value = Integer.parseInt(raw);
            return value < 0 ? null : value;
        } catch (NumberFormatException ex) {
            return null;
        }
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
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("setup", "inv", "reload", "set", "add", "remove").stream()
                .filter(sub -> sub.startsWith(prefix) && sender.hasPermission(permissionFor(sub)))
                .toList();
        }
        boolean points = List.of("set", "add", "remove").contains(args[0].toLowerCase(Locale.ROOT))
            && sender.hasPermission("halloween.points");
        if (points && args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new java.util.ArrayList<>();
            names.add("*");
            Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (points && args.length == 3) {
            return List.of("1", "5", "10");
        }
        return List.of();
    }

    private static String permissionFor(String sub) {
        return switch (sub) {
            case "set", "add", "remove" -> "halloween.points";
            default -> "halloween." + sub;
        };
    }
}
