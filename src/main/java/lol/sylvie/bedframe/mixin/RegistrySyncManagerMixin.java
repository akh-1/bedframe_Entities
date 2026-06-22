package lol.sylvie.bedframe.mixin;

import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import org.geysermc.geyser.api.GeyserApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Fabric API's strict registry sync handshake for Bedrock players
 * connecting through Geyser. Without this, Bedrock players see the
 * {@code "This server requires Fabric Loader and Fabric API installed on your client!"}
 * disconnect screen whenever the server has any modded registry entry — even
 * Polymer-overlay entries that are otherwise transparent to vanilla clients.
 *
 * <p>This mirrors Hydraulic's identical mixin so Bedframe users no longer need
 * Hydraulic installed for the connectivity fix alone. Java vanilla clients
 * still go through the normal sync (they actually need the registries to
 * render correctly); for Bedrock players the registries don't matter since
 * Geyser translates everything to Bedrock equivalents on the fly.
 */
@Mixin(RegistrySyncManager.class)
public class RegistrySyncManagerMixin {
    /**
     * Inject without an explicit method descriptor — match {@code configureClient}
     * by name only. {@code RegistrySyncManager} has exactly one method called
     * {@code configureClient}, so there's no ambiguity, and matching by name alone
     * is robust across mapping environments (Yarn dev, intermediary production,
     * Mojang) since the method name is the same in all of them.
     *
     * <p>The handler parameter uses the Yarn type {@code ServerConfigurationNetworkHandler}
     * which Loom remaps to the intermediary type at build time (so the bytecode
     * descriptor matches what the runtime class actually is). Mixin enforces that
     * the injected method's descriptor exactly matches the target method's
     * descriptor — using {@code Object} here caused {@code InvalidInjectionException}
     * at startup because Mixin rejected the mismatch.
     */
    /**
     * Per-handler-class cache of the zero-arg method that returns the
     * {@code com.mojang.authlib.GameProfile}. {@code configureClient} runs on every
     * single player connection (Java and Bedrock alike), and the original
     * implementation re-scanned the handler's entire class hierarchy with
     * {@code getDeclaredMethods()} on each connect — expensive reflection on the
     * connection thread plus a burst of INFO log lines per join. The handler class is
     * stable for the lifetime of the JVM, so we resolve the accessor once per class and
     * reuse it. {@code OPTIONAL.empty()} (an empty Optional) is cached as the "no method
     * found" sentinel so we don't re-scan on every connection when resolution fails.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.Optional<java.lang.reflect.Method>>
        PROFILE_ACCESSOR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject(
        method = "configureClient",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void bedframe$skipSyncForBedrock(ServerConfigurationNetworkHandler handler, MinecraftServer server, CallbackInfo ci) {
        java.util.UUID uuid = bedframe$resolveUuid(handler);
        if (uuid == null) return; // Couldn't resolve a profile UUID — let sync proceed normally.

        if (GeyserApi.api().isBedrockPlayer(uuid)) {
            ci.cancel();
        }
    }

    private static java.util.UUID bedframe$resolveUuid(ServerConfigurationNetworkHandler handler) {
        java.lang.reflect.Method accessor = PROFILE_ACCESSOR_CACHE
            .computeIfAbsent(handler.getClass(), RegistrySyncManagerMixin::bedframe$findProfileAccessor)
            .orElse(null);
        if (accessor == null) return null;

        try {
            Object profile = accessor.invoke(handler);
            if (profile == null) return null;
            // GameProfile is a record in current Mojang authlib (accessor id()), but older
            // versions expose getId(). Try the record-style accessor first.
            try {
                if (profile.getClass().getMethod("id").invoke(profile) instanceof java.util.UUID u) return u;
            } catch (NoSuchMethodException ignored) {
                if (profile.getClass().getMethod("getId").invoke(profile) instanceof java.util.UUID u) return u;
            }
        } catch (Exception ignored) {
            // Reflection failure — treat as "unknown", sync proceeds normally.
        }
        return null;
    }

    /** Scans the handler class hierarchy once for a zero-arg method returning a GameProfile. */
    private static java.util.Optional<java.lang.reflect.Method> bedframe$findProfileAccessor(Class<?> start) {
        for (Class<?> cls = start; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!"com.mojang.authlib.GameProfile".equals(m.getReturnType().getName())) continue;
                try {
                    m.setAccessible(true);
                } catch (Exception ignored) {
                    continue;
                }
                return java.util.Optional.of(m);
            }
        }
        return java.util.Optional.empty();
    }
}
