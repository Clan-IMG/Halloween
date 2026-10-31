package net.clanimg.halloween;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Schickt jeden Spieler beim Join zum Spawn, indem für ihn der konfigurierte Befehl (z.B. /spawn von HuskHomes)
 * ausgeführt wird - so gilt immer der Spawn, den man mit /setspawn gesetzt hat.
 */
public final class JoinSpawnListener implements Listener {
    private final Halloween plugin;

    public JoinSpawnListener(Halloween plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        HalloweenSettings.JoinSpawnSettings joinSpawn = plugin.settings().joinSpawn();
        String command = joinSpawn.command() == null ? "" : joinSpawn.command().trim();
        if (!joinSpawn.enabled() || command.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        String withoutSlash = command.startsWith("/") ? command.substring(1) : command;
        // Kurz warten, bis der Join (Welt, Position) vollständig ist.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.performCommand(withoutSlash);
            }
        }, joinSpawn.delayTicks());
    }
}
