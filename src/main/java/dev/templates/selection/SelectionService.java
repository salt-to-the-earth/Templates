package dev.templates.selection;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player working selection. Blocks are keyed by world and integer coordinates. */
public final class SelectionService {
    private final Map<UUID, Map<String, Block>> selections = new LinkedHashMap<>();
    private final Map<UUID, Location> lastPoints = new LinkedHashMap<>();

    public void add(Player player, Block block) {
        selections.computeIfAbsent(player.getUniqueId(), ignored -> new LinkedHashMap<>())
                .put(key(block), block);
        lastPoints.put(player.getUniqueId(), block.getLocation());
    }

    public void addCuboid(Player player, Location first, Location second) {
        if (first.getWorld() == null || second.getWorld() == null
                || !first.getWorld().getUID().equals(second.getWorld().getUID())) return;
        World world = first.getWorld();
        int minX = Math.min(first.getBlockX(), second.getBlockX());
        int maxX = Math.max(first.getBlockX(), second.getBlockX());
        int minY = Math.min(first.getBlockY(), second.getBlockY());
        int maxY = Math.max(first.getBlockY(), second.getBlockY());
        int minZ = Math.min(first.getBlockZ(), second.getBlockZ());
        int maxZ = Math.max(first.getBlockZ(), second.getBlockZ());
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > 16384) return;
        Map<String, Block> selected = selections.computeIfAbsent(
                player.getUniqueId(), ignored -> new LinkedHashMap<>());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    selected.put(key(block), block);
                }
            }
        }
        lastPoints.put(player.getUniqueId(), second.clone());
    }

    public void remove(Player player, Block block) {
        Map<String, Block> selected = selections.get(player.getUniqueId());
        if (selected != null) selected.remove(key(block));
    }

    public void clear(Player player) {
        selections.remove(player.getUniqueId());
        lastPoints.remove(player.getUniqueId());
    }

    public Location lastPoint(Player player) {
        Location point = lastPoints.get(player.getUniqueId());
        return point == null ? null : point.clone();
    }

    public Collection<Block> blocks(Player player) {
        Map<String, Block> selected = selections.get(player.getUniqueId());
        return selected == null ? java.util.List.of() : java.util.List.copyOf(selected.values());
    }

    public int size(Player player) {
        Map<String, Block> selected = selections.get(player.getUniqueId());
        return selected == null ? 0 : selected.size();
    }

    private String key(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }
}
