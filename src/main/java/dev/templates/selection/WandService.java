package dev.templates.selection;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class WandService {
    private final NamespacedKey wandKey;

    public WandService(JavaPlugin plugin) {
        wandKey = new NamespacedKey(plugin, "selection_wand");
    }

    public ItemStack createWand() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6Template Selection Wand");
        meta.setLore(List.of("§7Left-click: select block", "§7Shift + left-click: cuboid",
                "§7Right-click: remove block", "§7Use /templates help for instructions"));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isWand(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer()
                .get(wandKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public void give(Player player) {
        player.getInventory().addItem(createWand()).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }
}
