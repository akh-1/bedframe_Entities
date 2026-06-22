package lol.sylvie.bedframe.geyser;

import lol.sylvie.bedframe.api.BedframeEntities;
import lol.sylvie.bedframe.api.BedframeEntities.Registration;
import lol.sylvie.bedframe.geyser.translator.EntityTranslator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;

import java.util.List;
import java.util.Map;

/**
 * Pushes the carrier's "bedframe:variant" int to each Bedrock viewer for every live
 * registered entity, so the resource pack's render controller selects the right model.
 *
 * Works for ANY spawn (command, natural, mod-driven). Call {@link #init()} once from
 * BedframeInitializer.onInitialize().
 */
public final class EntityPropertyPusher {

    private static boolean initialized = false;
    private static int tickCounter = 0;

    private EntityPropertyPusher() {}

    public static void init() {
        if (initialized) return;
        initialized = true;

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % 20 != 0) return;                 // once per second is plenty
            if (EntityTranslator.VARIANT_PROPS.isEmpty()) return; // properties not registered yet

            Map<EntityType<?>, Registration> byType = BedframeEntities.byType();
            if (byType.isEmpty()) return;

            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            if (players.isEmpty()) return;

            for (ServerWorld world : server.getWorlds()) {
                for (Entity entity : world.iterateEntities()) {
                    if (entity.isRemoved()) continue;
                    Registration reg = byType.get(entity.getType());
                    if (reg == null) continue;

                    GeyserIntEntityProperty prop = EntityTranslator.VARIANT_PROPS.get(reg.carrier().bedrockId());
                    if (prop == null) continue;

                    int javaId = entity.getId();
                    // Per-entity variant for types driven by the variant system (chocobo colours,
                    // enderscape states, ...); fixed per-type variant for single-look mobs (Tom's).
                    int variant = VariantTracker.isTracked(entity.getType())
                            ? VariantTracker.resolveVariant(entity)
                            : reg.variant();
                    for (ServerPlayerEntity player : players) {
                        if (!GeyserApi.api().isBedrockPlayer(player.getUuid())) continue;
                        GeyserConnection conn = GeyserApi.api().connectionByUuid(player.getUuid());
                        if (conn == null) continue;
                        conn.entities().entityByJavaId(javaId).thenAccept(ge -> {
                            if (ge != null) ge.updateProperty(prop, variant);
                        });
                    }
                }
            }
        });
    }
}
