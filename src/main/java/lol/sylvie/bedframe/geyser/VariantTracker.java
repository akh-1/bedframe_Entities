package lol.sylvie.bedframe.geyser;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime bridge between a live entity's CURRENT FactoryTools model instance and the Bedrock
 * "bedframe:variant" index that selects its look.
 *
 * <p>How the pieces fit together:
 * <ul>
 *   <li>At startup, a variant discovery (e.g. {@link FactoryToolsDiscovery}) enumerates every
 *       {@code PolyModelInstance} a mod can show, registers each as a Bedrock variant, and records
 *       {@code instance -> variant} in {@link #MODEL_TO_VARIANT} (by identity).</li>
 *   <li>At runtime, a mixin on FactoryTools {@code SimpleEntityModel} (constructor + setModel)
 *       records {@code entityId -> currentInstance} in {@link #ENTITY_MODEL} whenever the mod
 *       changes an entity's look (colour, saddle, baby, jelly, rock variant, ...).</li>
 *   <li>The property pusher calls {@link #resolveVariant(Entity)} to learn which variant to push.</li>
 * </ul>
 *
 * <p>This inherits each mod's own state->look logic for free: we never replicate it, we just read
 * back whichever instance the mod last set.
 */
public final class VariantTracker {
    private VariantTracker() {}

    /** instance (PolyModelInstance, kept as Object to avoid a FactoryTools compile dep) -> variant. */
    public static final Map<Object, Integer> MODEL_TO_VARIANT =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /** live entity id -> its current model instance (updated by the SimpleEntityModel mixin). */
    public static final Map<Integer, Object> ENTITY_MODEL = new ConcurrentHashMap<>();

    /** entity type -> fallback variant, used before the first model instance is recorded. */
    public static final Map<EntityType<?>, Integer> DEFAULT_VARIANT = new ConcurrentHashMap<>();

    /** Called by the FactoryTools mixin on construction and on every setModel(). */
    public static void record(Object entity, Object modelInstance) {
        if (modelInstance == null || !(entity instanceof Entity e)) return;
        ENTITY_MODEL.put(e.getId(), modelInstance);
    }

    /** Forget an entity's recorded model (call on removal to avoid unbounded growth). */
    public static void forget(int entityId) {
        ENTITY_MODEL.remove(entityId);
    }

    /** True if this entity type is driven by the per-entity variant system (vs a single fixed look). */
    public static boolean isTracked(EntityType<?> type) {
        return DEFAULT_VARIANT.containsKey(type);
    }

    /**
     * Resolve the Bedrock variant for a live entity: its current instance's variant if known,
     * otherwise the type's default, otherwise 0 (the untouched vanilla carrier).
     */
    public static int resolveVariant(Entity entity) {
        Object inst = ENTITY_MODEL.get(entity.getId());
        if (inst != null) {
            Integer v = MODEL_TO_VARIANT.get(inst);
            if (v != null) return v;
        }
        Integer dv = DEFAULT_VARIANT.get(entity.getType());
        return dv != null ? dv : 0;
    }
}
