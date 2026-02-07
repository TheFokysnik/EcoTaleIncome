package com.hypixel.hytale.server.core.entity.entities;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/**
 * Stub — Hytale Player entity.
 * Real class: com.hypixel.hytale.server.core.entity.entities.Player
 */
public class Player {

    public UUID getUuid() { return UUID.randomUUID(); }

    public String getDisplayName() { return ""; }

    public boolean hasPermission(String permission) { return false; }

    public void sendMessage(String message) {}

    public void sendActionBar(String message) {}

    public PlayerRef getPlayerRef() { return new PlayerRef(); }

    public static ComponentType<EntityStore, Player> getComponentType() {
        return new ComponentType<>();
    }

    /**
     * Stub inner — PlayerRef for sendMessage(Message) pattern.
     */
    public static class PlayerRef {
        public void sendMessage(Object message) {}
        public String getUsername() { return ""; }
    }
}
