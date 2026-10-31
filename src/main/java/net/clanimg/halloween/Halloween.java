package net.clanimg.halloween;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.clanimg.halloween.deco.Deco;
import net.clanimg.halloween.deco.DecoItems;
import net.clanimg.halloween.deco.DecoListener;
import net.clanimg.halloween.deco.DecoRegistry;
import net.clanimg.halloween.menu.MenuListener;
import net.clanimg.halloween.score.Leaderboard;
import net.clanimg.halloween.score.ScoreboardService;
import net.clanimg.halloween.storage.HalloweenRepository;
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
    /** Grund, warum keine Datenbank-Verbindung besteht (null = verbunden). */
    private volatile String databaseError = "noch nicht verbunden";
    private volatile boolean connecting;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = HalloweenSettings.from(getConfig());
        decoItems = new DecoItems(this);
        scoreboards = new ScoreboardService(settings.scoreboard());
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
        if (scoreboards != null) {
            scoreboards.removeAll();
        }
        if (repository != null) {
            repository.close();
        }
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
            List<Leaderboard.Entry> scores = repository.loadScores();
            sync(() -> {
                registry.replaceAll(decos);
                scoreboards.update(Leaderboard.of(scores));
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

    public void playerLeft(Player player) {
        registry.forget(player.getUniqueId());
        scoreboards.remove(player);
    }

    // ------------------------------------------------------------------ Finden

    public void recordFind(Player player, Deco deco) {
        UUID playerId = player.getUniqueId();
        String name = player.getName();
        long foundAt = System.currentTimeMillis();
        async(() -> {
            boolean isNew = repository.recordFind(playerId, name, deco.id());
            sync(() -> {
                if (!isNew) {
                    decoListener.alreadyFound(player);
                    return;
                }
                send(player, "found", "%found%", Integer.toString(registry.foundCount(playerId)),
                    "%total%", Integer.toString(registry.total()));
                playFoundSound(player);
                scoreboards.update(scoreboards.leaderboard().withFind(playerId, name, foundAt));
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
        send(player, "deco-placed", "%total%", Integer.toString(registry.total()));
        UUID placedBy = player.getUniqueId();
        async(() -> repository.addDeco(deco, placedBy), ex -> {
            registry.remove(deco.id());
            getLogger().warning("Deko nicht gespeichert: " + ex.getMessage());
            send(player, "database-error");
        });
    }

    public void removeDeco(Player player, Deco deco) {
        registry.remove(deco.id());
        send(player, "deco-removed", "%total%", Integer.toString(registry.total()));
        async(() -> {
            repository.removeDeco(deco.id());
            refreshNow(); // Punkte ohne diese Deko
        }, ex -> {
            getLogger().warning("Deko nicht aus der Datenbank entfernt: " + ex.getMessage());
            send(player, "database-error");
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
