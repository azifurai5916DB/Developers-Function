package com.developersfunction.df;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DevelopersFunctionPlugin extends JavaPlugin {
    private OperatorStore operatorStore;
    private DeathLogStore deathLogStore;
    private DfCommand command;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.operatorStore = new OperatorStore(this);
        this.deathLogStore = new DeathLogStore(this);
        this.command = new DfCommand(this, operatorStore, deathLogStore);

        PluginCommand df = getCommand("df");
        if (df != null) {
            df.setExecutor(command);
            df.setTabCompleter(command);
        }

        Bukkit.getPluginManager().registerEvents(command, this);
        Bukkit.getPluginManager().registerEvents(new DeathLogListener(deathLogStore), this);
    }

    @Override
    public void onDisable() {
        if (command != null) {
            command.shutdown();
        }
    }

    public OperatorStore operatorStore() {
        return operatorStore;
    }

    public DeathLogStore deathLogStore() {
        return deathLogStore;
    }
}
