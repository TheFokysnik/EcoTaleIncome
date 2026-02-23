package com.hypixel.hytale.server.core.modules.entity.damage;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Stub — Hytale damage instance.
 * Real class: com.hypixel.hytale.server.core.modules.entity.damage.Damage
 */
public class Damage {

    /** The source that caused this damage. */
    public Source getSource() { return null; }

    /** The amount of damage dealt. */
    public float getAmount() { return 0f; }

    /** Base interface for all damage sources. */
    public interface Source {}

    /**
     * Damage caused by another entity (player, NPC, etc.).
     */
    public static class EntitySource implements Source {
        /** Reference to the attacking entity. */
        public Ref<EntityStore> getRef() { return null; }
    }
}
