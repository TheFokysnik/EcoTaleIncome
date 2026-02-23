package com.hypixel.hytale.server.npc.role;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Stub — Hytale NPC Role.
 * Real class: com.hypixel.hytale.server.npc.role.Role
 */
public class Role {

    /** Role name, e.g. "Skeleton Warrior", "Zombie". */
    public String getRoleName() { return null; }

    /** Whether this NPC is friendly to the given entity. */
    public boolean isFriendly(Ref<EntityStore> targetRef, CommandBuffer<EntityStore> commandBuffer) {
        return false;
    }

    /** Initial max health for this role. */
    public float getInitialMaxHealth() { return 0f; }
}
