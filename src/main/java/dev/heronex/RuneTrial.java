package dev.heronex;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class RuneTrial extends JavaPlugin implements Listener {
    private enum Rune {
        MEND(20, 3), WARD(35, 6), PULSE(45, 12);

        final int cost;
        final int unlockKills;

        Rune(int cost, int unlockKills) {
            this.cost = cost;
            this.unlockKills = unlockKills;
        }
    }

    private static final int MAX_MANA = 100;
    private final Map<UUID, Integer> mana = new HashMap<>();
    private final Map<UUID, EnumMap<Rune, Long>> readyAt = new HashMap<>();

    private Trials trials;

    @Override public void onEnable() {
        trials = new Trials(this);
        getServer().getPluginManager().registerEvents(trials, this);
        trials.clearLoadedMobs();
        getServer().getScheduler().runTaskTimer(this, trials::tick, 20L, 20L);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                mana.computeIfPresent(player.getUniqueId(), (id, value) -> Math.min(MAX_MANA, value + 5));
            }
        }, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::saveConfig, 1200L, 1200L);
    }

    @Override public void onDisable() { if (trials != null) trials.stop(null); saveConfig(); }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        // Keep session mana and cooldowns across reconnects.
        saveConfig();
    }

    private String progress(UUID id) { return "kills." + id; }

    @EventHandler public void defeat(EntityDeathEvent event) {
        Player player = event.getEntity().getKiller();
        if (player == null || !(event.getEntity() instanceof org.bukkit.entity.Monster)) return;
        String key = progress(player.getUniqueId());
        int old = getConfig().getInt(key);
        if (old >= Rune.PULSE.unlockKills) return;
        int kills = old + 1;
        getConfig().set(key, kills);
        for (Rune rune : Rune.values()) {
            if (kills == rune.unlockKills) {
                saveConfig();
                player.sendMessage("Rune unlocked: " + rune.name());
            }
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length > 0 && trials.command(player, args)) return true;
        UUID id = player.getUniqueId();
        int kills = getConfig().getInt(progress(id));
        int available = mana.getOrDefault(id, MAX_MANA);
        if (args.length != 1) {
            player.sendMessage("Mana " + available + "/" + MAX_MANA + " | /rune join, start [difficulty], leave, best [difficulty] | Monster kills " + kills + "/12");
            for (Rune rune : Rune.values()) {
                player.sendMessage("/rune " + rune.name().toLowerCase(Locale.ROOT) + " | " + rune.cost
                    + " mana" + (kills < rune.unlockKills ? " | Unlocks at " + rune.unlockKills + " kills" : ""));
            }
            return true;
        }
        Rune rune;
        try { rune = Rune.valueOf(args[0].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { player.sendMessage("Choose mend, ward, or pulse."); return true; }
        if (kills < rune.unlockKills || available < rune.cost) {
            player.sendMessage("You need to unlock the rune and have " + rune.cost + " mana.");
            return true;
        }
        EnumMap<Rune, Long> cooldowns = readyAt.computeIfAbsent(id, ignored -> new EnumMap<>(Rune.class));
        long now = System.nanoTime();
        Long ready = cooldowns.get(rune);
        if (ready != null && ready - now > 0) {
            player.sendMessage("That rune is recharging.");
            return true;
        }
        if (!cast(player, rune)) return true;
        mana.put(id, available - rune.cost);
        cooldowns.put(rune, now + java.util.concurrent.TimeUnit.SECONDS.toNanos(8));
        player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1, 0), 25, 0.6, 0.5, 0.6, 0.3);
        player.sendMessage("Cast " + rune.name() + " | Mana " + (available - rune.cost) + "/" + MAX_MANA);
        return true;
    }

    private boolean cast(Player player, Rune rune) {
        switch (rune) {
            case MEND -> {
                double max = Objects.requireNonNull(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)).getValue();
                if (player.getHealth() >= max) { player.sendMessage("Your health is already full."); return false; }
                player.setHealth(Math.min(max, player.getHealth() + 6.0));
            }
            case WARD -> player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.RESISTANCE, 100, 0));
            case PULSE -> {
                var targets = player.getNearbyEntities(4, 4, 4).stream()
                    .filter(org.bukkit.entity.Monster.class::isInstance)
                    .map(org.bukkit.entity.Monster.class::cast)
                    .filter(monster -> monster.getLocation().distanceSquared(player.getLocation()) <= 16
                        && player.hasLineOfSight(monster))
                    .toList();
                if (targets.isEmpty()) { player.sendMessage("No visible hostile monsters within four blocks."); return false; }
                targets.forEach(monster -> monster.damage(6.0, player));
            }
        }
        return true;
    }
}
