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
import org.bukkit.entity.ArmorStand;
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

    private static final int RECORD_INTERVAL = 2;
    private static final int BUFFER_FRAMES = 150;
    private static final long MAX_HIT_AGE_MS = 15L * 60 * 60 * 1000;
    private static final long PRUNE_INTERVAL = 5L * 60 * 20;
    private static final int FS = 6;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss");

    private final Map<UUID, float[]>  bufData  = new HashMap<>();
    private final Map<UUID, Integer>  bufHead  = new HashMap<>();
    private final Map<UUID, Integer>  bufCount = new HashMap<>();
    private final Map<UUID, String>   bufWorld = new HashMap<>();
    private final List<HitRecord>     allHits  = new ArrayList<>();
    private final Map<UUID, Inventory>       openGuis = new HashMap<>();
    private final Map<UUID, List<HitRecord>> guiHits  = new HashMap<>();
    private final Map<UUID, ReplaySession>   sessions = new HashMap<>();

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

    static class ReplaySession {
        BukkitTask task;
        ArmorStand victimStand;
        ArmorStand attackerStand;
        GameMode prevMode;
        Location prevLoc;

        ReplaySession(BukkitTask task, ArmorStand vs, ArmorStand as,
                      GameMode prevMode, Location prevLoc) {
            this.task = task;
            this.victimStand = vs;
            this.attackerStand = as;
            this.prevMode = prevMode;
            this.prevLoc = prevLoc;
        }

        void cleanup() {
            if (task != null) task.cancel();
            if (victimStand != null && !victimStand.isDead()) victimStand.remove();
            if (attackerStand != null && !attackerStand.isDead()) attackerStand.remove();
        }
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("replay").setExecutor(this);
        getCommand("replay").setTabCompleter(this);

        recordTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) recordFrame(p);
            }
        }.runTaskTimer(this, 0L, RECORD_INTERVAL);

        pruneTask = new BukkitRunnable() {
            @Override public void run() { pruneOldHits(); }
        }.runTaskTimer(this, PRUNE_INTERVAL, PRUNE_INTERVAL);

        getLogger().info("HitReplay enabled.");
    }

    @Override
    public void onDisable() {
        if (recordTask != null) recordTask.cancel();
        if (pruneTask != null) pruneTask.cancel();
        for (UUID id : new HashSet<>(sessions.keySet())) {
            Player v = Bukkit.getPlayer(id);
            ReplaySession s = sessions.get(id);
            s.cleanup();
            if (v != null) { v.setGameMode(s.prevMode); v.teleport(s.prevLoc); }
        }
        sessions.clear();
        getLogger().info("HitReplay disabled.");
    }

    private void recordFrame(Player p) {
        UUID id = p.getUniqueId();
        Location loc = p.getLocation();
        bufData.computeIfAbsent(id, k -> new float[BUFFER_FRAMES * FS]);
        bufHead.putIfAbsent(id, 0);
        bufCount.putIfAbsent(id, 0);
        float[] buf = bufData.get(id);
        int head = bufHead.get(id);
        int base = head * FS;
        buf[base]     = (float) loc.getX();
        buf[base + 1] = (float) loc.getY();
        buf[base + 2] = (float) loc.getZ();
        buf[base + 3] = loc.getYaw();
        buf[base + 4] = loc.getPitch();
        buf[base + 5] = (float) p.getHealth();
        bufWorld.put(id, loc.getWorld().getName());
        bufHead.put(id, (head + 1) % BUFFER_FRAMES);
        int c = bufCount.get(id);
        if (c < BUFFER_FRAMES) bufCount.put(id, c + 1);
    }

    private float[] snapshot(UUID id) {
        float[] buf = bufData.get(id);
        if (buf == null) return new float[0];
        int head = bufHead.get(id);
        int count = bufCount.get(id);
        float[] out = new float[count * FS];
        int start = (head - count + BUFFER_FRAMES) % BUFFER_FRAMES;
        for (int i = 0; i < count; i++)
            System.arraycopy(buf, ((start + i) % BUFFER_FRAMES) * FS, out, i * FS, FS);
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        float[] vf = snapshot(victim.getUniqueId());
        float[] af = snapshot(attacker.getUniqueId());
        int fc = Math.max(vf.length, af.length) / FS;
        String world = bufWorld.getOrDefault(victim.getUniqueId(), victim.getWorld().getName());
        allHits.add(0, new HitRecord(
            System.currentTimeMillis(),
            victim.getName(), victim.getUniqueId(),
            attacker.getName(), attacker.getUniqueId(),
            vf, af, world, fc
        ));
    }

    private void pruneOldHits() {
        long cutoff = System.currentTimeMillis() - MAX_HIT_AGE_MS;
        Iterator<HitRecord> it = allHits.iterator();
        int n = 0;
        while (it.hasNext()) { if (it.next().timestamp() < cutoff) { it.remove(); n++; } }
        if (n > 0) getLogger().info("[HitReplay] Pruned " + n + " old records.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("hitreplay.admin")) { sender.sendMessage("§cNo permission."); return true; }
        if (!(sender instanceof Player viewer)) { sender.sendMessage("§cOnly players can use this."); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) { stopReplay(viewer, true); return true; }
        if (args.length != 1) { viewer.sendMessage("§eUsage: /replay <player> | /replay stop"); return true; }
        List<HitRecord> hits = hitsFor(args[0]);
        if (hits.isEmpty()) { viewer.sendMessage("§cNo records found for §e" + args[0]); return true; }
        openGui(viewer, hits, args[0]);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("hitreplay.admin")) return List.of();
        if (args.length == 1) {
            Set<String> names = new HashSet<>();
            names.add("stop");
            for (HitRecord r : allHits) { names.add(r.victimName()); names.add(r.attackerName()); }
            return names.stream().filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }

    private List<HitRecord> hitsFor(String name) {
        List<HitRecord> out = new ArrayList<>();
        for (HitRecord r : allHits)
            if (r.victimName().equalsIgnoreCase(name) || r.attackerName().equalsIgnoreCase(name))
                out.add(r);
        return out;
    }

    private void openGui(Player viewer, List<HitRecord> hits, String targetName) {
        int size = Math.max(9, Math.min(54, (int) Math.ceil(hits.size() / 9.0) * 9));
        Inventory inv = Bukkit.createInventory(null, size, Component.text("§6HitReplay — " + targetName));
        for (int i = 0; i < Math.min(hits.size(), size); i++) {
            HitRecord rec = hits.get(i);
            boolean isVictim = rec.victimName().equalsIgnoreCase(targetName);
            ItemStack item = new ItemStack(isVictim ? Material.RED_DYE : Material.LIME_DYE);
            ItemMeta meta = item.getItemMeta();
            String time = FMT.format(new Date(rec.timestamp()));
            String opponent = isVictim ? rec.attackerName() : rec.victimName();
            int secs = (rec.frameCount() * RECORD_INTERVAL) / 20;
            meta.displayName(Component.text((isVictim ? "§cHit by §e" : "§aHit §e") + opponent + " §7at " + time));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Time: §f" + time));
            lore.add(Component.text("§7Duration: §f" + secs + "s"));
            lore.add(Component.text("§7Victim: §c" + rec.victimName()));
            lore.add(Component.text("§7Attacker: §e" + rec.attackerName()));
            lore.add(Component.text(""));
            lore.add(Component.text("§eClick to watch"));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }
        openGuis.put(viewer.getUniqueId(), inv);
        guiHits.put(viewer.getUniqueId(), hits);
        viewer.openInventory(inv);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        Inventory tracked = openGuis.get(viewer.getUniqueId());
        if (tracked == null || !event.getInventory().equals(tracked)) return;
        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        int slot = event.getRawSlot();
        List<HitRecord> hits = guiHits.get(viewer.getUniqueId());
        if (hits == null || slot < 0 || slot >= hits.size()) return;
        viewer.closeInventory();
        startReplay(viewer, hits.get(slot));
    }

    @EventHandler
    public void onGuiClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) return;
        Inventory tracked = openGuis.get(viewer.getUniqueId());
        if (tracked != null && event.getInventory().equals(tracked)) {
            openGuis.remove(viewer.getUniqueId());
            guiHits.remove(viewer.getUniqueId());
        }
    }

    private void startReplay(Player viewer, HitRecord rec) {
        stopReplay(viewer, false);

        org.bukkit.World world = Bukkit.getWorld(rec.worldName());
        if (world == null) world = viewer.getWorld();

        int frameCount = rec.frameCount();
        if (frameCount == 0) { viewer.sendMessage("§cNo frames recorded."); return; }

        float[] vf = rec.victimFrames();
        Location startLoc = vf.length >= FS
            ? new Location(world, vf[0], vf[1], vf[2], vf[3], vf[4])
            : viewer.getLocation();

        final org.bukkit.World finalWorld = world;
        ArmorStand victimStand   = spawnGhost(world, startLoc, "§c" + rec.victimName());
        ArmorStand attackerStand = spawnGhost(world, startLoc, "§e" + rec.attackerName());

        GameMode prevMode = viewer.getGameMode();
        Location prevLoc  = viewer.getLocation().clone();

        viewer.setGameMode(GameMode.SPECTATOR);
        viewer.teleport(startLoc.clone().add(0, 3, 0));

        int secs = (frameCount * RECORD_INTERVAL) / 20;
        viewer.sendMessage("§6[HitReplay] §fWatching §c" + rec.victimName()
                + " §fvs §e" + rec.attackerName()
                + " §f(" + secs + "s) — fly freely — §e/replay stop §fto exit");

        final int[] frame = {0};

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!viewer.isOnline()) { doCleanup(); cancel(); return; }

                if (frame[0] >= frameCount) {
                    viewer.sendMessage("§6[HitReplay] §fReplay finished. Use §e/replay stop §fto return.");
                    cancel();
                    return;
                }

                int fi = frame[0];

                float[] victimFrames = rec.victimFrames();
                if (victimFrames.length >= (fi + 1) * FS) {
                    int b = fi * FS;
                    victimStand.teleport(new Location(finalWorld,
                            victimFrames[b], victimFrames[b+1], victimFrames[b+2],
                            victimFrames[b+3], victimFrames[b+4]));
                }

                float[] attackerFrames = rec.attackerFrames();
                if (attackerFrames.length >= (fi + 1) * FS) {
                    int b = fi * FS;
                    attackerStand.teleport(new Location(finalWorld,
                            attackerFrames[b], attackerFrames[b+1], attackerFrames[b+2],
                            attackerFrames[b+3], attackerFrames[b+4]));
                }

                if (fi % 10 == 0) {
                    float vHp = getHp(rec.victimFrames(), fi);
                    float aHp = getHp(rec.attackerFrames(), fi);
                    int elapsed = (fi * RECORD_INTERVAL) / 20;
                    viewer.sendActionBar(Component.text(
                        "§c" + rec.victimName() + " §f" + String.format("%.1f", vHp) + "hp  " +
                        "§e" + rec.attackerName() + " §f" + String.format("%.1f", aHp) + "hp  " +
                        "§7[" + elapsed + "/" + secs + "s]"
                    ));
                }

                frame[0]++;
            }

            private void doCleanup() {
                if (!victimStand.isDead()) victimStand.remove();
                if (!attackerStand.isDead()) attackerStand.remove();
            }
        }.runTaskTimer(this, 0L, RECORD_INTERVAL);

        sessions.put(viewer.getUniqueId(),
                new ReplaySession(task, victimStand, attackerStand, prevMode, prevLoc));
    }

    private ArmorStand spawnGhost(org.bukkit.World world, Location loc, String name) {
        return world.spawn(loc, ArmorStand.class, as -> {
            as.setVisible(true);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setMarker(false);
            as.setBasePlate(false);
            as.setArms(false);
            as.customName(Component.text(name));
            as.setCustomNameVisible(true);
            as.setGlowing(true);
        });
    }

    private float getHp(float[] frames, int fi) {
        if (frames == null || frames.length < (fi + 1) * FS) return 0f;
        return frames[fi * FS + 5];
    }

    private void stopReplay(Player viewer, boolean msg) {
        ReplaySession s = sessions.remove(viewer.getUniqueId());
        if (s != null) {
            s.cleanup();
            viewer.setGameMode(s.prevMode);
            viewer.teleport(s.prevLoc);
            if (msg) viewer.sendMessage("§6[HitReplay] §fReplay stopped.");
        } else if (msg) {
            viewer.sendMessage("§cYou are not watching a replay.");
        }
    }
}
