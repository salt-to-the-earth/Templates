package dev.templates.workflow;

import dev.templates.model.TemplateBlock;
import dev.templates.model.TemplateData;
import dev.templates.selection.SelectionService;
import dev.templates.selection.WandService;
import dev.templates.storage.TemplateRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class TemplateWorkflowService implements Listener {
    private static final int MAX_TEMPLATE_BLOCKS = 32768;
    private static final int MAX_PREVIEW_PARTICLES = 3500;
    private final JavaPlugin plugin;
    private final SelectionService selections;
    private final WandService wands;
    private final TemplateRepository repository;
    private final NamespacedKey bookType;
    private final NamespacedKey bookId;
    private final Map<UUID, String> naming = new HashMap<>();
    private final Map<UUID, String> pendingNames = new HashMap<>();
    private final Set<UUID> awaitingAnchor = new HashSet<>();
    private final Map<UUID, PlacementSession> placements = new HashMap<>();
    private BukkitTask visualTask;
    private final File transactionsFile;

    private record OriginalBlock(int x, int y, int z, String material, String data,
                                 String inventory, String[] signLines) {}
    private static final class PlacementSession {
        TemplateData template;
        Location target;
        int rotation;
        List<OriginalBlock> originals = new ArrayList<>();
        Map<String, String> stagedData = new HashMap<>();
        long lastCenterAt;
        boolean staged;
        PlacementSession(TemplateData template) { this.template = template; }
    }

    public TemplateWorkflowService(JavaPlugin plugin, SelectionService selections, WandService wands,
                                   TemplateRepository repository) {
        this.plugin = plugin;
        this.selections = selections;
        this.wands = wands;
        this.repository = repository;
        this.bookType = new NamespacedKey(plugin, "book_type");
        this.bookId = new NamespacedKey(plugin, "book_id");
        this.transactionsFile = new File(plugin.getDataFolder(), "transactions.yml");
        recoverTransactions();
    }

    public void start() {
        if (visualTask != null) return;
        visualTask = Bukkit.getScheduler().runTaskTimer(plugin, this::renderPreviews, 10L, 10L);
    }

    public void stop() {
        if (visualTask != null) visualTask.cancel();
        for (UUID id : new ArrayList<>(placements.keySet())) {
            Player player = Bukkit.getPlayer(id);
            restoreAndClose(id, player, "Plugin stopping; temporary placement was rolled back.");
        }
    }

    public void giveMasterBook(Player player) {
        List<TemplateData> all = repository.all();
        List<Component> pages = new ArrayList<>();
        int pagesCount = Math.max(1, (all.size() + 7) / 8);
        for (int page = 0; page < pagesCount; page++) {
            Component text = Component.text("TEMPLATE LIBRARY\n\n", NamedTextColor.DARK_AQUA)
                    .append(link("＋ Create from current selection", "/templates create"))
                    .append(Component.text("\n\n", NamedTextColor.BLACK));
            int start = page * 8;
            int end = Math.min(all.size(), start + 8);
            if (all.isEmpty()) text = text.append(Component.text(
                    "No templates saved yet. Select blocks with the wand, then choose Create.", NamedTextColor.GRAY));
            for (int i = start; i < end; i++) {
                TemplateData data = all.get(i);
                text = text.append(Component.text((i + 1) + ". ", NamedTextColor.GRAY))
                        .append(link(data.name(), "/templates open " + data.id()))
                        .append(Component.text("  (" + data.blocks().size() + " blocks)\n", NamedTextColor.DARK_GRAY));
            }
            text = text.append(Component.text("\nPage " + (page + 1) + "/" + pagesCount
                    + "  •  " + all.size() + " saved templates", NamedTextColor.GRAY));
            pages.add(text);
        }
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        book.editMeta(meta -> {
            BookMeta bm = (BookMeta) meta;
            bm.title(Component.text("Template Library"));
            bm.author(Component.text("Templates"));
            bm.pages(pages);
            bm.getPersistentDataContainer().set(bookType, PersistentDataType.STRING, "master");
        });
        giveAndOpen(player, book);
    }

    public void giveTemplateBook(Player player, TemplateData data) {
        List<Component> pages = new ArrayList<>();
        pages.add(Component.text("TEMPLATE\n" + data.name() + "\n\n", NamedTextColor.DARK_AQUA)
                .append(Component.text("Blocks: " + data.blocks().size() + "\n", NamedTextColor.BLACK))
                .append(Component.text("Saved: " + new Date(data.createdAt()) + "\n\n", NamedTextColor.GRAY))
                .append(link("▶ Place this template", "/templates place " + data.id()))
                .append(Component.text("\n\n", NamedTextColor.BLACK))
                .append(link("Rename template", "/templates rename " + data.id()))
                .append(Component.text("\n\n", NamedTextColor.BLACK))
                .append(link("Delete template", "/templates delete " + data.id()))
                .append(Component.text("\n\n", NamedTextColor.BLACK))
                .append(link("Return to Template Library", "/templates book")));
        int pageSize = 18;
        for (int start = 0; start < data.blocks().size(); start += pageSize) {
            Component detail = Component.text("TEMPLATE CONTENTS\n\n", NamedTextColor.DARK_AQUA);
            int end = Math.min(data.blocks().size(), start + pageSize);
            for (int i = start; i < end; i++) {
                TemplateBlock b = data.blocks().get(i);
                detail = detail.append(Component.text((i + 1) + ". " + b.material()
                        + "  [" + b.x() + ", " + b.y() + ", " + b.z() + "]\n", NamedTextColor.BLACK));
            }
            detail = detail.append(Component.text("\nPage " + (pages.size() + 1), NamedTextColor.GRAY));
            pages.add(detail);
        }
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        book.editMeta(meta -> {
            BookMeta bm = (BookMeta) meta;
            bm.title(Component.text(data.name().length() > 32 ? data.name().substring(0, 32) : data.name()));
            bm.author(Component.text("Templates"));
            bm.pages(pages);
            bm.getPersistentDataContainer().set(bookType, PersistentDataType.STRING, "template");
            bm.getPersistentDataContainer().set(bookId, PersistentDataType.STRING, data.id());
        });
        giveAndOpen(player, book);
    }

    private Component link(String label, String command) {
        return Component.text("▶ " + label, NamedTextColor.BLUE)
                .clickEvent(ClickEvent.runCommand(command));
    }

    private void giveAndOpen(Player player, ItemStack book) {
        player.getInventory().addItem(book).values().forEach(item ->
                player.getWorld().dropItemNaturally(player.getLocation(), item));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == Material.WRITTEN_BOOK
                        && item.getItemMeta().getPersistentDataContainer().has(bookType, PersistentDataType.STRING)
                        && item.getItemMeta().equals(book.getItemMeta())) {
                    player.openBook(item);
                    break;
                }
            }
        });
    }

    public void beginCreate(Player player) {
        if (selections.size(player) < 1) {
            player.sendMessage(Component.text("Select at least one block before creating a template.", NamedTextColor.RED));
            return;
        }
        naming.put(player.getUniqueId(), "create");
        player.closeInventory();
        player.sendMessage(Component.text("[Templates] Enter a name for this template in chat, or type CANCEL.", NamedTextColor.GOLD));
    }

    public void beginRename(Player player, String id) {
        if (repository.get(id) == null) {
            player.sendMessage(Component.text("That template could not be found.", NamedTextColor.RED));
            return;
        }
        naming.put(player.getUniqueId(), "rename:" + id);
        player.closeInventory();
        player.sendMessage(Component.text("[Templates] Enter the new template name in chat, or type CANCEL.", NamedTextColor.GOLD));
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        String mode = naming.get(playerId);
        if (mode == null) return;
        event.setCancelled(true);
        String value = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;
            if (value.equalsIgnoreCase("cancel")) {
                naming.remove(playerId);
                player.sendMessage(Component.text("Template operation cancelled.", NamedTextColor.YELLOW));
                return;
            }
            if (value.isBlank() || value.length() > 32 || value.matches(".*[\\\\/].*")) {
                player.sendMessage(Component.text("Use a name from 1 to 32 characters; slashes are not allowed. Try again or CANCEL.", NamedTextColor.RED));
                return;
            }
            if (mode.equals("create")) {
                if (repository.existsName(value)) {
                    player.sendMessage(Component.text("A template with that name already exists. Choose another name.", NamedTextColor.RED));
                    return;
                }
                pendingNames.put(playerId, value);
                naming.remove(playerId);
                player.sendMessage(Component.text("Name reserved: " + value, NamedTextColor.GREEN));
                player.sendMessage(Component.text("Open /templates book and click Create confirmation, or run /templates confirm.", NamedTextColor.GOLD));
                giveConfirmBook(player, value);
            } else if (mode.startsWith("rename:")) {
                String id = mode.substring(7);
                TemplateData old = repository.get(id);
                if (old == null) { naming.remove(playerId); return; }
                if (repository.all().stream().anyMatch(t -> !t.id().equals(id) && t.name().equalsIgnoreCase(value))) {
                    player.sendMessage(Component.text("Another template already uses that name.", NamedTextColor.RED));
                    return;
                }
                try {
                    repository.save(new TemplateData(old.id(), value, old.sourceWorld(), old.anchorX(),
                            old.anchorY(), old.anchorZ(), old.frontYaw(), old.createdAt(), old.blocks()));
                    naming.remove(playerId);
                    giveTemplateBook(player, repository.get(id));
                } catch (IOException ex) {
                    player.sendMessage(Component.text("Could not rename template: " + ex.getMessage(), NamedTextColor.RED));
                }
            }
        });
    }

    private void giveConfirmBook(Player player, String name) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        book.editMeta(meta -> {
            BookMeta bm = (BookMeta) meta;
            bm.title(Component.text("Save " + name));
            bm.author(Component.text("Templates"));
            bm.pages(Component.text("SAVE TEMPLATE\n\nName: " + name + "\nSelected blocks: "
                    + selections.size(player) + "\n\n", NamedTextColor.DARK_AQUA)
                    .append(link("Confirm and set front anchor", "/templates confirm"))
                    .append(Component.text("\n\nThe next wand strike on the ground establishes the front and anchor.", NamedTextColor.GRAY)));
            bm.getPersistentDataContainer().set(bookType, PersistentDataType.STRING, "confirm");
        });
        giveAndOpen(player, book);
    }

    public void confirmCreate(Player player) {
        if (!pendingNames.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text("There is no template waiting for confirmation.", NamedTextColor.RED));
            return;
        }
        if (selections.size(player) < 1) {
            pendingNames.remove(player.getUniqueId());
            player.sendMessage(Component.text("Your selection is empty. Start again after selecting blocks.", NamedTextColor.RED));
            return;
        }
        awaitingAnchor.add(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(Component.text("Confirmed. Walk to the intended FRONT of the structure and strike the ground with your Templates wand.", NamedTextColor.GOLD));
    }

    @EventHandler
    public void onWandInteract(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !wands.isWand(event.getItem())) return;
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("templates.admin")) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        PlacementSession placement = placements.get(player.getUniqueId());
        if (placement != null) {
            handlePlacementClick(player, placement, clicked);
            return;
        }
        if (awaitingAnchor.remove(player.getUniqueId())) {
            String name = pendingNames.remove(player.getUniqueId());
            if (name == null) return;
            Block anchor = clicked.getRelative(0, 1, 0);
            try {
                TemplateData data = repository.capture(name, player, selections.blocks(player), anchor);
                if (data.blocks().isEmpty()) throw new IllegalStateException("Selection is empty.");
                repository.save(data);
                selections.clear(player);
                player.sendMessage(Component.text("Template '" + name + "' saved with " + data.blocks().size()
                        + " blocks. Its front/anchor is set.", NamedTextColor.GREEN));
                giveTemplateBook(player, data);
            } catch (Exception ex) {
                player.sendMessage(Component.text("Template could not be saved: " + ex.getMessage(), NamedTextColor.RED));
            }
            return;
        }
        if (action == Action.RIGHT_CLICK_BLOCK) {
            selections.remove(player, clicked);
            player.sendMessage(Component.text("Removed block. Selection: " + selections.size(player), NamedTextColor.YELLOW));
            return;
        }
        if (player.isSneaking()) {
            var first = selections.lastPoint(player);
            if (first == null || first.getWorld() == null || !first.getWorld().equals(clicked.getWorld())) {
                selections.add(player, clicked);
                player.sendMessage(Component.text("First cuboid corner selected. Shift-click the opposite corner.", NamedTextColor.YELLOW));
            } else {
                int before = selections.size(player);
                selections.addCuboid(player, first, clicked.getLocation());
                player.sendMessage(Component.text("Cuboid selected. " + selections.size(player)
                        + " blocks total (+" + (selections.size(player) - before) + ").", NamedTextColor.GREEN));
            }
        } else {
            selections.add(player, clicked);
            player.sendMessage(Component.text("Block selected. Selection: " + selections.size(player), NamedTextColor.GREEN));
        }
        player.spawnParticle(Particle.END_ROD, clicked.getLocation().add(.5, .65, .5), 8, .18, .18, .18, .01);
    }

    public void beginPlace(Player player, String id) {
        TemplateData data = repository.get(id);
        if (data == null) {
            player.sendMessage(Component.text("Template not found.", NamedTextColor.RED));
            return;
        }
        if (data.blocks().size() > MAX_TEMPLATE_BLOCKS) {
            player.sendMessage(Component.text("This template exceeds the configured placement safety limit.", NamedTextColor.RED));
            return;
        }
        placements.put(player.getUniqueId(), new PlacementSession(data));
        player.closeInventory();
        player.sendMessage(Component.text("Placement preview armed for '" + data.name()
                + "'. Strike the ground with your wand to choose its initial anchor.", NamedTextColor.GOLD));
    }

    private void handlePlacementClick(Player player, PlacementSession session, Block clicked) {
        if (session.target == null) {
            session.target = clicked.getRelative(0, 1, 0).getLocation();
            session.rotation = 0;
            player.sendMessage(Component.text("Preview positioned. Use the particle control panel on the ground: blue=X, red=Z, green=Y, yellow=rotate, white=center.", NamedTextColor.GREEN));
            return;
        }
        int px = clicked.getX() - session.target.getBlockX();
        int pz = clicked.getZ() - session.target.getBlockZ();
        if (clicked.getY() != session.target.getBlockY() - 1 || Math.abs(px) > 1 || Math.abs(pz) > 1) {
            player.sendMessage(Component.text("Use the ground control panel immediately around the preview anchor.", NamedTextColor.GRAY));
            return;
        }
        if (px == 0 && pz == 0) {
            long now = System.currentTimeMillis();
            if (session.staged && now - session.lastCenterAt < 1200) {
                placements.remove(player.getUniqueId());
                forgetTransaction(player.getUniqueId());
                player.sendMessage(Component.text("Placement finalized. The staged structure is now permanent.", NamedTextColor.GREEN));
                giveTemplateBook(player, session.template);
                return;
            }
            session.lastCenterAt = now;
            applyStage(player, session);
            return;
        }
        if (px == -1 && pz == 0) session.target.add(-1, 0, 0);
        else if (px == 1 && pz == 0) session.target.add(1, 0, 0);
        else if (px == 0 && pz == -1) session.target.add(0, 0, -1);
        else if (px == 0 && pz == 1) session.target.add(0, 0, 1);
        else if (px == -1 && pz == -1) session.target.add(0, -1, 0);
        else if (px == 1 && pz == 1) session.target.add(0, 1, 0);
        else if (px == -1 && pz == 1) session.rotation = (session.rotation + 3) % 4;
        else if (px == 1 && pz == -1) session.rotation = (session.rotation + 1) % 4;
        renderOne(player, session);
    }

    private void applyStage(Player player, PlacementSession session) {
        World world = session.target.getWorld();
        if (world == null) return;
        if (session.staged) restoreOriginals(session, world);
        List<OriginalBlock> originals = new ArrayList<>();
        Map<String, String> staged = new HashMap<>();
        try {
            for (TemplateBlock b : session.template.blocks()) {
                int[] rotated = rotate(b.x(), b.z(), session.rotation);
                int x = session.target.getBlockX() + rotated[0];
                int y = session.target.getBlockY() + b.y();
                int z = session.target.getBlockZ() + rotated[1];
                Block block = world.getBlockAt(x, y, z);
                originals.add(captureOriginal(block));
            }
            session.originals = originals;
            session.stagedData.clear();
            for (TemplateBlock b : session.template.blocks()) {
                int[] rotated = rotate(b.x(), b.z(), session.rotation);
                Block block = world.getBlockAt(session.target.getBlockX() + rotated[0],
                        session.target.getBlockY() + b.y(), session.target.getBlockZ() + rotated[1]);
                placeBlock(block, b, session.rotation);
                session.stagedData.put(key(block), block.getBlockData().getAsString());
            }
            session.staged = true;
            persistTransactions();
            player.sendMessage(Component.text("Template staged. Adjust the preview and click the white center again to apply a new position. Double-click the center to finalize, or use /templates cancel.", NamedTextColor.GREEN));
        } catch (Exception ex) {
            for (OriginalBlock original : originals) restoreOriginal(world, original);
            session.staged = false;
            session.originals.clear();
            persistTransactions();
            player.sendMessage(Component.text("Apply failed; the original blocks were restored: " + ex.getMessage(), NamedTextColor.RED));
        }
    }

    private OriginalBlock captureOriginal(Block block) {
        String inventory = "";
        String[] lines = null;
        var state = block.getState();
        if (state instanceof Container container && block.getType() != Material.ENDER_CHEST) {
            inventory = TemplateRepository.encodeItems(container.getInventory().getContents());
        }
        if (state instanceof Sign sign) {
            lines = new String[4];
            for (int i = 0; i < 4; i++) lines[i] = sign.getLine(i);
        }
        return new OriginalBlock(block.getX(), block.getY(), block.getZ(), block.getType().name(),
                block.getBlockData().getAsString(), inventory, lines);
    }

    private void placeBlock(Block block, TemplateBlock data, int rotation) throws IOException {
        Material material = Material.matchMaterial(data.material());
        if (material == null || !material.isBlock()) throw new IOException("Unknown block material " + data.material());
        block.setType(material, false);
        BlockData blockData = Bukkit.createBlockData(data.blockData());
        for (int i = 0; i < rotation; i++) blockData.rotate(StructureRotation.CLOCKWISE_90);
        block.setBlockData(blockData, false);
        var state = block.getState();
        if (!data.inventoryData().isBlank() && state instanceof Container container
                && material != Material.ENDER_CHEST) {
            container.getInventory().setContents(TemplateRepository.decodeItems(data.inventoryData()));
            container.update(true, false);
        }
        if (data.signLines() != null && state instanceof Sign sign) {
            for (int i = 0; i < Math.min(4, data.signLines().length); i++) sign.setLine(i, data.signLines()[i]);
            sign.update(true, false);
        }
    }

    private int[] rotate(int x, int z, int rotation) {
        return switch (rotation) {
            case 1 -> new int[]{-z, x};
            case 2 -> new int[]{-x, -z};
            case 3 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    private void restoreOriginals(PlacementSession session, World world) {
        for (OriginalBlock original : session.originals) {
            String key = original.x() + ":" + original.y() + ":" + original.z();
            String expected = session.stagedData.get(key);
            Block block = world.getBlockAt(original.x(), original.y(), original.z());
            if (expected != null && !block.getBlockData().getAsString().equals(expected)) {
                plugin.getLogger().warning("Skipped rollback at " + key + " because another change was detected.");
                continue;
            }
            restoreOriginal(world, original);
        }
        session.staged = false;
        session.originals.clear();
        session.stagedData.clear();
        persistTransactions();
    }

    private void restoreOriginal(World world, OriginalBlock original) {
        Block block = world.getBlockAt(original.x(), original.y(), original.z());
        Material material = Material.matchMaterial(original.material());
        if (material == null) return;
        block.setType(material, false);
        try { block.setBlockData(Bukkit.createBlockData(original.data()), false); }
        catch (IllegalArgumentException ignored) { }
        var state = block.getState();
        try {
            if (!original.inventory().isBlank() && state instanceof Container container) {
                container.getInventory().setContents(TemplateRepository.decodeItems(original.inventory()));
                container.update(true, false);
            }
            if (original.signLines() != null && state instanceof Sign sign) {
                for (int i = 0; i < Math.min(4, original.signLines().length); i++) sign.setLine(i, original.signLines()[i]);
                sign.update(true, false);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not fully restore block data at " + original.x() + "," + original.y() + "," + original.z() + ": " + ex.getMessage());
        }
    }

    private String key(Block block) { return block.getX() + ":" + block.getY() + ":" + block.getZ(); }

    public void finalizePlacement(Player player) {
        PlacementSession session = placements.remove(player.getUniqueId());
        if (session == null || !session.staged) {
            if (session != null) placements.put(player.getUniqueId(), session);
            player.sendMessage(Component.text("There is no staged placement to finalize.", NamedTextColor.RED));
            return;
        }
        forgetTransaction(player.getUniqueId());
        player.sendMessage(Component.text("Placement finalized. The structure is permanent.", NamedTextColor.GREEN));
        giveTemplateBook(player, session.template);
    }

    public void cancelPlacement(Player player) {
        if (!placements.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text("You do not have an active placement.", NamedTextColor.YELLOW));
            return;
        }
        restoreAndClose(player.getUniqueId(), player, "Placement cancelled; the original world blocks were restored where unchanged.");
    }

    private void restoreAndClose(UUID id, Player player, String message) {
        PlacementSession session = placements.remove(id);
        if (session != null && session.staged && session.target != null && session.target.getWorld() != null) {
            restoreOriginals(session, session.target.getWorld());
        }
        forgetTransaction(id);
        if (player != null) player.sendMessage(Component.text(message, NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (placements.containsKey(id)) restoreAndClose(id, event.getPlayer(), "Disconnected; temporary placement rolled back.");
        naming.remove(id); pendingNames.remove(id); awaitingAnchor.remove(id);
    }

    private void renderPreviews() {
        for (Map.Entry<UUID, PlacementSession> entry : placements.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) renderOne(player, entry.getValue());
        }
    }

    private void renderOne(Player player, PlacementSession session) {
        if (session.target == null || session.target.getWorld() == null) return;
        World world = session.target.getWorld();
        int emitted = 0;
        for (TemplateBlock b : session.template.blocks()) {
            if (emitted >= MAX_PREVIEW_PARTICLES) break;
            int[] r = rotate(b.x(), b.z(), session.rotation);
            Location point = session.target.clone().add(r[0] + .5, b.y() + .5, r[1] + .5);
            player.spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
            emitted++;
        }
        panel(player, session);
    }

    private void panel(Player player, PlacementSession session) {
        World world = session.target.getWorld();
        int x = session.target.getBlockX(), y = session.target.getBlockY() - 1, z = session.target.getBlockZ();
        dust(player, world, x - 1, y, z, Color.BLUE);
        dust(player, world, x + 1, y, z, Color.BLUE);
        dust(player, world, x, y, z - 1, Color.RED);
        dust(player, world, x, y, z + 1, Color.RED);
        dust(player, world, x - 1, y, z - 1, Color.LIME);
        dust(player, world, x + 1, y, z + 1, Color.LIME);
        dust(player, world, x - 1, y, z + 1, Color.YELLOW);
        dust(player, world, x + 1, y, z - 1, Color.YELLOW);
        dust(player, world, x, y, z, Color.WHITE);
    }

    private void dust(Player player, World world, int x, int y, int z, Color color) {
        player.spawnParticle(Particle.DUST, new Location(world, x + .5, y + 1.05, z + .5),
                2, .12, .02, .12, 0, new Particle.DustOptions(color, 1.25f));
    }

    private void persistTransactions() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlacementSession> entry : placements.entrySet()) {
            PlacementSession s = entry.getValue();
            if (!s.staged || s.target == null || s.target.getWorld() == null) continue;
            String base = entry.getKey().toString();
            yaml.set(base + ".world", s.target.getWorld().getUID().toString());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (OriginalBlock b : s.originals) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("x", b.x()); row.put("y", b.y()); row.put("z", b.z());
                row.put("material", b.material()); row.put("data", b.data());
                row.put("inventory", b.inventory());
                row.put("sign", b.signLines() == null ? List.of() : Arrays.asList(b.signLines()));
                rows.add(row);
            }
            yaml.set(base + ".originals", rows);
        }
        try { yaml.save(transactionsFile); }
        catch (IOException ex) { plugin.getLogger().severe("Could not persist placement rollback data: " + ex.getMessage()); }
    }

    private void forgetTransaction(UUID id) {
        if (!transactionsFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(transactionsFile);
        yaml.set(id.toString(), null);
        try { yaml.save(transactionsFile); }
        catch (IOException ex) { plugin.getLogger().warning("Could not clear rollback record: " + ex.getMessage()); }
    }

    private void recoverTransactions() {
        if (!transactionsFile.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(transactionsFile);
        for (String id : yaml.getKeys(false)) {
            String worldText = yaml.getString(id + ".world", "");
            World world;
            try { world = Bukkit.getWorld(UUID.fromString(worldText)); }
            catch (IllegalArgumentException ex) { continue; }
            if (world == null) continue;
            for (Map<?, ?> row : yaml.getMapList(id + ".originals")) {
                List<?> rawSign = (List<?>) row.getOrDefault("sign", List.of());
                String[] sign = rawSign.isEmpty() ? null : rawSign.stream().map(String::valueOf).toArray(String[]::new);
                OriginalBlock original = new OriginalBlock(num(row.get("x")), num(row.get("y")), num(row.get("z")),
                        String.valueOf(row.get("material")), String.valueOf(row.get("data")),
                        String.valueOf(row.getOrDefault("inventory", "")), sign);
                restoreOriginal(world, original);
            }
            yaml.set(id, null);
        }
        try { yaml.save(transactionsFile); }
        catch (IOException ex) { plugin.getLogger().warning("Could not save transaction recovery results: " + ex.getMessage()); }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        // Recovery on initial enable handles worlds already loaded; this hook retries
        // records for worlds that were not available at that moment.
        recoverTransactions();
    }

    private int num(Object value) { return value instanceof Number n ? n.intValue() : 0; }
}
