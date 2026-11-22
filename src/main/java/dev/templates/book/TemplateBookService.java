package dev.templates.book;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/** Paginated book layout foundation; page content is deliberately bounded. */
public final class TemplateBookService {
    private static final int MASTER_ENTRIES_PER_PAGE = 8;
    private static final int TEMPLATE_LINES_PER_PAGE = 10;

    public TemplateBookService(JavaPlugin plugin) {
    }

    public void giveMasterBook(Player player, List<String> templateNames) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Template Library");
        meta.setAuthor("Templates");
        List<String> pages = new ArrayList<>();
        int pageCount = Math.max(1, (templateNames.size() + MASTER_ENTRIES_PER_PAGE - 1)
                / MASTER_ENTRIES_PER_PAGE);
        if (templateNames.isEmpty()) {
            pages.add("TEMPLATE LIBRARY\n\nNo templates saved yet.\n\nUse /templates help.");
        } else {
            for (int page = 0; page < pageCount; page++) {
                StringBuilder text = new StringBuilder("TEMPLATE LIBRARY\n\n");
                int start = page * MASTER_ENTRIES_PER_PAGE;
                int end = Math.min(templateNames.size(), start + MASTER_ENTRIES_PER_PAGE);
                for (int i = start; i < end; i++) {
                    text.append(i + 1).append(". ").append(templateNames.get(i)).append('\n');
                }
                text.append("\nPage ").append(page + 1).append('/').append(pageCount);
                text.append("\n\nOpen with /templates open <name>");
                pages.add(text.toString());
            }
        }
        meta.setPages(pages);
        book.setItemMeta(meta);
        player.getInventory().addItem(book);
        player.sendMessage("§aTemplate Library book issued with " + pages.size() + " page(s).");
    }

    public void giveTemplateBook(Player player, String name, List<String> details) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(name.length() > 32 ? name.substring(0, 32) : name);
        meta.setAuthor("Templates");
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder("TEMPLATE: ").append(name).append("\n\n");
        int lines = 0;
        for (String detail : details) {
            if (lines >= TEMPLATE_LINES_PER_PAGE) {
                pages.add(page.toString());
                page = new StringBuilder("TEMPLATE: ").append(name).append(" (continued)\n\n");
                lines = 0;
            }
            page.append(detail).append('\n');
            lines++;
        }
        page.append("\nACTIONS\nUse /templates place ").append(name);
        pages.add(page.toString());
        meta.setPages(pages);
        book.setItemMeta(meta);
        player.getInventory().addItem(book);
    }
}
