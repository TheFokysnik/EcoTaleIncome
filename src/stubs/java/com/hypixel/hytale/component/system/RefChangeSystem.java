package com.hypixel.hytale.component.system;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;

/**
 * Stub — ECS system that reacts to a component being added to an entity.
 * Real class: com.hypixel.hytale.component.system.RefChangeSystem
 */
public abstract class RefChangeSystem<ECS_TYPE, C extends Component<ECS_TYPE>>
        implements ISystem<ECS_TYPE>, QuerySystem<ECS_TYPE> {

    @Override
    public abstract Query<ECS_TYPE> getQuery();

    public abstract void onComponentAdded(
            Ref<ECS_TYPE> ref,
            C component,
            Store<ECS_TYPE> store,
            CommandBuffer<ECS_TYPE> commandBuffer
    );
}
