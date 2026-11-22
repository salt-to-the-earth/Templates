package dev.templates.visual;

import dev.templates.selection.SelectionService;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;

public final class SelectionVisualizer {
    private static final double SPACING = 0.5;
    private static final int MAX_PARTICLES = 24000;
    private final JavaPlugin plugin;
    private final SelectionService selections;
    private BukkitTask task;

    public SelectionVisualizer(JavaPlugin plugin, SelectionService selections) {
        this.plugin = plugin;
        this.selections = selections;
    }

    public void start() {
        if (task != null) return;
        long interval = Math.max(5L, plugin.getConfig().getLong("selection-particle-refresh-ticks", 10L));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::render, 1L, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void render() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.hasPermission("templates.admin")) continue;
            Set<Edge> drawn = new HashSet<>();
            int[] count = {0};
            for (Block block : selections.blocks(player)) {
                if (count[0] >= MAX_PARTICLES) break;
                if (!block.getWorld().equals(player.getWorld())) continue;
                int x = block.getX(), y = block.getY(), z = block.getZ();
                edge(player, block.getWorld(), x,y,z,x+1,y,z,count,drawn);
                edge(player, block.getWorld(), x,y,z+1,x+1,y,z+1,count,drawn);
                edge(player, block.getWorld(), x,y+1,z,x+1,y+1,z,count,drawn);
                edge(player, block.getWorld(), x,y+1,z+1,x+1,y+1,z+1,count,drawn);
                edge(player, block.getWorld(), x,y,z,x,y+1,z,count,drawn);
                edge(player, block.getWorld(), x+1,y,z,x+1,y+1,z,count,drawn);
                edge(player, block.getWorld(), x,y,z+1,x,y+1,z+1,count,drawn);
                edge(player, block.getWorld(), x+1,y,z+1,x+1,y+1,z+1,count,drawn);
                edge(player, block.getWorld(), x,y,z,x,y,z+1,count,drawn);
                edge(player, block.getWorld(), x+1,y,z,x+1,y,z+1,count,drawn);
                edge(player, block.getWorld(), x,y+1,z,x,y+1,z+1,count,drawn);
                edge(player, block.getWorld(), x+1,y+1,z,x+1,y+1,z+1,count,drawn);
            }
        }
    }

    private record Edge(int x0,int y0,int z0,int x1,int y1,int z1) {}

    private void edge(Player player, World world, int x0,int y0,int z0,int x1,int y1,int z1,
                      int[] count, Set<Edge> drawn) {
        Edge forward = new Edge(x0,y0,z0,x1,y1,z1);
        Edge reverse = new Edge(x1,y1,z1,x0,y0,z0);
        if (drawn.contains(forward) || drawn.contains(reverse)) return;
        drawn.add(forward);
        double dx=x1-x0, dy=y1-y0, dz=z1-z0;
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        int steps=Math.max(1,Math.min(400,(int)Math.ceil(length/SPACING)));
        for (int i=0;i<=steps && count[0]<MAX_PARTICLES;i++) {
            double t=(double)i/steps;
            Location point=new Location(world,x0+dx*t,y0+dy*t,z0+dz*t);
            player.spawnParticle(Particle.END_ROD,point,1,0,0,0,0);
            count[0]++;
        }
    }
}
