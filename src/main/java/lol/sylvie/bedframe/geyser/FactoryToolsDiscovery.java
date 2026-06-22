package lol.sylvie.bedframe.geyser;

import lol.sylvie.bedframe.api.BedframeEntities;
import lol.sylvie.bedframe.api.BedframeEntities.Carrier;
import lol.sylvie.bedframe.geyser.model.BbModelConverter.Converted;
import lol.sylvie.bedframe.geyser.model.EmuAnimationConverter;
import lol.sylvie.bedframe.geyser.model.EmuModelConverter;
import lol.sylvie.bedframe.geyser.model.ProceduralAnimations;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static lol.sylvie.bedframe.util.BedframeConstants.LOGGER;

/**
 * Generic discovery for FactoryTools / Polymer-patch mobs (Enderscape, deer-mod-patch, future ones).
 *
 * Finding the per-mod model registry used to rely on a name/path convention ({@code *\/entity/model/*Models}),
 * which missed mods structured differently (e.g. deer-mod-patch). Now we scan the raw .class bytes of
 * every mod for ones that reference FactoryTools {@code PolyModelInstance} AND carry an {@code ALL}
 * token - that catches the registry class regardless of its name or package - then verify by reflection.
 *
 * For each verified registry: reflect {@code data()} (LayerDefinition) + {@code texture()} (Identifier)
 * per instance, convert geometry once per LayerDefinition, map the entity type from the texture path
 * {@code entity/<type>/}, register the carrier variant, and fill {@link VariantTracker}.
 *
 * Heavy diagnostics on purpose: every candidate class and every reason a class yields 0 is logged.
 */
public final class FactoryToolsDiscovery {
    private FactoryToolsDiscovery() {}

    /** Registries handled by a specialised discovery instead (ChocoCraft composites its textures). */
    private static final Set<String> SKIP_CLASSES = Set.of();

    /** Procedural locomotion: generic bone-name-driven walk for any mob with conventionally named
     *  legs. (The private build can special-case non-leg movers here.) */
    private static String proceduralFor(String ns, String type, java.util.List<String> boneNames) {
        return ProceduralAnimations.genericWalk(ns, type, boneNames);
    }

    /** Merge two Bedrock animation files (walk + declaratives) into one "animations" object. */
    private static String mergeAnimations(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        try {
            com.google.gson.JsonObject ra = com.google.gson.JsonParser.parseString(a).getAsJsonObject();
            com.google.gson.JsonObject rb = com.google.gson.JsonParser.parseString(b).getAsJsonObject();
            com.google.gson.JsonObject anims = ra.getAsJsonObject("animations");
            for (Map.Entry<String, com.google.gson.JsonElement> e : rb.getAsJsonObject("animations").entrySet())
                anims.add(e.getKey(), e.getValue());
            return ra.toString();
        } catch (Exception e) {
            return a;   // fall back to the walk clip only
        }
    }

