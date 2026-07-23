package com.developersfunction.df;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

final class DeathLogListener implements Listener {
    private final DeathLogStore store;

    DeathLogListener(DeathLogStore store) {
        this.store = store;
    }

    @EventHandler
    void onDeath(PlayerDeathEvent event) {
        String message = event.getDeathMessage() == null ? "Died" : event.getDeathMessage();
        store.append(event.getEntity(), message);
    }
}
