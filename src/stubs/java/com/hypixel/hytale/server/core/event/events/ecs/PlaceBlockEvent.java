package com.hypixel.hytale.server.core.event.events.ecs;

import com.hypixel.hytale.component.system.CancellableEcsEvent;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

/**
 * Stub — ECS event fired when a player places a block.
 */
public class PlaceBlockEvent extends CancellableEcsEvent {
    public Vector3i getTargetBlock() { return new Vector3i(); }
    public BlockType getBlockType() { return null; }
}
