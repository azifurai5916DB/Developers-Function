package com.developersfunction.df;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class DeathLogStore {
    private final JavaPlugin plugin;
    private final File folder;

    DeathLogStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "deathlogs");
        this.folder.mkdirs();
    }

    void append(Player player, String message) {
        File file = file(player.getUniqueId());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> logs = new ArrayList<>(yaml.getStringList("logs"));
        logs.add(Instant.now() + " | " + player.getWorld().getName() + " | "
                + player.getLocation().getBlockX() + ","
                + player.getLocation().getBlockY() + ","
                + player.getLocation().getBlockZ() + " | " + message);
        if (logs.size() > 50) {
            logs = logs.subList(logs.size() - 50, logs.size());
        }
        yaml.set("name", player.getName());
        yaml.set("logs", logs);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save death log for " + player.getName() + ": " + e.getMessage());
        }
    }

    List<String> read(UUID uuid) {
        return YamlConfiguration.loadConfiguration(file(uuid)).getStringList("logs");
    }

    private File file(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }
}
