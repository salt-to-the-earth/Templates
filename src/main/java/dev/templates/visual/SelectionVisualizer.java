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
            var blocks = selections.blocks(player).stream()
                    .filter(block -> block.getWorld().equals(player.getWorld())).toList();
            if (blocks.isEmpty()) continue;
            int minX = blocks.stream().mapToInt(Block::getX).min().orElse(0);
            int maxX = blocks.stream().mapToInt(Block::getX).max().orElse(0) + 1;
            int minY = blocks.stream().mapToInt(Block::getY).min().orElse(0);
            int maxY = blocks.stream().mapToInt(Block::getY).max().orElse(0) + 1;
            int minZ = blocks.stream().mapToInt(Block::getZ).min().orElse(0);
            int maxZ = blocks.stream().mapToInt(Block::getZ).max().orElse(0) + 1;
            World world = player.getWorld();
            int[] count = {0};
            Set<Edge> drawn = new HashSet<>();
            edge(player, world, minX,minY,minZ,maxX,minY,minZ,count,drawn);
            edge(player, world, minX,maxY,minZ,maxX,maxY,minZ,count,drawn);
            edge(player, world, minX,minY,maxZ,maxX,minY,maxZ,count,drawn);
            edge(player, world, minX,maxY,maxZ,maxX,maxY,maxZ,count,drawn);
            edge(player, world, minX,minY,minZ,minX,maxY,minZ,count,drawn);
            edge(player, world, maxX,minY,minZ,maxX,maxY,minZ,count,drawn);
            edge(player, world, minX,minY,maxZ,minX,maxY,maxZ,count,drawn);
            edge(player, world, maxX,minY,maxZ,maxX,maxY,maxZ,count,drawn);
            edge(player, world, minX,minY,minZ,minX,minY,maxZ,count,drawn);
            edge(player, world, maxX,minY,minZ,maxX,minY,maxZ,count,drawn);
            edge(player, world, minX,maxY,minZ,minX,maxY,maxZ,count,drawn);
            edge(player, world, maxX,maxY,minZ,maxX,maxY,maxZ,count,drawn);
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
