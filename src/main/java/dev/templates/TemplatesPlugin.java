package dev.templates;

import dev.templates.command.TemplatesCommand;
import dev.templates.selection.SelectionService;
import dev.templates.selection.WandService;
import dev.templates.storage.TemplateRepository;
import dev.templates.visual.SelectionVisualizer;
import dev.templates.workflow.TemplateWorkflowService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class TemplatesPlugin extends JavaPlugin {
    private SelectionVisualizer visualizer;
    private TemplateWorkflowService workflow;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        SelectionService selections = new SelectionService();
        WandService wands = new WandService(this);
        TemplateRepository repository = new TemplateRepository(this);
        workflow = new TemplateWorkflowService(this, selections, wands, repository);
        visualizer = new SelectionVisualizer(this, selections);
        visualizer.start();
        workflow.start();

        PluginCommand command = getCommand("templates");
        if (command == null) {
            getLogger().severe("Missing /templates command in plugin.yml; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        TemplatesCommand handler = new TemplatesCommand(this, wands, selections, repository, workflow);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
        getServer().getPluginManager().registerEvents(workflow, this);
        getLogger().info("Templates enabled. Selection, saved template books, particle placement preview, and reversible staging are loaded.");
    }

    @Override
    public void onDisable() {
        if (workflow != null) workflow.stop();
        if (visualizer != null) visualizer.stop();
    }
}
