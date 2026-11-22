package dev.templates.storage;

import dev.templates.model.TemplateBlock;
import dev.templates.model.TemplateData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.*;

public final class TemplateRepository {
    private final JavaPlugin plugin;
    private final File folder;

    public TemplateRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "templates");
        if (!folder.exists()) folder.mkdirs();
    }

    public List<TemplateData> all() {
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return List.of();
        List<TemplateData> result = new ArrayList<>();
        for (File file : files) {
            try {
                TemplateData data = read(file);
                if (data != null) result.add(data);
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not load template " + file.getName() + ": " + ex.getMessage());
            }
        }
        result.sort(Comparator.comparing(TemplateData::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public TemplateData get(String id) {
        File file = file(id);
        if (!file.isFile()) return null;
        try { return read(file); }
        catch (Exception ex) {
            plugin.getLogger().warning("Could not read template " + id + ": " + ex.getMessage());
            return null;
        }
    }

    public boolean existsName(String name) {
        return all().stream().anyMatch(t -> t.name().equalsIgnoreCase(name));
    }

    public void save(TemplateData data) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", data.id());
        yaml.set("name", data.name());
        yaml.set("source-world", data.sourceWorld().toString());
        yaml.set("anchor.x", data.anchorX());
        yaml.set("anchor.y", data.anchorY());
        yaml.set("anchor.z", data.anchorZ());
        yaml.set("front-yaw", data.frontYaw());
        yaml.set("created-at", data.createdAt());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (TemplateBlock block : data.blocks()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("x", block.x()); row.put("y", block.y()); row.put("z", block.z());
            row.put("material", block.material()); row.put("block-data", block.blockData());
            row.put("inventory", block.inventoryData());
            row.put("sign-lines", block.signLines() == null ? List.of() : Arrays.asList(block.signLines()));
            entries.add(row);
        }
        yaml.set("blocks", entries);
        yaml.save(file(data.id()));
    }

    public void delete(String id) {
        File file = file(id);
        if (file.exists() && !file.delete()) plugin.getLogger().warning("Could not delete template file " + file);
    }

    public TemplateData capture(String name, Player player, Collection<Block> selected, Block anchor) {
        List<TemplateBlock> blocks = new ArrayList<>();
        for (Block block : selected) {
            int x = block.getX() - anchor.getX();
            int y = block.getY() - anchor.getY();
            int z = block.getZ() - anchor.getZ();
            String inventory = "";
            String[] lines = null;
            var state = block.getState();
            if (state instanceof Container container && block.getType() != Material.ENDER_CHEST) {
                inventory = encodeItems(container.getInventory().getContents());
            }
            if (state instanceof Sign sign) {
                lines = new String[4];
                for (int i = 0; i < 4; i++) lines[i] = sign.getLine(i);
            }
            blocks.add(new TemplateBlock(x, y, z, block.getType().name(),
                    block.getBlockData().getAsString(), inventory, lines));
        }
        blocks.sort(Comparator.comparingInt(TemplateBlock::y)
                .thenComparingInt(TemplateBlock::x).thenComparingInt(TemplateBlock::z));
        return new TemplateData(UUID.randomUUID().toString(), name, player.getWorld().getUID(),
                anchor.getX(), anchor.getY(), anchor.getZ(), player.getLocation().getYaw(),
                System.currentTimeMillis(), blocks);
    }

    public static String encodeItems(ItemStack[] items) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
                out.writeInt(items.length);
                for (ItemStack item : items) out.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Could not serialize container inventory", ex);
        }
    }

    public static ItemStack[] decodeItems(String encoded) throws IOException {
        if (encoded == null || encoded.isBlank()) return new ItemStack[0];
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            int size = in.readInt();
            if (size < 0 || size > 4096) throw new IOException("Invalid inventory size");
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < size; i++) items[i] = (ItemStack) in.readObject();
            return items;
        } catch (ClassNotFoundException ex) {
            throw new IOException("Unknown item data class", ex);
        }
    }

    private TemplateData read(File file) throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<TemplateBlock> blocks = new ArrayList<>();
        for (Map<?, ?> row : yaml.getMapList("blocks")) {
            List<?> rawLines = (List<?>) (row.containsKey("sign-lines") ? row.get("sign-lines") : List.of());
            String[] lines = rawLines.isEmpty() ? null : rawLines.stream().map(String::valueOf).toArray(String[]::new);
            blocks.add(new TemplateBlock(number(row.get("x")), number(row.get("y")), number(row.get("z")),
                    String.valueOf(row.get("material")), String.valueOf(row.get("block-data")),
                    String.valueOf(row.containsKey("inventory") ? row.get("inventory") : ""), lines));
        }
        return new TemplateData(yaml.getString("id", file.getName().replace(".yml", "")),
                yaml.getString("name", "Unnamed"), UUID.fromString(yaml.getString("source-world")),
                yaml.getInt("anchor.x"), yaml.getInt("anchor.y"), yaml.getInt("anchor.z"),
                (float) yaml.getDouble("front-yaw"), yaml.getLong("created-at"), blocks);
    }

    private int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private File file(String id) { return new File(folder, id + ".yml"); }
}
