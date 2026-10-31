package net.clanimg.halloween.deco;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.clanimg.halloween.Halloween;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Findet Deko in kopierten Welten, auch wo noch kein Spieler war: Für jede Deko, die in einer Welt noch nicht zählt,
 * wird ihr Chunk im Hintergrund geladen (nie neu erzeugt) und geprüft, ob dort genau diese Deko steht. Ändert nichts an
 * vorhandenen Daten - es kommt höchstens "Deko steht auch in dieser Welt" dazu. Nur auf dem Server-Thread.
 */
public final class DecoDiscovery {
    /** Dieselbe Deko in derselben Welt frühestens nach dieser Zeit erneut prüfen. */
    private static final long RECHECK_MS = 10 * 60_000L;

    private final Halloween plugin;
    private final Map<String, Long> lastChecked = new HashMap<>();
    private BukkitTask task;

    public DecoDiscovery(Halloween plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        // Kurz nach dem Start (Deko aus der Datenbank geladen), dann jede Minute.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::run, 20L * 15L, 20L * 60L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void run() {
        DecoRegistry registry = plugin.registry();
        if (registry.total() == 0) {
            return;
        }
        // Welten mit Spielern und Welten, in denen schon Deko zählen.
        Set<World> worlds = new LinkedHashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            worlds.add(player.getWorld());
        }
        for (World world : Bukkit.getWorlds()) {
            if (plugin.scoreData().total(world.getName()) > 0) {
                worlds.add(world);
            }
        }
        long now = System.currentTimeMillis();
        for (World world : worlds) {
            Set<UUID> known = plugin.scoreData().decosIn(world.getName());
            for (Deco deco : registry.all()) {
                if (known.contains(deco.id())) {
                    continue;
                }
                String key = world.getName().toLowerCase(Locale.ROOT) + "|" + deco.id();
                Long last = lastChecked.get(key);
                if (last != null && now - last < RECHECK_MS) {
                    continue;
                }
                lastChecked.put(key, now);
                world.getChunkAtAsync(deco.x() >> 4, deco.z() >> 4, false).thenAccept(chunk -> {
                    if (chunk == null) {
                        return; // Chunk gibt es in dieser Welt nicht
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> check(world, deco));
                });
            }
        }
    }

    private void check(World world, Deco deco) {
        Block block = world.getBlockAt(deco.x(), deco.y(), deco.z());
        plugin.decoItems().decoAt(block, plugin.registry())
            .filter(found -> found.id().equals(deco.id()))
            .ifPresent(found -> plugin.discoverDeco(found, world.getName()));
    }
}
