package dev.templates.visual;

import dev.templates.selection.SelectionService;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SelectionVisualizer {
    private final JavaPlugin plugin;
    private final SelectionService selections;
    private BukkitTask task;

    public SelectionVisualizer(JavaPlugin plugin, SelectionService selections) {
        this.plugin = plugin;
        this.selections = selections;
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (var player : plugin.getServer().getOnlinePlayers()) {
                for (Block block : selections.blocks(player)) {
                    block.getWorld().spawnParticle(Particle.END_ROD,
                            block.getLocation().add(0.5, 0.5, 0.5), 1, 0.12, 0.12, 0.12, 0);
                }
            }
        }, 10L, 10L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }
}
