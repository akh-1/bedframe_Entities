package lol.sylvie.bedframe.api;

import net.minecraft.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public registration API for giving custom (Polymer) entities a Bedrock appearance.
 *
 * A Java entity's visual model is procedural client code, so it can't be read
 * server-side the way block/item models can. The mod therefore supplies a Bedrock
 * geometry (.geo.json content) + texture (.png bytes) per entity, and Bedframe wires
 * up the carrier disguise, the resource pack definitions, and the runtime property.
 *
 * Call {@link #register} from your mod's onInitialize, BEFORE Geyser starts (mod init
 * is early enough). Many entities may share a carrier; each is assigned a variant index
 * (1, 2, 3...; 0 is reserved for the untouched vanilla carrier) and an int property
 * "bedframe:variant" on that carrier selects which one renders.
 */
public final class BedframeEntities {

    /** A vanilla Bedrock entity used as the network/render carrier. */
    public record Carrier(String bedrockId, String vanillaGeometry, String vanillaTexture, String material) {
        /** Polar bear - good for bear/large-quadruped sized mobs. */
        public static final Carrier POLAR_BEAR =
                new Carrier("minecraft:polar_bear", "geometry.polarbear", "textures/entity/polarbear", "polarbear");
        public static final Carrier COW =
                new Carrier("minecraft:cow", "geometry.cow", "textures/entity/cow/cow", "cow");
        public static final Carrier PIG =
                new Carrier("minecraft:pig", "geometry.pig.v1.8", "textures/entity/pig/pig", "pig");
        public static final Carrier CHICKEN =
                new Carrier("minecraft:chicken", "geometry.chicken", "textures/entity/chicken/chicken", "chicken");
        public static final Carrier VILLAGER =
                new Carrier("minecraft:villager_v2", "geometry.humanoid.custom", "textures/entity/villager2/villager", "villager");
        public static final Carrier CREEPER =
                new Carrier("minecraft:creeper", "geometry.creeper", "textures/entity/creeper/creeper", "creeper");
        public static final Carrier CAT =
                new Carrier("minecraft:cat", "geometry.cat", "textures/entity/cat/tabby", "cat");
        public static final Carrier OCELOT =
                new Carrier("minecraft:ocelot", "geometry.ocelot", "textures/entity/cat/ocelot", "ocelot");

        /** For any carrier not listed above. Look up the vanilla geo/texture/material ids for that mob. */
        public static Carrier of(String bedrockId, String vanillaGeometry, String vanillaTexture, String material) {
            return new Carrier(bedrockId, vanillaGeometry, vanillaTexture, material);
        }
    }

    /** One registered custom entity.
     *  animationsJson / animationClips are null/empty for entities without converted animations. */
    public record Registration(EntityType<?> javaType, Carrier carrier, int variant,
                               String geometryId, String geometryJson, byte[] texturePng,
                               String animationsJson, List<String> animationClips) {
        public boolean hasAnimations() {
            return animationsJson != null && !animationsJson.isBlank()
                    && animationClips != null && !animationClips.isEmpty();
        }
    }

    private static final List<Registration> REGISTRATIONS = new ArrayList<>();
    private static final Map<String, Integer> nextVariantByCarrier = new java.util.HashMap<>();

    private BedframeEntities() {}

    /**
     * @param javaType     the custom Fabric/Polymer entity type
     * @param carrier      a vanilla Bedrock carrier (see {@link Carrier} presets or {@link Carrier#of})
     * @param geometryId   the Bedrock geometry identifier declared inside geometryJson (e.g. "geometry.grizzly")
     * @param geometryJson the full .geo.json content for the entity
     * @param texturePng   the PNG bytes for the entity texture
     */
    public static synchronized int register(EntityType<?> javaType, Carrier carrier,
                                            String geometryId, String geometryJson, byte[] texturePng) {
        return registerAnimated(javaType, carrier, geometryId, geometryJson, texturePng, null, null);
    }

    /**
     * Like {@link #register} but also attaches a converted Bedrock animation set.
     * @param animationsJson the full .animation.json content (or null for none)
     * @param animationClips the clip base names present in animationsJson (e.g. ["idle","walk"])
     */
    public static synchronized int registerAnimated(EntityType<?> javaType, Carrier carrier,
                                                     String geometryId, String geometryJson, byte[] texturePng,
                                                     String animationsJson, List<String> animationClips) {
        int variant = nextVariantByCarrier.merge(carrier.bedrockId(), 1, Integer::sum); // 1, 2, 3...
        REGISTRATIONS.add(new Registration(javaType, carrier, variant, geometryId, geometryJson, texturePng,
                animationsJson, animationClips == null ? null : List.copyOf(animationClips)));
        return variant;
    }

    public static synchronized List<Registration> all() {
        return List.copyOf(REGISTRATIONS);
    }

    /** Registrations grouped by carrier bedrock id, preserving insertion order. */
    public static synchronized Map<String, List<Registration>> byCarrier() {
        Map<String, List<Registration>> map = new LinkedHashMap<>();
        for (Registration r : REGISTRATIONS) {
            map.computeIfAbsent(r.carrier().bedrockId(), k -> new ArrayList<>()).add(r);
        }
        return map;
    }

    /** Java entity type -> its registration, for the runtime property pusher. */
    public static synchronized Map<EntityType<?>, Registration> byType() {
        Map<EntityType<?>, Registration> map = new java.util.HashMap<>();
        for (Registration r : REGISTRATIONS) map.put(r.javaType(), r);
        return map;
    }
}
