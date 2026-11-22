package dev.templates.command;

import dev.templates.model.TemplateData;
import dev.templates.selection.SelectionService;
import dev.templates.selection.WandService;
import dev.templates.storage.TemplateRepository;
import dev.templates.workflow.TemplateWorkflowService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;

public final class TemplatesCommand implements CommandExecutor, TabCompleter {
    private final WandService wands;
    private final SelectionService selections;
    private final TemplateRepository repository;
    private final TemplateWorkflowService workflow;

    public TemplatesCommand(org.bukkit.plugin.java.JavaPlugin plugin, WandService wands,
                            SelectionService selections, TemplateRepository repository,
                            TemplateWorkflowService workflow) {
        this.wands = wands;
        this.selections = selections;
        this.repository = repository;
        this.workflow = workflow;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("templates.admin")) {
            sender.sendMessage(Component.text("You do not have permission to use Templates.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            if (sender instanceof Player player) workflow.giveMasterBook(player);
            sendHelp(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command must be run in-game.");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "wand" -> {
                wands.give(player);
                player.sendMessage(Component.text("Templates wand added. Left-click to select, Shift-left-click for a cuboid, right-click to remove.", NamedTextColor.GREEN));
            }
            case "book", "library" -> workflow.giveMasterBook(player);
            case "selection" -> player.sendMessage(Component.text("Selected blocks: " + selections.size(player), NamedTextColor.YELLOW));
            case "clear" -> {
                selections.clear(player);
                player.sendMessage(Component.text("Selection cleared.", NamedTextColor.GREEN));
            }
            case "create" -> workflow.beginCreate(player);
            case "confirm" -> workflow.confirmCreate(player);
            case "open" -> {
                if (args.length < 2) player.sendMessage(Component.text("Usage: /templates open <template-id>", NamedTextColor.YELLOW));
                else workflow.openTemplate(player, args[1]);
            }
            case "place" -> {
                if (args.length < 2) player.sendMessage(Component.text("Usage: /templates place <template-id>", NamedTextColor.YELLOW));
                else workflow.beginPlace(player, args[1]);
            }
            case "rename" -> {
                if (args.length < 2) player.sendMessage(Component.text("Usage: /templates rename <template-id>", NamedTextColor.YELLOW));
                else workflow.beginRename(player, args[1]);
            }
            case "delete" -> {
                if (args.length < 2) player.sendMessage(Component.text("Usage: /templates delete <template-id>", NamedTextColor.YELLOW));
                else workflow.deleteTemplate(player, args[1]);
            }
            case "finalize" -> workflow.finalizePlacement(player);
            case "cancel" -> workflow.cancelPlacement(player);
            case "list" -> {
                List<TemplateData> all = repository.all();
                player.sendMessage(Component.text("Saved templates: " + all.size(), NamedTextColor.GOLD));
                for (TemplateData data : all) {
                    player.sendMessage(Component.text("• " + data.name() + " (" + data.blocks().size() + " blocks) — /templates open " + data.id(), NamedTextColor.GRAY));
                }
            }
            default -> sendHelp(player);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("Templates — structure library", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/templates wand", NamedTextColor.YELLOW).append(Component.text(" — get the single workflow wand", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/templates book", NamedTextColor.YELLOW).append(Component.text(" — open the Master Template Book", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/templates create", NamedTextColor.YELLOW).append(Component.text(" — name and save the current selection", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/templates place <id>", NamedTextColor.YELLOW).append(Component.text(" — preview and stage a saved template", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/templates finalize | cancel", NamedTextColor.YELLOW).append(Component.text(" — commit or roll back staged placement", NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("wand", "book", "create", "confirm", "list", "open", "place", "rename", "delete", "selection", "clear", "finalize", "cancel", "help");
        if (args.length == 2 && List.of("open", "place", "rename", "delete").contains(args[0].toLowerCase())) {
            return repository.all().stream().map(TemplateData::id).filter(id -> id.startsWith(args[1])).toList();
        }
        return List.of();
    }
}
