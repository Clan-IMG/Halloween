package net.clanimg.halloween.deco;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.clanimg.halloween.Halloween;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Deko platzieren, finden (Links-/Rechtsklick) und - schleichend mit Recht - abbauen. */
public final class DecoListener implements Listener {
    private static final String MANAGE_PERMISSION = "halloween.inv";
    /** Linksklick feuert beim Gedrückthalten dauernd - so oft zählt höchstens ein Klick. */
    private static final long CLICK_COOLDOWN_MS = 400L;
    private static final long ALREADY_FOUND_MESSAGE_COOLDOWN_MS = 2_000L;

    private final Halloween plugin;
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlreadyFoundMessage = new ConcurrentHashMap<>();

    public DecoListener(Halloween plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!plugin.decoItems().isDecoItem(event.getItemInHand()) || !player.hasPermission(MANAGE_PERMISSION)) {
            return;
        }
        Block block = event.getBlockPlaced();
        UUID id = UUID.randomUUID();
        boolean tile = plugin.decoItems().writeBlockId(block, id);
        Deco deco = new Deco(id, block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), block.getType().name(), tile);
        plugin.addDeco(player, deco);
    }

    /** Linksklick bricht Deko im Kreativmodus sonst ab - abbauen nur mit Recht und schleichend. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Optional<Deco> deco = plugin.decoItems().decoAt(event.getBlock(), plugin.registry());
        if (deco.isEmpty()) {
            return;
        }
        if (!player.hasPermission(MANAGE_PERMISSION) || !player.isSneaking()) {
            event.setCancelled(true);
            if (player.hasPermission(MANAGE_PERMISSION)) {
                plugin.send(player, "deco-protected");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakDone(BlockBreakEvent event) {
        plugin.decoItems().decoAt(event.getBlock(), plugin.registry())
            .ifPresent(deco -> plugin.removeDeco(event.getPlayer(), deco));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        Block block = event.getClickedBlock();
        if (block == null || (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Optional<Deco> deco = plugin.decoItems().decoAt(block, plugin.registry());
        if (deco.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission(MANAGE_PERMISSION) && player.isSneaking()) {
            return; // Verwalten: schleichend abbauen statt finden
        }
        event.setCancelled(true);

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastClick.put(playerId, now);
        if (last != null && now - last < CLICK_COOLDOWN_MS) {
            return;
        }
        if (!plugin.registry().markFound(playerId, deco.get().id())) {
            alreadyFound(player);
            return;
        }
        plugin.recordFind(player, deco.get());
    }

    public void alreadyFound(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastAlreadyFoundMessage.put(player.getUniqueId(), now);
        if (last == null || now - last >= ALREADY_FOUND_MESSAGE_COOLDOWN_MS) {
            plugin.send(player, "already-found");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> plugin.decoItems().decoAt(block, plugin.registry()).isPresent());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> plugin.decoItems().decoAt(block, plugin.registry()).isPresent());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.playerJoined(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastClick.remove(playerId);
        lastAlreadyFoundMessage.remove(playerId);
        plugin.playerLeft(event.getPlayer());
    }
}
