package net.clanimg.halloween.score;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.clanimg.halloween.HalloweenSettings.ScoreboardSettings;
import net.clanimg.halloween.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/** Eigene Sidebar je Spieler (die eigene Zeile ist für jeden anders). Nur auf dem Server-Thread benutzen. */
public final class ScoreboardService {
    private static final String OBJECTIVE = "halloween";
    private static final int MAX_LINES = 15;

    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private ScoreboardSettings settings;
    private Leaderboard leaderboard = Leaderboard.EMPTY;

    public ScoreboardService(ScoreboardSettings settings) {
        this.settings = settings;
    }

    public Leaderboard leaderboard() {
        return leaderboard;
    }

    public void applySettings(ScoreboardSettings newSettings) {
        settings = newSettings;
        for (Player player : Bukkit.getOnlinePlayers()) {
            remove(player);
            show(player);
        }
    }

    /** Neue Rangliste für alle anzeigen. */
    public void update(Leaderboard newLeaderboard) {
        leaderboard = newLeaderboard;
        for (Player player : Bukkit.getOnlinePlayers()) {
            render(player);
        }
    }

    public void show(Player player) {
        if (!settings.enabled()) {
            return;
        }
        Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), id -> createBoard());
        player.setScoreboard(board);
        render(player);
    }

    public void remove(Player player) {
        Scoreboard board = boards.remove(player.getUniqueId());
        if (board != null && player.isOnline() && player.getScoreboard() == board) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    public void removeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            remove(player);
        }
        boards.clear();
    }

    private Scoreboard createBoard() {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Text.parse(settings.title()));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank()); // keine roten Zahlen rechts
        return board;
    }

    private void render(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) {
            return;
        }
        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            return;
        }
        List<String> lines = SidebarLines.build(leaderboard, player.getUniqueId(), player.getName(), settings);
        int count = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < count; i++) {
            // Feste, unsichtbare Einträge; angezeigt wird der eigene Text. Höherer Wert = weiter oben.
            var score = objective.getScore("line-" + i);
            score.setScore(count - i);
            String line = lines.get(i);
            score.customName(Text.parse(line.isEmpty() ? " " : line));
        }
        for (int i = count; i < MAX_LINES; i++) {
            board.resetScores("line-" + i);
        }
    }
}
