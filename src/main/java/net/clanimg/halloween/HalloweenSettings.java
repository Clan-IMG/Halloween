package net.clanimg.halloween;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Inhalt der config.yml. */
public record HalloweenSettings(
    String eventId,
    DatabaseSettings database,
    ScoreboardSettings scoreboard,
    String foundSound,
    JoinSpawnSettings joinSpawn,
    ParticleSettings particles,
    Map<String, String> messages
) {
    /** Kleine grüne Pixel an Deko, die der Spieler noch nicht gefunden hat (nur er sieht sie). */
    public record ParticleSettings(boolean enabled, int rgb, float size, int count, int intervalTicks, double range) {}

    /** Beim Join einen Befehl für den Spieler ausführen, z.B. /spawn (HuskHomes nach /setspawn). */
    public record JoinSpawnSettings(boolean enabled, String command, int delayTicks) {}

    public record DatabaseSettings(String host, int port, String name, String user, String password, int poolSize) {
        public String jdbcUrl() {
            return "jdbc:mariadb://" + host + ":" + port + "/" + name;
        }
    }

    public record ScoreboardSettings(
        boolean enabled,
        String title,
        String subtitle,
        int top,
        String entry,
        String ownEntry,
        String ownEntryUnranked,
        boolean showOwnWithoutScore,
        String empty,
        int refreshSeconds
    ) {}

    /** {{ secrets.NAME }} / {{ env.NAME }} - setzt SimpleCloud beim Kopieren aus dem Template ein. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\{\\{\\s*(?:secrets|env)\\.([A-Za-z0-9_]+)\\s*}}$");

    public static HalloweenSettings from(FileConfiguration config) {
        return from(config, System::getenv);
    }

    static HalloweenSettings from(FileConfiguration config, UnaryOperator<String> environment) {
        DatabaseSettings database = new DatabaseSettings(
            resolve(config.getString("database.host", "localhost"), environment),
            config.getInt("database.port", 3306),
            resolve(config.getString("database.name", "halloween"), environment),
            resolve(config.getString("database.user", "halloween"), environment),
            resolve(config.getString("database.password", ""), environment),
            Math.max(1, config.getInt("database.pool-size", 4)));
        ScoreboardSettings scoreboard = new ScoreboardSettings(
            config.getBoolean("scoreboard.enabled", true),
            config.getString("scoreboard.title", "Halloween"),
            config.getString("scoreboard.subtitle", ""),
            Math.max(1, Math.min(10, config.getInt("scoreboard.top", 5))),
            config.getString("scoreboard.entry", "&7%rank%. %name% &8(&b%score%&8)"),
            config.getString("scoreboard.own-entry", "&7%rank%. %name% &8(&b%score%&8/&b%total%&8)"),
            config.getString("scoreboard.own-entry-unranked", "&7%name% &8(&b%score%&8/&b%total%&8)"),
            config.getBoolean("scoreboard.show-own-without-score", true),
            config.getString("scoreboard.empty", ""),
            Math.max(2, config.getInt("scoreboard.refresh-seconds", 10)));
        // Auch Nachrichten, die eine ältere config.yml auf dem Server noch nicht hat (Standard aus dem Jar).
        Set<String> keys = new LinkedHashSet<>();
        ConfigurationSection section = config.getConfigurationSection("messages");
        if (section != null) {
            keys.addAll(section.getKeys(false));
        }
        Configuration defaults = config.getDefaults();
        if (defaults != null && defaults.getConfigurationSection("messages") != null) {
            keys.addAll(defaults.getConfigurationSection("messages").getKeys(false));
        }
        Map<String, String> messages = new HashMap<>();
        for (String key : keys) {
            // Ohne Standardwert: getString(path, def) würde die Werte aus der config.yml im Jar ignorieren.
            String text = config.getString("messages." + key);
            messages.put(key, text == null ? "" : text);
        }
        return new HalloweenSettings(
            config.getString("event-id", "halloween"),
            database,
            scoreboard,
            config.getString("found-sound", ""),
            new JoinSpawnSettings(
                config.getBoolean("join-spawn.enabled", false),
                config.getString("join-spawn.command", "spawn"),
                Math.max(1, config.getInt("join-spawn.delay-ticks", 5))),
            new ParticleSettings(
                config.getBoolean("particles.enabled", true),
                parseColor(config.getString("particles.color", "#55FF55")),
                (float) Math.max(0.1, config.getDouble("particles.size", 0.6)),
                Math.max(1, config.getInt("particles.count", 2)),
                Math.max(2, config.getInt("particles.interval-ticks", 10)),
                Math.max(1.0, config.getDouble("particles.range", 24.0))),
            Map.copyOf(messages));
    }

    /**
     * Ein nicht ersetzter SimpleCloud-Platzhalter wird aus der gleichnamigen Umgebungsvariable gelesen, falls es sie
     * gibt; sonst bleibt er stehen (und die Verbindung scheitert mit einem klaren Hinweis).
     */
    static String resolve(String raw, UnaryOperator<String> environment) {
        if (raw == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(raw.trim());
        if (!matcher.matches()) {
            return raw;
        }
        String value = environment.apply(matcher.group(1));
        return value == null || value.isBlank() ? raw : value;
    }

    static int parseColor(String raw) {
        try {
            String hex = raw == null ? "" : raw.trim().replace("#", "");
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException ex) {
            return 0x55FF55;
        }
    }

    /** Nachricht mit Präfix; {@code replacements} paarweise Platzhalter und Wert. */
    public String message(String key, String... replacements) {
        String text = messages.getOrDefault(key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return messages.getOrDefault("prefix", "") + text;
    }
}
