package dev.heronex;

import java.util.*;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;

final class Trials implements Listener {
    private static final int RADIUS = 14;
    private enum DifficultyMode {
        NORMAL(240_000, 4, 0), VETERAN(210_000, 2, 1), NIGHTMARE(180_000, 1, 2);

        final long limitMs;
        final int revives;
        final int extraEnemies;

        DifficultyMode(long limitMs, int revives, int extraEnemies) {
            this.limitMs = limitMs;
            this.revives = revives;
            this.extraEnemies = extraEnemies;
        }
    }

    private record Slam(LivingEntity boss, Location center, long resolvesAt) {}
    private static final int TOTAL_STAGES = 4;
    private final RuneTrial plugin;
    private final NamespacedKey mobTag;
    private final Set<UUID> party = new LinkedHashSet<>();
    private final Map<UUID, LivingEntity> mobs = new HashMap<>();
    private final Map<UUID, Location> respawns = new HashMap<>();
    private final BossBar bar = Bukkit.createBossBar("Rune Trial", BarColor.PURPLE, BarStyle.SEGMENTED_10);
    private Location arena;
    private int stage;
    private long started;
    private long nextWaveAt;
    private boolean reinforcements;
    private DifficultyMode mode = DifficultyMode.NORMAL;
    private int initialPartySize;
    private int revivesRemaining;
    private long nextSlamAt;
    private Slam slam;

    Trials(RuneTrial plugin) {
        this.plugin = plugin;
        this.mobTag = new NamespacedKey(plugin, "trial_mob");
        arena = plugin.getConfig().getLocation("arena");
    }

