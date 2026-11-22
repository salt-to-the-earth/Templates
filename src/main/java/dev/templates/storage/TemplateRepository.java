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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class TemplateRepository {
    private final JavaPlugin plugin;
    private final File folder;
    private final File legacyFolder;

    public TemplateRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder: " + dataFolder);
        }
        this.folder = new File(dataFolder, "saves");
        this.legacyFolder = new File(dataFolder, "templates");
        ensureFolder();
        migrateLegacyFolder();
    }

    private void ensureFolder() {
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IllegalStateException("Could not create template saves folder: " + folder);
        }
    }

    private void migrateLegacyFolder() {
        if (!legacyFolder.isDirectory()) return;
        File[] oldFiles = legacyFolder.listFiles();
        if (oldFiles == null) return;
        for (File oldFile : oldFiles) {
            if (!oldFile.isFile() || !oldFile.getName().toLowerCase(Locale.ROOT).endsWith(".yml")) continue;
            File destination = new File(folder, oldFile.getName());
            if (destination.exists()) {
                plugin.getLogger().warning("Keeping legacy template file because a saves file already exists: " + oldFile.getName());
                continue;
            }
            try {
                Files.move(oldFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Migrated template save: " + oldFile.getName());
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not migrate " + oldFile.getName() + " into saves/: " + ex.getMessage());
            }
        }
        File[] remaining = legacyFolder.listFiles();
        if (remaining != null && remaining.length == 0 && !legacyFolder.delete()) {
            plugin.getLogger().warning("Could not remove empty legacy templates folder.");
        }
    }

    public List<TemplateData> all() {
        ensureFolder();
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return List.of();
        List<TemplateData> result = new ArrayList<>();
        for (File file : files) {
            try {
                TemplateData data = read(file);
                if (data != null) result.add(data);
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not load template save " + file.getName() + ": " + ex.getMessage());
            }
        }
        result.sort(Comparator.comparing(TemplateData::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public TemplateData get(String id) {
        File file = fileForId(id);
        if (file == null || !file.isFile()) return null;
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
        ensureFolder();
        File oldFile = fileForId(data.id());
        File target = new File(folder, safeFileName(data.name()) + ".yml");
        if (target.exists() && (oldFile == null || !target.equals(oldFile))) {
            TemplateData occupying = read(target);
            if (occupying == null || !occupying.id().equals(data.id())) {
                throw new IOException("A save file with that name already exists. Choose a different template name.");
            }
        }

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

        File temporary = new File(folder, target.getName() + ".tmp");
        yaml.save(temporary);
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                moveFailure.addSuppressed(atomicFailure);
                throw moveFailure;
            }
        }
        if (oldFile != null && !oldFile.equals(target) && oldFile.exists() && !oldFile.delete()) {
            plugin.getLogger().warning("Saved renamed template but could not remove its previous file: " + oldFile.getName());
        }
    }

    public void delete(String id) {
        File file = fileForId(id);
        if (file != null && file.exists() && !file.delete()) {
            plugin.getLogger().warning("Could not delete template save " + file);
        }
    }

    private File fileForId(String id) {
        ensureFolder();
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return null;
        for (File candidate : files) {
            try {
                if (id.equals(YamlConfiguration.loadConfiguration(candidate).getString("id"))) return candidate;
            } catch (Exception ignored) {
                // The full reader reports malformed files when listing them.
            }
        }
        return null;
    }

    private String safeFileName(String name) throws IOException {
        String safe = name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .trim().replaceAll("[. ]+$", "");
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            throw new IOException("The template name cannot be used as a save filename.");
        }
        return safe;
    }

    public TemplateData capture(String name, Player player, Collection<Block> selected, Block anchor) {
        List<TemplateBlock> blocks = new ArrayList<>();
        for (Block block : selected) {
            if (!block.getWorld().equals(player.getWorld())) {
                throw new IllegalArgumentException("All selected blocks must be in the same world as the player.");
            }
            int x = block.getX() - anchor.getX();
            int y = block.getY() - anchor.getY();
            int z = block.getZ() - anchor.getZ();
            String inventory = "";
            String[] lines = null;
            var state = block.getState();
            if (state instanceof Container container && block.getType() != Material.ENDER_CHEST) {
                inventory = encodeItems(container.getSnapshotInventory().getContents());
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
        return new TemplateData(yaml.getString("id", file.getName().replaceFirst("(?i)\\.yml$", "")),
                yaml.getString("name", "Unnamed"), UUID.fromString(yaml.getString("source-world")),
                yaml.getInt("anchor.x"), yaml.getInt("anchor.y"), yaml.getInt("anchor.z"),
                (float) yaml.getDouble("front-yaw"), yaml.getLong("created-at"), blocks);
    }

    private int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
}
