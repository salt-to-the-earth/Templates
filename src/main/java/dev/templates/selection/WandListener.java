package dev.templates.selection;

import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

public final class WandListener implements Listener {
    private final SelectionService selections;
    private final WandService wands;

    public WandListener(JavaPlugin plugin, SelectionService selections, WandService wands) {
        this.selections = selections;
        this.wands = wands;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !wands.isWand(event.getItem())) return;
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        if (action == Action.RIGHT_CLICK_BLOCK) {
            selections.remove(player, clicked);
            player.sendMessage(ChatColor.YELLOW + "Removed block. Selection: " + selections.size(player));
            return;
        }
        if (player.isSneaking()) {
            var first = selections.lastPoint(player);
            if (first == null) {
                selections.add(player, clicked);
                player.sendMessage(ChatColor.YELLOW + "First cuboid point set. Shift-click the opposite corner.");
            } else {
                selections.addCuboid(player, first, clicked.getLocation());
                player.sendMessage(ChatColor.GREEN + "Cuboid added. Selection: " + selections.size(player));
            }
        } else {
            selections.add(player, clicked);
            player.sendMessage(ChatColor.GREEN + "Block selected. Selection: " + selections.size(player));
        }
    }
}