    boolean command(Player player, String[] args) {
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!Set.of("setarena", "join", "leave", "start", "best").contains(action)) return false;
        boolean modeCommand = Set.of("start", "best").contains(action);
        if (args.length > (modeCommand ? 2 : 1)) {
            player.sendMessage("Use /rune " + action + (modeCommand ? " [normal|veteran|nightmare]" : ""));
            return true;
        }
        DifficultyMode selected;
        try {
            selected = args.length == 2 ? DifficultyMode.valueOf(args[1].toUpperCase(Locale.ROOT)) : DifficultyMode.NORMAL;
        } catch (IllegalArgumentException invalid) {
            player.sendMessage("Choose normal, veteran or nightmare.");
            return true;
        }
        switch (action) {
            case "setarena" -> {
                if (!player.hasPermission("runetrial.admin") || stage > 0 || !party.isEmpty()) {
                    player.sendMessage("An operator can set the arena while the lobby is empty.");
                    return true;
                }
                arena = player.getLocation();
                plugin.getConfig().set("arena", arena);
                plugin.saveConfig();
                player.sendMessage("Trial center saved. Clear a 28-block-wide arena around this point.");
            }
            case "join" -> {
                if (stage > 0 || party.size() >= 4 || !inside(player.getLocation())
                        || player.isDead() || arena.getWorld().getDifficulty() == Difficulty.PEACEFUL || player.getGameMode() != GameMode.SURVIVAL) {
                    player.sendMessage("Join the idle lobby in Survival from inside the trial arena.");
                    return true;
                }
                party.add(player.getUniqueId());
                player.sendMessage("Joined the trial lobby. Use /rune start with 1-4 players ready.");
            }
            case "start" -> {
                if (stage > 0 || !party.contains(player.getUniqueId()) || arena == null || arena.getWorld().getDifficulty() == Difficulty.PEACEFUL
                    || party.stream().map(Bukkit::getPlayer).anyMatch(member ->
                        member == null || member.isDead() || member.getGameMode() != GameMode.SURVIVAL
                        || !inside(member.getLocation()))) {
                    player.sendMessage("All queued players must be alive in Survival inside the arena.");
                    return true;
                }
                mode = selected;
                initialPartySize = party.size();
                revivesRemaining = mode.revives;
                started = System.currentTimeMillis();
                stage = 1;
                party.stream().map(Bukkit::getPlayer).forEach(bar::addPlayer);
                nextWaveAt = started;
            }
            case "leave" -> {
                remove(player);
                player.sendMessage("Left the trial.");
            }
            case "best" -> {
                long best = plugin.getConfig().getLong(bestPath(player.getUniqueId(), selected));
                player.sendMessage(best > 0 ? selected + " personal best: " + best / 1000.0 + "s" : "No completed " + selected + " trial.");
            }
        }
        return true;
    }

    private boolean inside(Location location) {
        return arena != null && arena.getWorld() != null && arena.getWorld().equals(location.getWorld())
            && arena.distanceSquared(location) <= RADIUS * RADIUS;
    }

    private String bestPath(UUID player, DifficultyMode difficulty) {
        return difficulty == DifficultyMode.NORMAL ? "trial.best-ms." + player
            : "trial.records." + difficulty.name() + "." + player;
    }

    private void spawn(EntityType type, int count) {
        World world = arena.getWorld();
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2 / count;
            Location spawn = arena.clone().add(Math.cos(angle) * 5, 0, Math.sin(angle) * 5);
            if (!spawn.getBlock().isPassable() || !spawn.clone().add(0, 1, 0).getBlock().isPassable()) spawn = arena;
            var entity = (LivingEntity) world.spawnEntity(spawn, type);
            entity.getPersistentDataContainer().set(mobTag, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            entity.setRemoveWhenFarAway(false);
            mobs.put(entity.getUniqueId(), entity);
            if (entity instanceof Mob mob) {
                party.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                    .filter(player -> !player.isDead() && inside(player.getLocation()))
                    .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(entity.getLocation())))
                    .ifPresent(mob::setTarget);
            }
        }
    }

    void tick() {
        if (stage == 0) return;
        long now = System.currentTimeMillis();
        if (now - started >= mode.limitMs) { stop("Time expired."); return; }
        for (UUID id : List.copyOf(party)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) party.remove(id);
            else if (!player.isDead() && (!inside(player.getLocation()) || player.getGameMode() != GameMode.SURVIVAL)) {
                remove(player);
                player.sendMessage("You left the trial boundary or Survival mode.");
            }
        }
        if (party.isEmpty()) { stop(null); return; }
        long timeLeft = Math.min(mode.limitMs, mode.limitMs - now + started);
        bar.setTitle(mode + " | Wave " + stage + "/" + TOTAL_STAGES + " | " + timeLeft / 1000
            + "s | Revives " + revivesRemaining);
        bar.setProgress(timeLeft / (double) mode.limitMs);
        if (nextWaveAt != 0 && now >= nextWaveAt) {
            nextWaveAt = 0;
            int enemies = initialPartySize + mode.extraEnemies;
            switch (stage) {
                case 1 -> spawn(EntityType.ZOMBIE, 2 + enemies);
                case 2 -> spawn(EntityType.SKELETON, 2 + enemies);
                case 3 -> { spawn(EntityType.HUSK, enemies * 2); spawn(EntityType.SPIDER, 2); }
                case 4 -> {
                    spawn(EntityType.RAVAGER, 1);
                    spawn(EntityType.PILLAGER, enemies);
                    nextSlamAt = now + 5_000;
                }
                default -> throw new IllegalStateException("Unknown stage " + stage);
            }
        }
        if (stage == TOTAL_STAGES && !reinforcements && mobs.values().stream().anyMatch(
                mob -> mob.getType() == EntityType.RAVAGER && !mob.isDead() && mob.getHealth() <= 50)) {
            reinforcements = true;
            notifyParty("The ravager called reinforcements.");
            spawn(EntityType.VINDICATOR, 1 + initialPartySize + mode.extraEnemies);
        }
        if (stage == TOTAL_STAGES && nextSlamAt > 0) advanceSlam(now);
        for (var mob : mobs.values()) {
            if (!mob.isValid()) { stop("A trial mob disappeared. Resetting the arena."); return; }
            if (!inside(mob.getLocation())) mob.teleport(arena);
        }
    }

    private void advanceSlam(long now) {
        if (slam != null && !slam.boss.isValid()) slam = null;
        if (slam == null && now >= nextSlamAt) {
            LivingEntity boss = mobs.values().stream()
                .filter(mob -> mob.getType() == EntityType.RAVAGER && mob.isValid()).findFirst().orElse(null);
            if (boss == null) return;
            slam = new Slam(boss, boss.getLocation(), now + 3_000);
            nextSlamAt = now + (reinforcements ? 7_000 : 10_000);
            notifyParty("Ravager slam incoming! Move outside the particle ring.");
        }
        if (slam == null) return;
        Location center = slam.center;
        World world = center.getWorld();
        if (now >= slam.resolvesAt) {
            LivingEntity source = slam.boss;
            slam = null;
            for (UUID id : List.copyOf(party)) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && !player.isDead() && world.equals(player.getWorld())
                        && player.getLocation().distanceSquared(center) <= 16) {
                    player.damage(6 + 2 * mode.extraEnemies, source);
                }
            }
            world.spawnParticle(Particle.EXPLOSION, center, 4, 1, 0.2, 1, 0);
            slam = null;
            return;
        }
        for (int point = 0; point < 24; point++) {
            double angle = point * Math.PI / 12;
            world.spawnParticle(Particle.FLAME,
                center.clone().add(4 * Math.cos(angle), .2, 4 * Math.sin(angle)), 1, 0, 0, 0, 0);
        }
    }

    private void notifyParty(String message) {
        party.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).forEach(player -> player.sendMessage(message));
    }

    private void remove(Player player) {
        party.remove(player.getUniqueId());
        respawns.remove(player.getUniqueId());
        bar.removePlayer(player);
        if (party.isEmpty()) stop(null);
    }

    void stop(String message) {
        if (message != null) notifyParty(message);
        bar.removeAll();
        mobs.values().forEach(Entity::remove);
        mobs.clear();
        party.clear();
        respawns.clear();
        stage = 0;
        nextWaveAt = 0;
        reinforcements = false;
        slam = null;
        nextSlamAt = 0;
    }

    @EventHandler public void target(EntityTargetLivingEntityEvent event) {
        if (mobs.containsKey(event.getEntity().getUniqueId()) && event.getTarget() != null
            && !party.contains(event.getTarget().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true) public void damage(EntityDamageByEntityEvent event) {
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) attacker = shooter;
        if (mobs.containsKey(event.getEntity().getUniqueId()) && !party.contains(attacker.getUniqueId())
            || mobs.containsKey(attacker.getUniqueId()) && event.getEntity() instanceof Player player
                && !party.contains(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler public void death(EntityDeathEvent event) {
        if (mobs.remove(event.getEntity().getUniqueId()) == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (!mobs.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (stage < TOTAL_STAGES) {
            stage++;
            nextWaveAt = now + 4_000;
            notifyParty("Wave cleared. Four seconds until the next wave.");
            return;
        }
        if (now - started >= mode.limitMs) { stop("Time expired."); return; }
        long time = Math.max(1, now - started);
        for (UUID id : party) {
            long best = plugin.getConfig().getLong(bestPath(id, mode), Long.MAX_VALUE);
            if (time < best) plugin.getConfig().set(bestPath(id, mode), time);
        }
        plugin.saveConfig();
        stop("Trial cleared in " + time / 1000.0 + "s.");
    }

    @EventHandler public void playerDeath(PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (stage == 0 || !party.contains(id)) return;
        if (revivesRemaining == 0) {
            remove(event.getEntity());
            event.getEntity().sendMessage("No team revives left. Your trial is over.");
            return;
        }
        revivesRemaining--;
        respawns.put(id, arena.clone());
    }

    @EventHandler public void respawn(PlayerRespawnEvent event) {
        Location destination = respawns.remove(event.getPlayer().getUniqueId());
        if (destination != null) event.setRespawnLocation(destination);
    }

    @EventHandler public void quit(PlayerQuitEvent event) { remove(event.getPlayer()); }

    void clearLoadedMobs() {
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) clearOrphan(entity);
    }

    @EventHandler public void load(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) clearOrphan(entity);
    }

    private void clearOrphan(Entity entity) {
        if (entity.getPersistentDataContainer().has(mobTag) && !mobs.containsKey(entity.getUniqueId())) entity.remove();
    }
}
