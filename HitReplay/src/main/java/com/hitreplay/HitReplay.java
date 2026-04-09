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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class HitReplay extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final int MAX_BUFFER = 300;
    private static final long MAX_HIT_AGE_MS = 15L * 60 * 60 * 1000;
    private static final long PRUNE_INTERVAL = 5L * 60 * 20;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss");

    private final Map<UUID, Deque<Frame>> buffers = new HashMap<>();
    private final List<HitRecord> allHits = new ArrayList<>();
    private final Map<UUID, Inventory> openGuis = new HashMap<>();
    private final Map<UUID, List<HitRecord>> guiHits = new HashMap<>();
    private final Map<UUID, ReplaySession> sessions = new HashMap<>();

    private BukkitTask recordTask;
    private BukkitTask pruneTask;

    static class Frame {
        final double x, y, z;
        final float yaw, pitch, health;
        final String world;

        Frame(Player p) {
            Location loc = p.getLocation();
            this.x = loc.getX();
            this.y = loc.getY();
            this.z = loc.getZ();
            this.yaw = loc.getYaw();
            this.pitch = loc.getPitch();
            this.health = (float) p.getHealth();
            this.world = loc.getWorld().getName();
        }

        Location toLocation() {
            org.bukkit.World w = Bukkit.getWorld(world);
            if (w == null) w = Bukkit.getWorlds().get(0);
            return new Location(w, x, y, z, yaw, pitch);
        }
    }

    static class HitRecord {
        final long timestamp;
        final String victimName, attackerName;
        final UUID victimId, attackerId;
        final List<Frame> victimFrames, attackerFrames;

        HitRecord(long timestamp,
                  String victimName, UUID victimId,
                  String attackerName, UUID attackerId,
                  List<Frame> victimFrames, List<Frame> attackerFrames) {
            this.timestamp = timestamp;
            this.victimName = victimName;
            this.victimId = victimId;
            this.attackerName = attackerName;
            this.attackerId = attackerId;
            this.victimFrames = victimFrames;
            this.attackerFrames = attackerFrames;
        }

        int frameCount() {
            return Math.max(victimFrames.size(), attackerFrames.size());
        }
    }

    static class ReplaySession {
        final BukkitTask task;
        final ArmorStand victimStand, attackerStand;
        final GameMode prevMode;
        final Location prevLoc;

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
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID id = p.getUniqueId();
                    Deque<Frame> buf = buffers.computeIfAbsent(id, k -> new ArrayDeque<>());
                    buf.addLast(new Frame(p));
                    while (buf.size() > MAX_BUFFER) buf.pollFirst();
                }
            }
        }.runTaskTimer(this, 0L, 1L);

        pruneTask = new BukkitRunnable() {
            @Override
            public void run() {
                long cutoff = System.currentTimeMillis() - MAX_HIT_AGE_MS;
                allHits.removeIf(r -> r.timestamp < cutoff);
            }
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        Deque<Frame> vBuf = buffers.get(victim.getUniqueId());
        Deque<Frame> aBuf = buffers.get(attacker.getUniqueId());

        List<Frame> vFrames = vBuf != null ? new ArrayList<>(vBuf) : new ArrayList<>();
        List<Frame> aFrames = aBuf != null ? new ArrayList<>(aBuf) : new ArrayList<>();

        allHits.add(0, new HitRecord(
            System.currentTimeMillis(),
            victim.getName(), victim.getUniqueId(),
            attacker.getName(), attacker.getUniqueId(),
            vFrames, aFrames
        ));

        getLogger().info("[HitReplay] Saved: " + victim.getName()
                + " hit by " + attacker.getName()
                + " — v:" + vFrames.size() + " a:" + aFrames.size() + " frames");
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
            for (HitRecord r : allHits) { names.add(r.victimName); names.add(r.attackerName); }
            return names.stream().filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }

    private List<HitRecord> hitsFor(String name) {
        List<HitRecord> out = new ArrayList<>();
        for (HitRecord r : allHits)
            if (r.victimName.equalsIgnoreCase(name) || r.attackerName.equalsIgnoreCase(name))
                out.add(r);
        return out;
    }

    private void openGui(Player viewer, List<HitRecord> hits, String targetName) {
        int size = Math.max(9, Math.min(54, (int) Math.ceil(hits.size() / 9.0) * 9));
        Inventory inv = Bukkit.createInventory(null, size, Component.text("§6HitReplay — " + targetName));
        for (int i = 0; i < Math.min(hits.size(), size); i++) {
            HitRecord rec = hits.get(i);
            boolean isVictim = rec.victimName.equalsIgnoreCase(targetName);
            ItemStack item = new ItemStack(isVictim ? Material.RED_DYE : Material.LIME_DYE);
            ItemMeta meta = item.getItemMeta();
            String time = FMT.format(new Date(rec.timestamp));
            String opponent = isVictim ? rec.attackerName : rec.victimName;
            int secs = rec.frameCount() / 20;
            meta.displayName(Component.text((isVictim ? "§cHit by §e" : "§aHit §e") + opponent + " §7at " + time));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Time: §f" + time));
            lore.add(Component.text("§7Duration: §f" + secs + "s"));
            lore.add(Component.text("§7Victim: §c" + rec.victimName));
            lore.add(Component.text("§7Attacker: §e" + rec.attackerName));
            lore.add(Component.text("§7Frames: §f" + rec.frameCount()));
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

        int frameCount = rec.frameCount();
        viewer.sendMessage("§6[HitReplay] §fLoading — §e" + frameCount + " frames (" + (frameCount / 20) + "s)");

        if (frameCount == 0) {
            viewer.sendMessage("§cNo frames recorded.");
            return;
        }

        List<Frame> vFrames = rec.victimFrames;
        Location startLoc = !vFrames.isEmpty() ? vFrames.get(0).toLocation() : viewer.getLocation();

        ArmorStand victimStand   = spawnGhost(startLoc, "§c" + rec.victimName);
        ArmorStand attackerStand = spawnGhost(startLoc, "§e" + rec.attackerName);

        GameMode prevMode = viewer.getGameMode();
        Location prevLoc  = viewer.getLocation().clone();

        viewer.setGameMode(GameMode.SPECTATOR);
        viewer.teleport(startLoc.clone().add(0, 5, 0));

        int secs = frameCount / 20;
        viewer.sendMessage("§6[HitReplay] §fWatching §c" + rec.victimName
                + " §fvs §e" + rec.attackerName
                + " §f(" + secs + "s) — fly freely — §e/replay stop §fto exit");

        final int[] frame = {0};
        final ArmorStand finalVS = victimStand;
        final ArmorStand finalAS = attackerStand;
        final List<Frame> finalVF = rec.victimFrames;
        final List<Frame> finalAF = rec.attackerFrames;

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!viewer.isOnline()) { finalVS.remove(); finalAS.remove(); cancel(); return; }
                if (frame[0] >= frameCount) {
                    viewer.sendMessage("§6[HitReplay] §fReplay finished. §e/replay stop §fto return.");
                    cancel();
                    return;
                }
                int fi = frame[0];
                if (fi < finalVF.size()) finalVS.teleport(finalVF.get(fi).toLocation());
                if (fi < finalAF.size()) finalAS.teleport(finalAF.get(fi).toLocation());
                if (fi % 20 == 0) {
                    float vHp = fi < finalVF.size() ? finalVF.get(fi).health : 0;
                    float aHp = fi < finalAF.size() ? finalAF.get(fi).health : 0;
                    viewer.sendActionBar(Component.text(
                        "§c" + rec.victimName + " §f" + String.format("%.1f", vHp) + "hp  "
                        + "§e" + rec.attackerName + " §f" + String.format("%.1f", aHp) + "hp  "
                        + "§7[" + (fi / 20) + "/" + secs + "s]"
                    ));
                }
                frame[0]++;
            }
        }.runTaskTimer(this, 0L, 1L);

        sessions.put(viewer.getUniqueId(),
                new ReplaySession(task, victimStand, attackerStand, prevMode, prevLoc));
    }

    private ArmorStand spawnGhost(Location loc, String name) {
        return loc.getWorld().spawn(loc, ArmorStand.class, as -> {
            as.setVisible(true);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setMarker(false);
            as.setBasePlate(false);
            as.setArms(true);
            as.customName(Component.text(name));
            as.setCustomNameVisible(true);
            as.setGlowing(true);
        });
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
