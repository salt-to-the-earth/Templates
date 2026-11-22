package dev.templates.workflow;

import dev.templates.model.TemplateBlock;
import dev.templates.model.TemplateData;
import dev.templates.model.CoordinateTransform;
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
    private final Map<UUID, String> naming = new java.util.concurrent.ConcurrentHashMap<>();
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
        Location panelAnchor;
        int rotation;
        List<OriginalBlock> originals = new ArrayList<>();
        Map<String, String> stagedData = new HashMap<>();
        long lastCenterAt;
        boolean staged;
        int rightX, rightZ, forwardX, forwardZ;
        boolean anchorRotation;
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
                .append(link("Cancel active placement", "/templates cancel"))
                .append(Component.text("\n\n", NamedTextColor.BLACK))
                .append(link("Return to Template Library", "/templates book")));
        pages.add(Component.text("PLACEMENT CONTROLS\n\n", NamedTextColor.DARK_AQUA)
                .append(Component.text("The panel faces the direction you were looking when you placed it. Click the block beneath a colored indicator with your wand.\n\n", NamedTextColor.BLACK))
                .append(Component.text("Green top/bottom: move up / down\nYellow left/right: rotate around center\nShift + yellow: rotate around anchor\nBlue diagonal pair: move left / right\nRed diagonal pair: move forward / back\nWhite center: apply; immediate second tap finalizes.\n\n", NamedTextColor.BLACK))
                .append(Component.text("You can also use /templates finalize or /templates cancel.", NamedTextColor.GRAY)));
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

    private void giveSelectionBook(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        book.editMeta(meta -> {
            BookMeta bm = (BookMeta) meta;
            bm.title(Component.text("Current Selection"));
            bm.author(Component.text("Templates"));
            bm.pages(Component.text("TEMPLATE SELECTION\\n\\nSelected blocks: " + selections.size(player) + "\\n\\n", NamedTextColor.DARK_AQUA)
                    .append(link("Create template from selection", "/templates create"))
                    .append(Component.text("\\n\\n", NamedTextColor.BLACK))
                    .append(link("Clear selection", "/templates clear"))
                    .append(Component.text("\\n\\nUse the wand to add blocks, Shift-click the opposite corner for a cuboid, or right-click to remove blocks.", NamedTextColor.GRAY)));
            bm.getPersistentDataContainer().set(bookType, PersistentDataType.STRING, "selection");
        });
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack existing = player.getInventory().getItem(slot);
            if (existing == null || existing.getType() != Material.WRITTEN_BOOK) continue;
            if (!"selection".equals(existing.getItemMeta().getPersistentDataContainer()
                    .get(bookType, PersistentDataType.STRING))) continue;
            player.getInventory().setItem(slot, book);
            player.openBook(book);
            return;
        }
        giveAndOpen(player, book);
    }

    public void beginCreate(Player player) {
        if (selections.size(player) < 1) {
            player.sendMessage(Component.text("Select at least one block before creating a template.", NamedTextColor.RED));
            return;
        }
        if (selections.size(player) > MAX_TEMPLATE_BLOCKS) {
            player.sendMessage(Component.text("This selection exceeds the " + MAX_TEMPLATE_BLOCKS + "-block template limit.", NamedTextColor.RED));
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
            giveSelectionBook(player);
            return;
        }
        if (player.isSneaking()) {
            var first = selections.lastPoint(player);
            if (first == null || first.getWorld() == null || !first.getWorld().equals(clicked.getWorld())) {
                selections.add(player, clicked);
                player.sendMessage(Component.text("First cuboid corner selected. Shift-click the opposite corner.", NamedTextColor.YELLOW));
            } else {
                int before = selections.size(player);
                if (selections.addCuboid(player, first, clicked.getLocation())) {
                    player.sendMessage(Component.text("Cuboid selected. " + selections.size(player)
                            + " blocks total (+" + (selections.size(player) - before) + ").", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("That cuboid exceeds the 16,384-block limit or crosses worlds. The selection was not changed.", NamedTextColor.RED));
                }
            }
        } else {
            selections.add(player, clicked);
            player.sendMessage(Component.text("Block selected. Selection: " + selections.size(player), NamedTextColor.GREEN));
        }
        player.spawnParticle(Particle.END_ROD, clicked.getLocation().add(.5, .65, .5), 8, .18, .18, .18, .01);
    }


    public void openTemplate(Player player, String id) {
        TemplateData data = repository.get(id);
        if (data == null) player.sendMessage(Component.text("Template not found.", NamedTextColor.RED));
        else giveTemplateBook(player, data);
    }

    public void deleteTemplate(Player player, String id) {
        TemplateData data = repository.get(id);
        if (data == null) {
            player.sendMessage(Component.text("Template not found.", NamedTextColor.RED));
            return;
        }
        repository.delete(id);
        player.sendMessage(Component.text("Template '" + data.name() + "' was deleted.", NamedTextColor.GREEN));
        giveMasterBook(player);
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
            session.panelAnchor = clicked.getLocation().clone();
            session.target = clicked.getRelative(0, 1, 0).getLocation();
            session.rotation = CoordinateTransform.rotationBetween(session.template.frontYaw(), player.getLocation().getYaw());
            session.anchorRotation = true;
            int facing = Math.floorMod(Math.round(player.getLocation().getYaw() / 90.0f), 4);
            session.forwardX = switch (facing) { case 1 -> -1; case 3 -> 1; default -> 0; };
            session.forwardZ = switch (facing) { case 0 -> 1; case 2 -> -1; default -> 0; };
            session.rightX = -session.forwardZ;
            session.rightZ = session.forwardX;
            player.sendMessage(Component.text("Preview positioned. Use the particle control panel on the ground: blue=X, red=Z, green=Y, yellow=rotate, white=center.", NamedTextColor.GREEN));
            return;
        }
        int dx = clicked.getX() - session.panelAnchor.getBlockX();
        int dz = clicked.getZ() - session.panelAnchor.getBlockZ();
        int px = dx * session.rightX + dz * session.rightZ;
        int pz = dx * session.forwardX + dz * session.forwardZ;
        if (clicked.getY() != session.panelAnchor.getBlockY() || Math.abs(px) > 1 || Math.abs(pz) > 1) {
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
        if (px == 0 && pz == 1) session.target.add(0, 1, 0);
        else if (px == 0 && pz == -1) session.target.add(0, -1, 0);
        else if (px == -1 && pz == 1) session.target.add(-session.rightX, 0, -session.rightZ);
        else if (px == 1 && pz == -1) session.target.add(session.rightX, 0, session.rightZ);
        else if (px == 1 && pz == 1) session.target.add(session.forwardX, 0, session.forwardZ);
        else if (px == -1 && pz == -1) session.target.add(-session.forwardX, 0, -session.forwardZ);
        else if (Math.abs(px) == 1 && pz == 0) {
            session.anchorRotation = player.isSneaking();
            session.rotation = (session.rotation + (px > 0 ? 1 : 3)) % 4;
            if (wouldOverlapPanel(session)) {
                session.target.add(session.forwardX * 2, 0, session.forwardZ * 2);
                player.sendMessage(Component.text("Moved the rotated template two blocks forward to keep the control panel clear.", NamedTextColor.YELLOW));
            }
        }
        session.lastCenterAt = 0L;
        renderOne(player, session);
    }

    private void applyStage(Player player, PlacementSession session) {
        World world = session.target.getWorld();
        if (world == null) return;
        if (session.staged) {
            if (!canRestore(session, world)) {
                player.sendMessage(Component.text("A staged block was changed by another source. The new position was not applied; use /templates cancel to restore only unchanged blocks or /templates finalize to keep the current placement.", NamedTextColor.RED));
                return;
            }
            restoreOriginals(session, world);
        }
        List<OriginalBlock> originals = new ArrayList<>();
        Map<String, String> staged = new HashMap<>();
        try {
            for (TemplateBlock b : session.template.blocks()) {
                int[] rotated = transformBlock(session, b);
                int x = session.target.getBlockX() + rotated[0];
                int y = session.target.getBlockY() + b.y();
                int z = session.target.getBlockZ() + rotated[1];
                Block block = world.getBlockAt(x, y, z);
                originals.add(captureOriginal(block));
            }
            session.originals = originals;
            session.stagedData.clear();
            session.staged = true;
            persistTransactions();
            for (TemplateBlock b : session.template.blocks()) {
                int[] rotated = rotate(b.x(), b.z(), session.rotation);
                Block block = world.getBlockAt(session.target.getBlockX() + rotated[0],
                        session.target.getBlockY() + b.y(), session.target.getBlockZ() + rotated[1]);
                placeBlock(block, b, session.rotation);
                session.stagedData.put(key(block), signature(block));
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
            inventory = TemplateRepository.encodeItems(container.getSnapshotInventory().getContents());
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
        if (material == null || (!material.isBlock() && material != Material.AIR)) throw new IOException("Unknown block material " + data.material());
        block.setType(material, false);
        BlockData blockData = Bukkit.createBlockData(data.blockData());
        for (int i = 0; i < rotation; i++) blockData.rotate(StructureRotation.CLOCKWISE_90);
        block.setBlockData(blockData, false);
        var state = block.getState();
        if (!data.inventoryData().isBlank() && state instanceof Container container
                && material != Material.ENDER_CHEST) {
            container.getSnapshotInventory().setContents(TemplateRepository.decodeItems(data.inventoryData()));
            container.update(true, false);
        }
        if (data.signLines() != null && state instanceof Sign sign) {
            for (int i = 0; i < Math.min(4, data.signLines().length); i++) sign.setLine(i, data.signLines()[i]);
            sign.update(true, false);
        }
    }

    private int[] rotate(int x, int z, int rotation) {
        return CoordinateTransform.rotate(x, z, rotation);
    }

    private boolean canRestore(PlacementSession session, World world) {
        for (OriginalBlock original : session.originals) {
            String expected = session.stagedData.get(original.x() + ":" + original.y() + ":" + original.z());
            if (expected == null) return false;
            Block block = world.getBlockAt(original.x(), original.y(), original.z());
            if (!signature(block).equals(expected)) return false;
        }
        return true;
    }

    private void restoreOriginals(PlacementSession session, World world) {
        for (OriginalBlock original : session.originals) {
            String key = original.x() + ":" + original.y() + ":" + original.z();
            String expected = session.stagedData.get(key);
            Block block = world.getBlockAt(original.x(), original.y(), original.z());
            if (expected != null && !signature(block).equals(expected)) {
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

    private String signature(Block block) {
        OriginalBlock snapshot = captureOriginal(block);
        return snapshot.material() + "|" + snapshot.data() + "|" + snapshot.inventory()
                + "|" + Arrays.toString(snapshot.signLines());
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
        if (session != null && session.staged) {
            if (session.target == null || session.target.getWorld() == null) {
                if (player != null) player.sendMessage(Component.text("The placement world is unavailable. Its rollback record was preserved for recovery when that world loads.", NamedTextColor.RED));
                return;
            }
            restoreOriginals(session, session.target.getWorld());
        }
        forgetTransaction(id);
        if (player != null) player.sendMessage(Component.text(message, NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (placements.containsKey(id)) restoreAndClose(id, event.getPlayer(), "Disconnected; temporary placement rolled back.");
        naming.remove(id); pendingNames.remove(id); awaitingAnchor.remove(id); selections.clear(event.getPlayer());
    }

    private void renderPreviews() {
        for (Map.Entry<UUID, PlacementSession> entry : placements.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) renderOne(player, entry.getValue());
        }
    }

    private void renderOne(Player player, PlacementSession session) {
        if (session.target == null || session.target.getWorld() == null || session.panelAnchor == null) return;
        World world = session.target.getWorld();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (TemplateBlock b : session.template.blocks()) {
            int[] r = transformBlock(session, b);
            int x = session.target.getBlockX() + r[0];
            int y = session.target.getBlockY() + b.y();
            int z = session.target.getBlockZ() + r[1];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x + 1);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y + 1);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z + 1);
        }
        if (minX != Integer.MAX_VALUE) {
            int[] emitted = {0};
            Set<PreviewEdge> drawn = new HashSet<>();
            previewEdge(player, world, minX,minY,minZ,maxX,minY,minZ,emitted,drawn);
            previewEdge(player, world, minX,maxY,minZ,maxX,maxY,minZ,emitted,drawn);
            previewEdge(player, world, minX,minY,maxZ,maxX,minY,maxZ,emitted,drawn);
            previewEdge(player, world, minX,maxY,maxZ,maxX,maxY,maxZ,emitted,drawn);
            previewEdge(player, world, minX,minY,minZ,minX,maxY,minZ,emitted,drawn);
            previewEdge(player, world, maxX,minY,minZ,maxX,maxY,minZ,emitted,drawn);
            previewEdge(player, world, minX,minY,maxZ,minX,maxY,maxZ,emitted,drawn);
            previewEdge(player, world, maxX,minY,maxZ,maxX,maxY,maxZ,emitted,drawn);
            previewEdge(player, world, minX,minY,minZ,minX,minY,maxZ,emitted,drawn);
            previewEdge(player, world, maxX,minY,minZ,maxX,minY,maxZ,emitted,drawn);
            previewEdge(player, world, minX,maxY,minZ,minX,maxY,maxZ,emitted,drawn);
            previewEdge(player, world, maxX,maxY,minZ,maxX,maxY,maxZ,emitted,drawn);
        }
        panel(player, session);
    }

    private record PreviewEdge(int x0, int y0, int z0, int x1, int y1, int z1) {}

    private void previewEdge(Player player, World world, int x0, int y0, int z0,
                             int x1, int y1, int z1, int[] emitted, Set<PreviewEdge> drawn) {
        PreviewEdge forward = new PreviewEdge(x0,y0,z0,x1,y1,z1);
        PreviewEdge reverse = new PreviewEdge(x1,y1,z1,x0,y0,z0);
        if (drawn.contains(forward) || drawn.contains(reverse)) return;
        drawn.add(forward);
        double dx=x1-x0, dy=y1-y0, dz=z1-z0;
        int steps=Math.max(2, Math.min(32, (int)Math.ceil(Math.sqrt(dx*dx+dy*dy+dz*dz) * 2)));
        for (int i=0;i<=steps && emitted[0]<MAX_PREVIEW_PARTICLES;i++) {
            double t=(double)i/steps;
            player.spawnParticle(Particle.END_ROD,
                    new Location(world,x0+dx*t,y0+dy*t,z0+dz*t),1,0,0,0,0);
            emitted[0]++;
        }
    }

    private void panelDust(Player player, PlacementSession session, int right, int forward, Color color) {
        int x = session.panelAnchor.getBlockX() + right * session.rightX + forward * session.forwardX;
        int z = session.panelAnchor.getBlockZ() + right * session.rightZ + forward * session.forwardZ;
        dust(player, session.panelAnchor.getWorld(), x, session.panelAnchor.getBlockY(), z, color);
    }

    private int[] transformBlock(PlacementSession session, TemplateBlock block) {
        if (session.anchorRotation) return rotate(block.x(), block.z(), session.rotation);
        int minX = session.template.blocks().stream().mapToInt(TemplateBlock::x).min().orElse(0);
        int maxX = session.template.blocks().stream().mapToInt(TemplateBlock::x).max().orElse(0);
        int minZ = session.template.blocks().stream().mapToInt(TemplateBlock::z).min().orElse(0);
        int maxZ = session.template.blocks().stream().mapToInt(TemplateBlock::z).max().orElse(0);
        int x = block.x(), z = block.z();
        int centerX2 = minX + maxX + 1;
        int centerZ2 = minZ + maxZ + 1;
        for (int i = 0; i < session.rotation; i++) {
            int x2 = 2 * x + 1, z2 = 2 * z + 1;
            int nextX2 = centerX2 - (z2 - centerZ2);
            int nextZ2 = centerZ2 + (x2 - centerX2);
            x = (nextX2 - 1) / 2;
            z = (nextZ2 - 1) / 2;
        }
        return new int[]{x, z};
    }

    private boolean wouldOverlapPanel(PlacementSession session) {
        for (TemplateBlock block : session.template.blocks()) {
            int[] r = transformBlock(session, block);
            int worldX = session.target.getBlockX() + r[0];
            int worldY = session.target.getBlockY() + block.y();
            int worldZ = session.target.getBlockZ() + r[1];
            if (worldX == session.panelAnchor.getBlockX()
                    && worldY == session.panelAnchor.getBlockY()
                    && worldZ == session.panelAnchor.getBlockZ()) return true;
        }
        return false;
    }

    private void panel(Player player, PlacementSession session) {
        World world = session.panelAnchor.getWorld();
        int x = session.panelAnchor.getBlockX(), y = session.panelAnchor.getBlockY(), z = session.panelAnchor.getBlockZ();
        panelDust(player, session, 0, 1, Color.LIME);
        panelDust(player, session, 0, -1, Color.LIME);
        panelDust(player, session, 1, 0, Color.YELLOW);
        panelDust(player, session, -1, 0, Color.YELLOW);
        panelDust(player, session, -1, 1, Color.BLUE);
        panelDust(player, session, 1, -1, Color.BLUE);
        panelDust(player, session, 1, 1, Color.RED);
        panelDust(player, session, -1, -1, Color.RED);
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
                row.put("expected", s.stagedData.getOrDefault(b.x() + ":" + b.y() + ":" + b.z(), ""));
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
                List<?> rawSign = (List<?>) (row.containsKey("sign") ? row.get("sign") : List.of());
                String[] sign = rawSign.isEmpty() ? null : rawSign.stream().map(String::valueOf).toArray(String[]::new);
                OriginalBlock original = new OriginalBlock(num(row.get("x")), num(row.get("y")), num(row.get("z")),
                        String.valueOf(row.get("material")), String.valueOf(row.get("data")),
                        String.valueOf(row.containsKey("inventory") ? row.get("inventory") : ""), sign);
                String expected = String.valueOf(row.containsKey("expected") ? row.get("expected") : "");
                Block current = world.getBlockAt(original.x(), original.y(), original.z());
                if (expected.isBlank() || signature(current).equals(expected)) {
                    restoreOriginal(world, original);
                } else {
                    plugin.getLogger().warning("Crash recovery skipped " + original.x() + "," + original.y() + "," + original.z()
                            + " because the block changed after the template was staged.");
                }
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
