package com.developersfunction.df;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class OperatorStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Set<UUID> operators = ConcurrentHashMap.newKeySet();
    private final Set<String> names = ConcurrentHashMap.newKeySet();

    OperatorStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "operators.yml");
        load();
    }

    boolean isOperator(UUID uuid) {
        return operators.contains(uuid);
    }

    boolean add(OfflinePlayer player) {
        if (player.getUniqueId() == null) {
            return false;
        }
        operators.add(player.getUniqueId());
        if (player.getName() != null) {
            names.add(player.getName());
        }
        save();
        return true;
    }

    boolean remove(OfflinePlayer player) {
        boolean removed = player.getUniqueId() != null && operators.remove(player.getUniqueId());
        if (player.getName() != null) {
            names.removeIf(name -> name.equalsIgnoreCase(player.getName()));
        }
        save();
        return removed;
    }

    List<String> listNames() {
        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void load() {
        if (!file.exists()) {
            save();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String raw : yaml.getStringList("operators")) {
            try {
                operators.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                names.add(raw);
            }
        }
        names.addAll(yaml.getStringList("names"));
    }

    private void save() {
        file.getParentFile().mkdirs();
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> ids = operators.stream()
                .map(UUID::toString)
                .sorted(Comparator.naturalOrder())
                .toList();
        yaml.set("operators", ids);
        yaml.set("names", new ArrayList<>(names));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save operators.yml: " + e.getMessage());
        }
    }
}
