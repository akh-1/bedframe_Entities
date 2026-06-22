package lol.sylvie.bedframe.geyser.carrier;

import lol.sylvie.bedframe.api.BedframeEntities;
import lol.sylvie.bedframe.api.BedframeEntities.Registration;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.geysermc.geyser.api.GeyserApi;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Shared decision behind every per-patch getPolymerEntityType hook (BIL, ChocoCraft, ...).
 *
 * For a bedframe-registered carrier mob being sent to a Bedrock player, returns the vanilla
 * carrier type (e.g. polar_bear) so the REAL entity rides Geyser's native tracking (movement
 * + hitbox for free) and the pack + "bedframe:variant" property re-skin it. Everyone else
 * (Java clients, unregistered entities) gets the original type back untouched.
 *
 * Adding support for a new patch framework is just a thin mixin on its getPolymerEntityType
 * that extracts the backing Entity and delegates here.
 */
public final class CarrierRetype {
    private CarrierRetype() {}

    private static final java.util.Map<Class<?>, java.lang.reflect.Method> ACCESSORS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * For patch entities that aren't themselves the Entity but expose it via an accessor
     * (e.g. ChocoCraft's {@code record BasePolymerEntity(AbstractChocobo entity)}), reflectively
     * pull the backing Entity via the named zero-arg accessor and delegate. Keeps Bedframe free
     * of any compile dependency on the patch. The accessor Method is cached per class.
     */
    public static EntityType<?> forBedrockVia(Object holder, String accessor,
                                              PacketContext context, EntityType<?> original) {
        try {
            java.lang.reflect.Method m = ACCESSORS.computeIfAbsent(holder.getClass(), c -> {
                try { var mm = c.getMethod(accessor); mm.setAccessible(true); return mm; }
                catch (NoSuchMethodException e) { return null; }
            });
            if (m == null) return original;
            Object e = m.invoke(holder);
            if (!(e instanceof Entity ent)) return original;
            return forBedrock(ent, context, original);
        } catch (ReflectiveOperationException e) {
            return original;
        }
    }

    public static EntityType<?> forBedrock(Entity self, PacketContext context, EntityType<?> original) {
        if (self == null) return original;

        Registration reg = BedframeEntities.byType().get(self.getType());
        if (reg == null) return original;            // not a bedframe-registered mob

        ServerPlayerEntity player = context.getPlayer();
        if (player == null) return original;
        if (!GeyserApi.api().isBedrockPlayer(player.getUuid())) return original; // Java: unchanged

        Identifier id = Identifier.of(reg.carrier().bedrockId());
        if (!Registries.ENTITY_TYPE.containsId(id)) return original; // e.g. Bedrock-only id
        return Registries.ENTITY_TYPE.get(id);
    }
}
