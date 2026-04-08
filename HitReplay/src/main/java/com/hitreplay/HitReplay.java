package com.hitreplay;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class HitReplay extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final int RECORD_INTERVAL_TICKS = 2;
    private static final int BUFFER_FRAMES = 150;
    private static final long MAX_HIT_AGE_MS = 15L * 60 * 60 * 1000;
    private static final long PRUNE_INTERVAL_TICKS = 5L * 60 * 20;
    private static final int FRAME_SIZE = 6;
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");
    private static final String GUI_TITLE = "§6Hit Replays";

    private final Map<UUID, float[]> bufferData = new HashMap<>();
    private final Map<UUID, Integer> bufferHead = new HashMap<>();
    private final Map<UUID, Integer> bufferCount = new HashMap<>();
    private final Map<UUID, String> bufferWorld = new HashMap<>();
    private final List<HitRecord> allHits = new ArrayList<>();
    private final Map<UUID, List<HitRecord>> openGuis = new HashMap<>();
    private final Map<UUID, BukkitTask> viewerTasks = new HashMap<>();
    private final Map<UUID, GameMode> viewerPrevMode = new HashMap<>();
    private final Map<UUID, Location> viewerPrevLoc = new HashMap<>();

    private BukkitTask recordTask;
    private BukkitTask pruneTask;

    record HitRecord(
        long timestamp,
        String victimName, UUID victimId,
        String attackerName, UUID attackerId,
        float[] victimFrames,
        float[] attackerFrames,
        String worldName,
        int frameCount
    ) {}

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("replay").setExecutor(this);
        getCommand("replay").setTabCompleter(this);

        recordTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    recordFrame(p);
                }
            }
        }.runTaskTimer(this, 0L, RECORD_INTERVAL_TICKS);

        pruneTask = new BukkitRunnable() {
            @Override
            public void run() {
                pruneOldHits();
            }
        }.runTaskTimer(this, PRUNE_INTERVAL_TICKS, PRUNE_INTERVAL_TICKS);

        getLogger().info("HitReplay enabled.");
    }

    @Override
    public void onDisable() {
        if (recordTask != null) recordTask.cancel();
        if (pruneTask != null) pruneTask.cancel();
        for (UUID id : new HashSet<>(viewerTasks.keySet())) {
            Player v = Bukkit.getPlayer(id);
            if (v != null) restoreViewer(v);
        }
        viewerTasks.clear();
        getLogger().info("HitReplay disabled.");
    }

    private void recordFrame(Player p) {
        UUID id = p.getUniqueId();
        Location loc = p.getLocation();
        bufferData.computeIfAbsent(id, k -> new float[BUFFER_FRAMES * FRAME_SIZE]);
        bufferHead.putIfAbsent(id, 0);
        bufferCount.putIfAbsent(id, 0);
        float[] buf = bufferData.get(id);
        int head = bufferHead.get(id);
        buf[head * FRAME_SIZE]     = (float) loc.getX();
        buf[head * FRAME_SIZE + 1] = (float) loc.getY();
        buf[head * FRAME_SIZE + 2] = (float) loc.getZ();
        buf[head * FRAME_SIZE + 3] = loc.getYaw();
        buf[head * FRAME_SIZE + 4] = loc.getPitch();
        buf[head * FRAME_SIZE + 5] = (float) p.getHealth();
        bufferWorld.put(id, loc.getWorld().getName());
        int next = (head + 1) % BUFFER_FRAMES;
        bufferHead.put(id, next);
        int count = bufferCount.get(id);
        if (count < BUFFER_FRAMES) bufferCount.put(id, count + 1);
    }

    private float[] snapshotBuffer(UUID id) {
        float[] buf = bufferData.get(id);
        if (buf == null) return new float[0];
        int head = bufferHead.get(id);
        int count = bufferCount.get(id);
        float[] out = new float[count * FRAME_SIZE];
        int start = (head - count + BUFFER_FRAMES) % BUFFER_FRAMES;
        for (int i = 0; i < count; i++) {
            int src = ((start + i) % BUFFER_FRAMES) * FRAME_SIZE;
            System.arraycopy(buf, src, out, i * FRAME_SIZE, FRAME_SIZE);
        }
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        float[] vFrames = snapshotBuffer(victim.getUniqueId());
        float[] aFrames = snapshotBuffer(attacker.getUniqueId());
        int frameCount = Math.max(vFrames.length, aFrames.length) / FRAME_SIZE;
        String world = bufferWorld.getOrDefault(victim.getUniqueId(), victim.getWorld().getName());
        allHits.add(0, new HitRecord(
            System.currentTimeMillis(),
            victim.getName(), victim.getUniqueId(),
            attacker.getName(), attacker.getUniqueId(),
            vFrames, aFrames, world, frameCount
        ));
    }

    private void pruneOldHits() {
        long cutoff = System.currentTimeMillis() - MAX_HIT_AGE_MS;
        Iterator<HitRecord> it = allHits.iterator();
        int removed = 0;
        while (it.hasNext()) {
            if (it.next().timestamp() < cutoff) { it.remove(); removed++; }
        }
        if (removed > 0) getLogger().info("[HitReplay] Pruned " + removed + " old records.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hitreplay.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage("§cOnly players can use this.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
            stopReplay(viewer);
            return true;
        }
        if (args.length != 1) {
            viewer.sendMessage("§eUsage: /replay <player> | /replay stop");
            return true;
        }
        List<HitRecord> hits = getHitsForPlayer(args[0]);
        if (hits.isEmpty()) {
            viewer.sendMessage("§cNo hit records found for §e" + args[0] + "§c.");
            return true;
        }
        openGui(viewer, hits, args[0]);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("hitreplay.admin")) return List.of();
        if (args.length == 1) {
            Set<String> names = new HashSet<>();
            names.add("stop");
            for (HitRecord r : allHits) { names.add(r.victimName()); names.add(r.attackerName()); }
            return names.stream().filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }

    private List<HitRecord> getHitsForPlayer(String name) {
        List<HitRecord> result = new ArrayList<>();
        for (HitRecord r : allHits) {
            if (r.victimName().equalsIgnoreCase(name) || r.attackerName().equalsIgnoreCase(name))
                result.add(r);
        }
        return result;
    }

    private void openGui(Player viewer, List<HitRecord> hits, String targetName) {
        int rows = Math.min(6, Math.max(1, (int) Math.ceil(hits.size() / 9.0)));
        Inventory inv = Bukkit.createInventory(null, rows * 9, GUI_TITLE + " — " + targetName);
        for (int i = 0; i < Math.min(hits.size(), rows * 9); i++) {
            HitRecord rec = hits.get(i);
            boolean isVictim = rec.victimName().equalsIgnoreCase(targetName);
            ItemStack item = new ItemStack(isVictim ? Material.RED_DYE : Material.LIME_DYE);
            ItemMeta meta = item.getItemMeta();
            String timeStr = TIME_FORMAT.format(new Date(rec.timestamp()));
            String opponent = isVictim ? rec.attackerName() : rec.victimName();
            String role = isVictim ? "§cHit by " : "§aHit ";
            int seconds = (rec.frameCount() * RECORD_INTERVAL_TICKS) / 20;
            meta.displayName(Component.text(role + opponent + " at " + timeStr));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Time: §f" + timeStr));
            lore.add(Component.text("§7Duration: §f" + seconds + "s"));
            lore.add(Component.text("§7Victim: §c" + rec.victimName()));
            lore.add(Component.text("§7Attacker: §e" + rec.attackerName()));
            lore.add(Component.text(""));
            lore.add(Component.text("§eClick to watch replay"));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }
        openGuis.put(viewer.getUniqueId(), hits);
        viewer.openInventory(inv);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        if (event.getCurrentItem() == null) return;
        if (!event.getView().title().toString().contains(GUI_TITLE)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        List<HitRecord> hits = openGuis.get(viewer.getUniqueId());
        if (hits == null || slot < 0 || slot >= hits.size()) return;
        viewer.closeInventory();
        startReplay(viewer, hits.get(slot));
    }

    @EventHandler
    public void onGuiClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) return;
        if (!event.getView().title().toString().contains(GUI_TITLE)) return;
        openGuis.remove(viewer.getUniqueId());
    }

    private void startReplay(Player viewer, HitRecord rec) {
        stopReplay(viewer);
        viewerPrevMode.put(viewer.getUniqueId(), viewer.getGameMode());
        viewerPrevLoc.put(viewer.getUniqueId(), viewer.getLocation().clone());
        viewer.setGameMode(GameMode.SPECTATOR);

        int frameCount = rec.frameCount();
        if (frameCount == 0) { viewer.sendMessage("§cNo frames in replay."); restoreViewer(viewer); return; }

        org.bukkit.World world = Bukkit.getWorld(rec.worldName());
        if (world == null) world = viewer.getWorld();

        if (rec.victimFrames().length >= FRAME_SIZE) {
            float[] vf = rec.victimFrames();
            viewer.teleport(new Location(world, vf[0], vf[1], vf[2], vf[3], vf[4]));
        }

        int seconds = (frameCount * RECORD_INTERVAL_TICKS) / 20;
        viewer.sendMessage("§6[HitReplay] §fWatching §c" + rec.victimName()
                + " §fvs §e" + rec.attackerName() + " §f(" + seconds + "s) — /replay stop to exit");

        final int[] frame = {0};
        final org.bukkit.World finalWorld = world;

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (frame[0] >= frameCount) {
                    viewer.sendMessage("§6[HitReplay] §fReplay finished.");
                    restoreViewer(viewer);
                    viewerTasks.remove(viewer.getUniqueId());
                    cancel();
                    return;
                }
                float[] vf = rec.victimFrames();
                int fi = frame[0];
                if (vf.length >= (fi + 1) * FRAME_SIZE) {
                    int b = fi * FRAME_SIZE;
                    viewer.teleport(new Location(finalWorld, vf[b], vf[b+1], vf[b+2], vf[b+3], vf[b+4]));
                }
                if (fi % 10 == 0) {
                    float vHp = getHealth(rec.victimFrames(), fi);
                    float aHp = getHealth(rec.attackerFrames(), fi);
                    viewer.sendActionBar(Component.text(
                        "§c" + rec.victimName() + " §f" + String.format("%.1f", vHp) + "hp  " +
                        "§e" + rec.attackerName() + " §f" + String.format("%.1f", aHp) + "hp"
                    ));
                }
                frame[0]++;
            }
        }.runTaskTimer(HitReplay.this, 0L, RECORD_INTERVAL_TICKS);

        viewerTasks.put(viewer.getUniqueId(), task);
    }

    private float getHealth(float[] frames, int fi) {
        if (frames == null || frames.length < (fi + 1) * FRAME_SIZE) return 0f;
        return frames[fi * FRAME_SIZE + 5];
    }

    private void stopReplay(Player viewer) {
        BukkitTask t = viewerTasks.remove(viewer.getUniqueId());
        if (t != null) { t.cancel(); restoreViewer(viewer); viewer.sendMessage("§6[HitReplay] §fReplay stopped."); }
    }

    private void restoreViewer(Player viewer) {
        UUID id = viewer.getUniqueId();
        GameMode prev = viewerPrevMode.remove(id);
        Location prevLoc = viewerPrevLoc.remove(id);
        if (prev != null) viewer.setGameMode(prev);
        if (prevLoc != null) viewer.teleport(prevLoc);
    }
}
