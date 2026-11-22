package dev.templates.command;

import dev.templates.book.TemplateBookService;
import dev.templates.selection.SelectionService;
import dev.templates.selection.WandService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class TemplatesCommand implements CommandExecutor, TabCompleter {
    private final WandService wands;
    private final TemplateBookService books;
    private final SelectionService selections;

    public TemplatesCommand(org.bukkit.plugin.java.JavaPlugin plugin, WandService wands,
                            TemplateBookService books, SelectionService selections) {
        this.wands = wands;
        this.books = books;
        this.selections = selections;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command must be used in-game.");
            return true;
        }
        if (!player.hasPermission("templates.admin")) {
            player.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            player.sendMessage(ChatColor.GOLD + "Templates commands:");
            player.sendMessage("/templates wand - receive the selection wand");
            player.sendMessage("/templates book - receive the master library book");
            player.sendMessage("/templates selection - show selected block count");
            player.sendMessage("/templates clear - clear your selection");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "wand" -> {
                wands.give(player);
                player.sendMessage(ChatColor.GREEN + "Selection wand issued.");
            }
            case "book" -> books.giveMasterBook(player, List.of());
            case "selection" -> player.sendMessage(ChatColor.YELLOW + "Selected blocks: " + selections.size(player));
            case "clear" -> {
                selections.clear(player);
                player.sendMessage(ChatColor.GREEN + "Selection cleared.");
            }
            case "list" -> player.sendMessage(ChatColor.YELLOW + "Template storage is the next implementation milestone.");
            default -> player.sendMessage(ChatColor.RED + "Unknown option. Use /templates help.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("wand", "book", "list", "selection", "clear", "help");
        return List.of();
    }
}