    /** Bone names from a converted Bedrock geometry JSON (minecraft:geometry[0].bones[].name). */
    private static java.util.List<String> boneNamesOf(String geometryJson) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(geometryJson).getAsJsonObject();
            com.google.gson.JsonObject geo = root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            for (com.google.gson.JsonElement b : geo.getAsJsonArray("bones"))
                names.add(b.getAsJsonObject().get("name").getAsString());
        } catch (Exception ignored) { /* malformed/empty geometry -> no bones */ }
        return names;
    }

    public static synchronized void discover(Carrier carrier) {
        List<String> classes = findModelRegistries();
        LOGGER.info("[bedframe] FactoryTools scan: {} candidate registry classes: {}", classes.size(), classes);

        int totalVariants = 0, totalTypes = 0;
        for (String className : classes) {
            if (SKIP_CLASSES.contains(className)) {
                LOGGER.info("[bedframe] FactoryTools: {} -> SKIPPED (specialised discovery)", className);
                continue;
            }
            try {
                int[] r = processModelsClass(className, carrier);
                totalVariants += r[0];
                totalTypes += r[1];
            } catch (Throwable t) {
                LOGGER.warn("[bedframe] FactoryTools: {} -> FAILED: {}", className, t.toString());
            }
        }
        LOGGER.info("[bedframe] FactoryTools discovery: {} variants across {} types", totalVariants, totalTypes);
    }

    // --- find candidate registry classes by scanning .class bytes ----------------------------

    private static List<String> findModelRegistries() {
        Set<String> out = new LinkedHashSet<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (Path root : mod.getRootPaths()) {
                try (Stream<Path> stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile).forEach(p -> {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        if (!rel.endsWith(".class")) return;
                        if (rel.contains("/mixin/") || rel.endsWith("Mixin.class")) return;  // not registries
                        try {
                            // ISO-8859-1 keeps bytes 1:1 so ASCII tokens in the constant pool match.
                            String body = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                            if (body.contains("PolyModelInstance") && body.contains("ALL")) {
                                out.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
                            }
                        } catch (Exception ignored) { /* unreadable entry */ }
                    });
                } catch (Exception e) {
                    LOGGER.warn("[bedframe] Failed to walk {}: {}", root, e.toString());
                }
            }
        }
        return new ArrayList<>(out);
    }

    // --- process one registry class -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static int[] processModelsClass(String className, Carrier carrier) {
        Class<?> modelsClass;
        try {
            modelsClass = Class.forName(className);
        } catch (Throwable t) {
            LOGGER.info("[bedframe] FactoryTools: {} -> 0 (Class.forName failed: {})", className, t);
            return new int[]{0, 0};
        }
        Object allObj;
        try {
            allObj = modelsClass.getField("ALL").get(null);
        } catch (Throwable t) {
            LOGGER.info("[bedframe] FactoryTools: {} -> 0 (no public static ALL field)", className);
            return new int[]{0, 0};
        }
        if (!(allObj instanceof List<?> all)) {
            LOGGER.info("[bedframe] FactoryTools: {} -> 0 (ALL is not a List)", className);
            return new int[]{0, 0};
        }
        if (all.isEmpty()) {
            LOGGER.info("[bedframe] FactoryTools: {} -> 0 (ALL empty - not initialised at scan time)", className);
            return new int[]{0, 0};
        }

        Map<Object, EntityType<?>> defaultInstances = new IdentityHashMap<>();
        try {
            Map<Object, Object> byType = (Map<Object, Object>) modelsClass.getField("BY_TYPE").get(null);
            for (Map.Entry<Object, Object> e : byType.entrySet())
                if (e.getKey() instanceof EntityType<?> t) defaultInstances.put(e.getValue(), t);
        } catch (Throwable ignored) { /* optional */ }

        Map<Object, Converted> geoByLayer = new IdentityHashMap<>();
        Map<EntityType<?>, Integer> firstVariant = new HashMap<>();
        Map<EntityType<?>, Integer> defaultVariant = new HashMap<>();
        int geoIndex = 0, registered = 0;
        // skip-reason counters for the diagnostic line
        int sNoData = 0, sNotEntityPath = 0, sTypeUnreg = 0, sNoTexture = 0, sConvFail = 0;

        for (Object instance : all) {
            try {
                Object texId = instance.getClass().getMethod("texture").invoke(instance);
                Object data = instance.getClass().getMethod("data").invoke(instance);
                if (texId == null || data == null) { sNoData++; continue; }

                Identifier id = (Identifier) texId;
                String ns = id.getNamespace(), path = id.getPath();
                String[] seg = path.split("/");
                if (seg.length < 2 || !"entity".equals(seg[0])) { sNotEntityPath++; continue; }

                Identifier typeId = Identifier.of(ns, seg[1]);
                if (!Registries.ENTITY_TYPE.containsId(typeId)) { sTypeUnreg++; continue; }
                EntityType<?> type = Registries.ENTITY_TYPE.get(typeId);

                byte[] png = readResource("assets/" + ns + "/textures/" + path + ".png");
                if (png.length == 0) { sNoTexture++; continue; }

                Converted conv = geoByLayer.get(data);
                if (conv == null) {
                    try {
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
                        conv = EmuModelConverter.convert(ns, "geo" + (geoIndex++), data, png,
                                img.getWidth(), img.getHeight(), EmuModelConverter.DEFAULT_Y_OFFSET);
                        geoByLayer.put(data, conv);
                    } catch (Throwable t) { sConvFail++; continue; }   // emuvanilla v1 / unconvertible
                }

                String walkJson = proceduralFor(ns, seg[1], boneNamesOf(conv.geometryJson()));
                EmuAnimationConverter.Converted decl = EmuAnimationConverter.fromModel(ns, seg[1], instance);

                java.util.List<String> clips = new java.util.ArrayList<>();
                if (walkJson != null) clips.addAll(ProceduralAnimations.CLIPS);
                if (decl != null) {
                    clips.addAll(decl.clips());
                    LOGGER.info("[bedframe] {} declarative clip(s) for {}: {}", decl.clips().size(), typeId, decl.clips());
                }
                String animJson = mergeAnimations(walkJson, decl == null ? null : decl.animationsJson());

                int variant = BedframeEntities.registerAnimated(type, carrier, conv.geometryId(),
                        conv.geometryJson(), png, animJson, clips.isEmpty() ? null : clips);
                VariantTracker.MODEL_TO_VARIANT.put(instance, variant);
                firstVariant.putIfAbsent(type, variant);
                if (defaultInstances.get(instance) == type) defaultVariant.put(type, variant);
                registered++;
            } catch (Throwable t) {
                LOGGER.warn("[bedframe] FactoryTools: instance in {} failed: {}", className, t.toString());
            }
        }

        for (EntityType<?> type : firstVariant.keySet())
            VariantTracker.DEFAULT_VARIANT.put(type, defaultVariant.getOrDefault(type, firstVariant.get(type)));

        LOGGER.info("[bedframe] FactoryTools: {} -> {} variants / {} types  (ALL={}; skipped: noData={}, notEntityPath={}, typeUnregistered={}, noTexture={}, convFail={})",
                className, registered, firstVariant.size(), all.size(),
                sNoData, sNotEntityPath, sTypeUnreg, sNoTexture, sConvFail);
        return new int[]{registered, firstVariant.size()};
    }

    private static byte[] readResource(String path) {
        try (InputStream in = FactoryToolsDiscovery.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
