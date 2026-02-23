package com.hypixel.hytale.server.core.modules.entity.damage;

import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Stub — Hytale death system container.
 * Real class: com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems
 */
public class DeathSystems {

    /**
     * Abstract system that reacts to entity death.
     * Extends RefChangeSystem so the JVM bridge method
     * onComponentAdded(Ref, Component, Store, CommandBuffer) is generated.
     */
    public static abstract class OnDeathSystem
            extends RefChangeSystem<EntityStore, DeathComponent> {
    }
}
