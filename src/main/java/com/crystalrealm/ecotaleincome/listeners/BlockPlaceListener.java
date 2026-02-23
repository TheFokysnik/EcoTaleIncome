package com.crystalrealm.ecotaleincome.listeners;

import com.crystalrealm.ecotaleincome.EcoTaleIncomePlugin;
import com.crystalrealm.ecotaleincome.protection.PlacedBlockTracker;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

/**
 * Listens for block place events and records positions in the
 * {@link PlacedBlockTracker} to prevent place-and-break exploits.
 *
 * <p>When a player places any block, its position is recorded. All reward
 * listeners (mining, woodcutting, farming) check the tracker before
 * granting income — player-placed blocks are denied rewards.</p>
 */
public class BlockPlaceListener {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final EcoTaleIncomePlugin plugin;
    private final PlacedBlockTracker placedBlockTracker;

    public BlockPlaceListener(EcoTaleIncomePlugin plugin,
                              PlacedBlockTracker placedBlockTracker) {
        this.plugin = plugin;
        this.placedBlockTracker = placedBlockTracker;
    }

    // ── Registration ────────────────────────────────────────────

    public void register(ComponentRegistryProxy<EntityStore> registry) {
        if (!plugin.getConfigManager().getConfig().getProtection().isDenyPlayerPlacedBlocks()) {
            LOGGER.info("Player-placed block protection is disabled — skipping listener.");
            return;
        }

        registry.registerSystem(new PlaceBlockSystem());
        LOGGER.info("BlockPlace listener registered (placed-block exploit protection active).");
    }

    // ── ECS Event System ────────────────────────────────────────

    /**
     * ECS system that handles {@link PlaceBlockEvent} to track placed positions.
     */
    private class PlaceBlockSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

        protected PlaceBlockSystem() {
            super(PlaceBlockEvent.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk,
                           Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer,
                           PlaceBlockEvent event) {
            try {
                PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
                if (playerRef == null) return;

                Vector3i pos = event.getTargetBlock();
                placedBlockTracker.recordPlacement(pos.getX(), pos.getY(), pos.getZ());
            } catch (Throwable e) {
                LOGGER.error("Error tracking block placement.", e);
            }
        }
    }
}
