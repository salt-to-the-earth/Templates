package dev.templates;

import dev.templates.book.TemplateBookService;
import dev.templates.command.TemplatesCommand;
import dev.templates.selection.SelectionService;
import dev.templates.selection.WandListener;
import dev.templates.selection.WandService;
import dev.templates.visual.SelectionVisualizer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Main entry point for the standalone Templates plugin. */
public final class TemplatesPlugin extends JavaPlugin {
    private SelectionVisualizer visualizer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        SelectionService selections = new SelectionService();
        WandService wands = new WandService(this);
        TemplateBookService books = new TemplateBookService(this);
        visualizer = new SelectionVisualizer(this, selections);
        visualizer.start();

        TemplatesCommand commandHandler = new TemplatesCommand(this, wands, books, selections);
        PluginCommand command = getCommand("templates");
        if (command == null) {
            getLogger().severe("Missing /templates command in plugin.yml; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        getServer().getPluginManager().registerEvents(
                new WandListener(this, selections, wands), this);

        getLogger().info("Templates enabled. Selection wand and paginated book foundation are ready.");
    }

    @Override
    public void onDisable() {
        if (visualizer != null) visualizer.stop();
    }
}
