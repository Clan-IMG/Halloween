package net.clanimg.halloween.deco;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.clanimg.halloween.Halloween;
import net.clanimg.halloween.HalloweenSettings.ParticleSettings;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Kleine farbige Pixel an Deko, die ein Spieler noch nicht gefunden hat - nur für ihn sichtbar. Welche Blöcke in einer
 * Welt Deko sind, wird geprüft wie beim Anklicken (Deko-ID im Kopf bzw. Koordinaten + Blocktyp), also auch in kopierten
 * Welten. Nur auf dem Server-Thread.
 */
public final class DecoParticles {
    /** Deko-Stellen je Welt spätestens so oft neu prüfen (z.B. Chunks, die inzwischen geladen sind). */
    private static final long RESCAN_MS = 15_000L;

    private record Spot(UUID decoId, Location center) {}

    private final Halloween plugin;
    private final Map<String, List<Spot>> spotsByWorld = new HashMap<>();
    private long cachedVersion = -1;
    private long cachedAt;
    private BukkitTask task;
    private ParticleSettings settings;
    private Particle.DustOptions dust;

    public DecoParticles(Halloween plugin) {
        this.plugin = plugin;
    }

    public void start(ParticleSettings newSettings) {
        stop();
        settings = newSettings;
        if (!settings.enabled()) {
            startScanOnly();
            return;
        }
        dust = new Particle.DustOptions(Color.fromRGB(settings.rgb()), settings.size());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, settings.intervalTicks(), settings.intervalTicks());
    }

    /** Ohne Partikel trotzdem regelmäßig prüfen, welche Deko in welcher Welt steht (Wertung pro Welt). */
    private void startScanOnly() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L * 15L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        spotsByWorld.clear();
    }

    private void tick() {
        DecoRegistry registry = plugin.registry();
        long now = System.currentTimeMillis();
        if (registry.version() != cachedVersion || now - cachedAt > RESCAN_MS) {
            spotsByWorld.clear();
            cachedVersion = registry.version();
            cachedAt = now;
        }
        if (registry.total() == 0) {
            return;
        }
        double rangeSquared = settings.range() * settings.range();
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            List<Spot> spots = spotsByWorld.computeIfAbsent(world.getName(), name -> scan(world));
            if (!settings.enabled()) {
                continue;
            }
            Location position = player.getLocation();
            UUID playerId = player.getUniqueId();
            for (Spot spot : spots) {
                if (spot.center().distanceSquared(position) > rangeSquared || registry.hasFound(playerId, spot.decoId())) {
                    continue;
                }
                player.spawnParticle(Particle.DUST, spot.center(), settings.count(), 0.4, 0.4, 0.4, 0.0, dust);
            }
        }
    }

    private List<Spot> scan(World world) {
        DecoRegistry registry = plugin.registry();
        List<Spot> spots = new ArrayList<>();
        for (Deco deco : registry.all()) {
            if (!world.isChunkLoaded(deco.x() >> 4, deco.z() >> 4)) {
                continue; // nicht extra laden; beim nächsten Durchlauf wieder geprüft
            }
            Block block = world.getBlockAt(deco.x(), deco.y(), deco.z());
            Optional<Deco> here = plugin.decoItems().decoAt(block, registry);
            if (here.isPresent() && here.get().id().equals(deco.id())) {
                spots.add(new Spot(deco.id(), block.getLocation().add(0.5, 0.35, 0.5)));
                plugin.discoverDeco(deco, world.getName()); // auch in Kopien der Welt mitzählen
            }
        }
        return spots;
    }
}
