package net.clanimg.halloween;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.clanimg.halloween.deco.Deco;
import net.clanimg.halloween.deco.DecoDiscovery;
import net.clanimg.halloween.deco.DecoParticles;
import net.clanimg.halloween.deco.DecoItems;
import net.clanimg.halloween.deco.DecoListener;
import net.clanimg.halloween.deco.DecoRegistry;
import net.clanimg.halloween.menu.MenuListener;
import net.clanimg.halloween.score.ScoreData;
import net.clanimg.halloween.score.ScoreboardService;
import net.clanimg.halloween.storage.HalloweenRepository;
import net.clanimg.halloween.storage.HalloweenRepository.Adjustment;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class Halloween extends JavaPlugin {
    private HalloweenSettings settings;
    private HalloweenRepository repository;
    private final DecoRegistry registry = new DecoRegistry();
    private DecoItems decoItems;
    private ScoreboardService scoreboards;
    private DecoListener decoListener;
    private BukkitTask refreshTask;
    private DecoParticles particles;
    private DecoDiscovery discovery;
    /** Grund, warum keine Datenbank-Verbindung besteht (null = verbunden). */
    private volatile String databaseError = "noch nicht verbunden";
    private volatile boolean connecting;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = HalloweenSettings.from(getConfig());
        decoItems = new DecoItems(this);
        scoreboards = new ScoreboardService(settings.scoreboard());
        // Unabhängig von der Datenbank.
        getServer().getPluginManager().registerEvents(new JoinSpawnListener(this), this);
        PluginCommand command = getCommand("halloween");
        if (command != null) {
            HalloweenCommand executor = new HalloweenCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        // Ohne Datenbank bleibt das Plugin an: /halloween zeigt dann den Grund, /halloween reload verbindet neu.
        try {
            start(HalloweenRepository.connect(settings.database(), settings.eventId(), getLogger()));
        } catch (SQLException | RuntimeException ex) {
            connectionFailed(ex);
        }
    }

    /** Nach erfolgreicher Verbindung: Listener, Items, Spieler, Abgleich. */
    private void start(HalloweenRepository connected) {
        repository = connected;
        databaseError = null;
        if (decoListener == null) {
            decoListener = new DecoListener(this);
            getServer().getPluginManager().registerEvents(decoListener, this);
            getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        }
        loadItems();
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerJoined(player); // nach /reload bzw. späterer Verbindung
        }
        scheduleRefresh();
        if (particles == null) {
            particles = new DecoParticles(this);
        }
        particles.start(settings.particles());
        if (discovery == null) {
            discovery = new DecoDiscovery(this);
        }
        discovery.start();
        getLogger().info("Halloween-Event '" + settings.eventId() + "' bereit.");
    }

    private void connectionFailed(Exception ex) {
        databaseError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        getLogger().severe("MariaDB nicht erreichbar (" + settings.database().jdbcUrl() + "): " + databaseError
            + " - Halloween ist inaktiv. Zugangsdaten in plugins/Halloween/config.yml prüfen, dann /halloween reload.");
    }

    public boolean isConnected() {
        return repository != null;
    }

    public String databaseError() {
        return databaseError == null ? "" : databaseError;
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (particles != null) {
            particles.stop();
        }
        if (discovery != null) {
            discovery.stop();
        }
        if (scoreboards != null) {
            scoreboards.removeAll();
        }
        if (repository != null) {
            repository.close();
        }
    }

    public HalloweenSettings settings() {
        return settings;
    }

    public ScoreData scoreData() {
        return scoreboards.data();
    }

    public DecoRegistry registry() {
        return registry;
    }

    public DecoItems decoItems() {
        return decoItems;
    }

    public void send(CommandSender sender, String key, String... replacements) {
        sender.sendMessage(Text.parse(settings.message(key, replacements)));
    }

    /** config.yml neu laden; ohne Verbindung wird (abseits des Server-Threads) neu verbunden. */
    public void reloadSettings(CommandSender sender) {
        reloadConfig();
        settings = HalloweenSettings.from(getConfig());
        scoreboards.applySettings(settings.scoreboard());
        if (isConnected()) {
            scheduleRefresh();
            particles.start(settings.particles());
            send(sender, "reloaded");
            return;
        }
        if (connecting) {
            send(sender, "connecting");
            return;
        }
        connecting = true;
        send(sender, "connecting");
        HalloweenSettings current = settings;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                HalloweenRepository connected = HalloweenRepository.connect(current.database(), current.eventId(), getLogger());
                sync(() -> {
                    connecting = false;
                    start(connected);
                    send(sender, "connected");
                });
            } catch (SQLException | RuntimeException ex) {
                sync(() -> {
                    connecting = false;
                    connectionFailed(ex);
                    send(sender, "database-offline", "%error%", databaseError());
                });
            }
        });
    }

    // ------------------------------------------------------------------ Abgleich mit der Datenbank

    private void scheduleRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        long period = 20L * settings.scoreboard().refreshSeconds();
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::refreshNow, 1L, period);
    }

    /** Deko (auch auf anderen Servern gesetzt) und Punkte neu laden. Läuft asynchron. */
    private void refreshNow() {
        try {
            List<Deco> decos = repository.loadDecos();
            ScoreData data = repository.loadScoreData(decos);
            // Funde der Online-Spieler auch neu laden (z.B. per /halloween set auf einem anderen Server zurückgesetzt).
            Map<UUID, Set<UUID>> found = repository.loadFound(registry.trackedPlayers());
            sync(() -> {
                registry.replaceAll(decos);
                found.forEach((playerId, decoIds) -> {
                    if (registry.trackedPlayers().contains(playerId)) {
                        registry.setFound(playerId, decoIds);
                    }
                });
                scoreboards.update(data);
            });
        } catch (SQLException ex) {
            getLogger().warning("Abgleich mit der Datenbank fehlgeschlagen: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ Spieler

    public void playerJoined(Player player) {
        scoreboards.show(player);
        UUID playerId = player.getUniqueId();
        String name = player.getName();
        async(() -> {
            Set<UUID> found = repository.loadFound(playerId);
            repository.updatePlayerName(playerId, name);
            sync(() -> registry.setFound(playerId, found));
        }, ex -> getLogger().warning("Funde von " + name + " nicht geladen: " + ex.getMessage()));
    }

    /** Nach einem Weltwechsel: Wertung der neuen Welt zeigen. */
    public void refreshScoreboard(Player player) {
        scoreboards.render(player);
    }

    public void playerLeft(Player player) {
        registry.forget(player.getUniqueId());
        scoreboards.remove(player);
    }

    // ------------------------------------------------------------------ Finden

    public void recordFind(Player player, Deco deco) {
        UUID playerId = player.getUniqueId();
        String name = player.getName();
        String world = player.getWorld().getName();
        long foundAt = System.currentTimeMillis();
        int total = scoreboards.data().total(world);
        if (total > 0 && scoreboards.data().score(playerId, world) >= total) {
            // Schon alle (z.B. per /halloween set) - weitere Klicks zählen nicht.
            registry.unmarkFound(playerId, deco.id());
            send(player, "all-found", "%total%", Integer.toString(total));
            return;
        }
        async(() -> {
            boolean isNew = repository.recordFind(playerId, name, deco.id());
            sync(() -> {
                if (!isNew) {
                    decoListener.alreadyFound(player);
                    return;
                }
                ScoreData data = scoreboards.data().withDecoIn(deco.id(), world).withFind(playerId, name, deco.id(), foundAt);
                scoreboards.update(data);
                send(player, "found", "%found%", Integer.toString(data.score(playerId, world)),
                    "%total%", Integer.toString(data.total(world)));
                playFoundSound(player);
            });
        }, ex -> {
            registry.unmarkFound(playerId, deco.id());
            getLogger().warning("Fund von " + name + " nicht gespeichert: " + ex.getMessage());
            send(player, "database-error");
        });
    }

    private void playFoundSound(Player player) {
        String sound = settings.foundSound();
        if (sound == null || sound.isBlank()) {
            return;
        }
        try {
            player.playSound(Sound.sound(Key.key(sound.trim()), Sound.Source.MASTER, 1.0f, 1.2f));
        } catch (InvalidKeyException ex) {
            getLogger().warning("found-sound ist kein gültiger Sound-Key: " + sound);
        }
    }

    // ------------------------------------------------------------------ Deko setzen / entfernen

    public void addDeco(Player player, Deco deco) {
        registry.add(deco); // sofort anklickbar
        ScoreData data = scoreboards.data().withDecoIn(deco.id(), deco.world());
        scoreboards.update(data); // neue Gesamtzahl dieser Welt
        send(player, "deco-placed", "%total%", Integer.toString(data.total(deco.world())));
        UUID placedBy = player.getUniqueId();
        async(() -> repository.addDeco(deco, placedBy), ex -> {
            registry.remove(deco.id());
            getLogger().warning("Deko nicht gespeichert: " + ex.getMessage());
            send(player, "database-error");
        });
    }

    public void removeDeco(Player player, Deco deco) {
        registry.remove(deco.id());
        ScoreData data = scoreboards.data().withoutDeco(deco.id());
        scoreboards.update(data);
        send(player, "deco-removed", "%total%", Integer.toString(data.total(player.getWorld().getName())));
        async(() -> {
            repository.removeDeco(deco.id());
            refreshNow(); // Punkte ohne diese Deko
        }, ex -> {
            getLogger().warning("Deko nicht aus der Datenbank entfernt: " + ex.getMessage());
            send(player, "database-error");
        });
    }

    /**
     * Deko steht (auch) in dieser Welt - beim Anklicken oder Sehen in einer kopierten Welt entdeckt. Zählt ab dann in
     * der Wertung dieser Welt mit.
     */
    public void discoverDeco(Deco deco, String world) {
        if (!isConnected() || scoreboards.data().decosIn(world).contains(deco.id())) {
            return;
        }
        scoreboards.update(scoreboards.data().withDecoIn(deco.id(), world));
        async(() -> repository.addDecoWorld(deco.id(), world),
            ex -> getLogger().warning("Deko-Welt nicht gespeichert: " + ex.getMessage()));
    }

    // ------------------------------------------------------------------ /halloween list | tp

    /** Alle Deko der Welt, in der der Spieler steht, im Chat - ein Klick teleportiert hin. */
    public void listDecos(Player player) {
        String world = player.getWorld().getName();
        List<Deco> decos = new java.util.ArrayList<>();
        for (UUID id : scoreboards.data().decosIn(world)) {
            registry.byId(id).ifPresent(decos::add);
        }
        if (decos.isEmpty()) {
            send(player, "list-empty", "%world%", world);
            return;
        }
        decos.sort(java.util.Comparator.comparingInt(Deco::x).thenComparingInt(Deco::z).thenComparingInt(Deco::y));
        send(player, "list-header", "%world%", world, "%total%", Integer.toString(decos.size()));
        UUID playerId = player.getUniqueId();
        for (int i = 0; i < decos.size(); i++) {
            Deco deco = decos.get(i);
            String line = settings.messages().getOrDefault("list-entry", "&8%nr%. &7%type% &8(&f%x% %y% %z%&8) %found%")
                .replace("%nr%", Integer.toString(i + 1))
                .replace("%type%", deco.material().toLowerCase(java.util.Locale.ROOT).replace('_', ' '))
                .replace("%x%", Integer.toString(deco.x()))
                .replace("%y%", Integer.toString(deco.y()))
                .replace("%z%", Integer.toString(deco.z()))
                .replace("%found%", registry.hasFound(playerId, deco.id())
                    ? settings.messages().getOrDefault("list-found", "&a✔") : settings.messages().getOrDefault("list-open", "&7✘"));
            player.sendMessage(Text.parse(line)
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                    Text.parse(settings.messages().getOrDefault("list-hover", "&7Klick zum Teleportieren"))))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/halloween tp " + deco.id())));
        }
    }

    /** Teleport zu einer Deko - in der Welt, in der man steht (auch in Kopien an denselben Koordinaten). */
    public void teleportToDeco(Player player, String rawId) {
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException ex) {
            send(player, "list-unknown");
            return;
        }
        Optional<Deco> deco = registry.byId(id);
        if (deco.isEmpty()) {
            send(player, "list-unknown");
            return;
        }
        org.bukkit.Location target = new org.bukkit.Location(player.getWorld(), deco.get().x() + 0.5, deco.get().y() + 1.0,
            deco.get().z() + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleportAsync(target);
    }

    // ------------------------------------------------------------------ Punkte von Hand (/halloween set|add|remove)

    /**
     * @param target Spielername oder * für alle Spieler, die schon in der Wertung waren, und alle, die online sind
     * @param world  Welt, deren Wertung geändert wird
     */
    public void adjustPoints(CommandSender sender, Adjustment adjustment, String target, int amount, String world) {
        Set<UUID> decosInWorld = scoreboards.data().decosIn(world);
        Map<String, UUID> online = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.put(player.getName(), player.getUniqueId());
        }
        async(() -> {
            Map<String, UUID> known = repository.knownPlayers();
            Map<String, UUID> targets = new LinkedHashMap<>();
            if (target.equals("*")) {
                targets.putAll(known);
                online.forEach(targets::putIfAbsent);
            } else {
                Map<String, UUID> all = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                all.putAll(known);
                all.putAll(online); // aktueller Name hat Vorrang
                for (Map.Entry<String, UUID> player : all.entrySet()) {
                    if (player.getKey().equalsIgnoreCase(target)) {
                        targets.put(player.getKey(), player.getValue());
                    }
                }
            }
            if (targets.isEmpty()) {
                sync(() -> send(sender, "player-unknown", "%player%", target));
                return;
            }
            Map<String, Integer> scores = new LinkedHashMap<>();
            for (Map.Entry<String, UUID> player : targets.entrySet()) {
                scores.put(player.getKey(), repository.adjustWorldScore(world, decosInWorld, player.getValue(), player.getKey(), adjustment, amount));
            }
            List<Deco> decos = repository.loadDecos();
            ScoreData data = repository.loadScoreData(decos);
            Map<UUID, Set<UUID>> found = repository.loadFound(registry.trackedPlayers());
            sync(() -> {
                found.forEach(registry::setFound);
                scoreboards.update(data);
                if (scores.size() == 1) {
                    Map.Entry<String, Integer> only = scores.entrySet().iterator().next();
                    send(sender, "points-set", "%player%", only.getKey(), "%score%", Integer.toString(only.getValue()), "%world%", world);
                } else {
                    send(sender, "points-all", "%count%", Integer.toString(scores.size()), "%world%", world);
                }
            });
        }, ex -> {
            getLogger().warning("Punkte nicht geändert: " + ex.getMessage());
            send(sender, "database-error");
        });
    }

    // ------------------------------------------------------------------ Deko-Items (/halloween setup)

    private void loadItems() {
        async(() -> {
            Map<Integer, byte[]> raw = repository.loadItems();
            sync(() -> {
                Map<Integer, ItemStack> items = new HashMap<>();
                raw.forEach((slot, bytes) -> {
                    try {
                        items.put(slot, ItemStack.deserializeBytes(bytes));
                    } catch (RuntimeException ex) {
                        getLogger().warning("Deko-Item in Slot " + slot + " nicht lesbar: " + ex.getMessage());
                    }
                });
                decoItems.setCatalog(items);
            });
        }, ex -> getLogger().warning("Deko-Items nicht geladen: " + ex.getMessage()));
    }

    public void saveItems(Player player, Map<Integer, ItemStack> items) {
        Map<Integer, byte[]> raw = new HashMap<>();
        items.forEach((slot, item) -> raw.put(slot, item.serializeAsBytes()));
        decoItems.setCatalog(items);
        async(() -> {
            repository.saveItems(raw);
            sync(() -> send(player, "setup-saved", "%count%", Integer.toString(items.size())));
        }, ex -> {
            getLogger().warning("Deko-Items nicht gespeichert: " + ex.getMessage());
            send(player, "database-error");
        });
    }

    // ------------------------------------------------------------------ Threads

    private interface DatabaseTask {
        void run() throws SQLException;
    }

    /** Datenbank-Arbeit abseits des Server-Threads; Fehler landen (auf dem Server-Thread) bei {@code onError}. */
    private void async(DatabaseTask task, Consumer<SQLException> onError) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                task.run();
            } catch (SQLException ex) {
                sync(() -> onError.accept(ex));
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Fehler in einer Datenbank-Aufgabe", ex);
            }
        });
    }

    private void sync(Runnable action) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, action);
        }
    }
}
