package com.crystalrealm.ecotaleincome.listeners;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Native ECS death system that detects NPC kills by players.
 *
 * <p>Uses the Hytale ECS {@link DeathSystems.OnDeathSystem} pattern — when a
 * {@link DeathComponent} is added to an NPC entity (i.e. the NPC dies), this
 * system identifies the killing player and invokes the callback.</p>
 *
 * <p>This provides mob kill detection <b>without</b> requiring RPG Leveling,
 * enabling rewards when MMOSkillTree, EndlessLeveling, or no level plugin is used.</p>
 */
public class MobDeathSystem extends DeathSystems.OnDeathSystem {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, Player> playerType;

    /**
     * Callback: (playerUuid, npcTypeId) when a player kills an NPC.
     */
    private final BiConsumer<UUID, String> killCallback;

    // Cached reflection methods
    private volatile Method getComponentMethod;
    private volatile boolean methodResolved = false;

    public MobDeathSystem(@Nonnull BiConsumer<UUID, String> killCallback) {
        this.killCallback = killCallback;
        this.npcType = NPCEntity.getComponentType();
        this.playerType = Player.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return npcType;
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death,
                                 Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer) {
        try {
            processNpcDeath(ref, death, store, commandBuffer);
        } catch (Throwable e) {
            LOGGER.warn("Error in MobDeathSystem: {}", e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Main logic
    // ══════════════════════════════════════════════════════════════

    private void processNpcDeath(Ref<EntityStore> ref, DeathComponent death,
                                 Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer) {
        Object accessor = commandBuffer != null ? commandBuffer : store;

        // 1. Get NPC component
        NPCEntity npc = getComp(accessor, ref, npcType);
        if (npc == null && accessor == commandBuffer) {
            npc = getComp(store, ref, npcType);
        }
        if (npc == null) return;

        // 2. Get NPC type identifier
        String npcTypeId = safeNpcTypeId(npc);

        // 3. Resolve attacker
        Ref<EntityStore> attackerRef = resolveAttackerRef(npc, death);
        if (attackerRef == null || !attackerRef.isValid()) return;

        // 4. Verify attacker is a Player
        Player killer = getComp(accessor, attackerRef, playerType);
        if (killer == null && accessor == commandBuffer) {
            killer = getComp(store, attackerRef, playerType);
        }
        if (killer == null) return;

        // 5. Get player UUID
        PlayerRef playerRef = getComp(accessor, attackerRef, PlayerRef.getComponentType());
        if (playerRef == null && accessor == commandBuffer) {
            playerRef = getComp(store, attackerRef, PlayerRef.getComponentType());
        }

        UUID playerUuid;
        if (playerRef != null) {
            playerUuid = playerRef.getUuid();
        } else {
            playerUuid = killer.getUuid();
        }

        if (playerUuid == null) return;

        // 6. Invoke callback
        LOGGER.debug("Native mob kill: player={}, npc={}", playerUuid, npcTypeId);
        killCallback.accept(playerUuid, npcTypeId);
    }

    // ══════════════════════════════════════════════════════════════
    //  Attacker resolution
    // ══════════════════════════════════════════════════════════════

    @Nullable
    private Ref<EntityStore> resolveAttackerRef(NPCEntity npc, DeathComponent death) {
        // Strategy 1: DeathComponent -> Damage -> EntitySource
        if (death != null) {
            try {
                Damage damage = death.getDeathInfo();
                if (damage != null) {
                    Damage.Source source = damage.getSource();
                    if (source instanceof Damage.EntitySource entitySource) {
                        Ref<EntityStore> ref = entitySource.getRef();
                        if (ref != null && ref.isValid()) return ref;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("DeathInfo resolution failed: {}", e.getMessage());
            }
        }

        // Strategy 2: NPCEntity damage data (via reflection)
        try {
            Method getDmg = npc.getClass().getMethod("getDamageData");
            Object damageData = getDmg.invoke(npc);
            if (damageData != null) {
                Method getMost = damageData.getClass().getMethod("getMostDamagingAttacker");
                @SuppressWarnings("unchecked")
                Ref<EntityStore> ref = (Ref<EntityStore>) getMost.invoke(damageData);
                if (ref != null && ref.isValid()) return ref;

                Method getAny = damageData.getClass().getMethod("getAnyAttacker");
                @SuppressWarnings("unchecked")
                Ref<EntityStore> any = (Ref<EntityStore>) getAny.invoke(damageData);
                if (any != null && any.isValid()) return any;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            LOGGER.debug("DamageData resolution failed: {}", e.getMessage());
        }

        return null;
    }

    // ══════════════════════════════════════════════════════════════
    //  ECS component access (reflection)
    // ══════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private <C> C getComp(Object accessor, Ref<EntityStore> ref, ComponentType<EntityStore, C> type) {
        try {
            if (!methodResolved || getComponentMethod == null) {
                getComponentMethod = findGetComponentMethod(accessor);
                methodResolved = true;
            }
            if (getComponentMethod != null) {
                return (C) getComponentMethod.invoke(accessor, ref, type);
            }

            // Brute-force fallback
            for (Method m : accessor.getClass().getMethods()) {
                if (m.getName().equals("getComponent") && m.getParameterCount() == 2) {
                    try {
                        m.setAccessible(true);
                        Object result = m.invoke(accessor, ref, type);
                        getComponentMethod = m;
                        return (C) result;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            LOGGER.debug("getComp reflection failed: {}", e.getMessage());
        }
        return null;
    }

    private Method findGetComponentMethod(Object accessor) {
        try {
            Method m = accessor.getClass().getMethod("getComponent", Ref.class, ComponentType.class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {}

        for (Method m : accessor.getClass().getMethods()) {
            if (m.getName().equals("getComponent") && m.getParameterCount() == 2) {
                Class<?>[] params = m.getParameterTypes();
                if (params[0].isAssignableFrom(Ref.class)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════

    private static String safeNpcTypeId(NPCEntity npc) {
        try {
            String typeId = npc.getNPCTypeId();
            if (typeId != null && !typeId.isBlank()) return typeId.toLowerCase().trim();
        } catch (Exception ignored) {}
        return "mob";
    }
}
