package lol.sylvie.bedframe.geyser;

import lol.sylvie.bedframe.api.BedframeEntities;
import lol.sylvie.bedframe.api.BedframeEntities.Carrier;
import lol.sylvie.bedframe.geyser.model.BbAnimationConverter;
import lol.sylvie.bedframe.geyser.model.BbModelConverter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static lol.sylvie.bedframe.util.BedframeConstants.LOGGER;

/**
 * Generic, mod-agnostic discovery. Instead of guessing one path per entity id or
 * writing a class per mod (Chocobo/Enderscape/Meteors), this walks EVERY loaded mod's root paths -
 * the same enumeration Hydraulic uses to harvest textures - and indexes, across all mods:
 *
 *   - entity textures:  assets/&lt;ns&gt;/textures/entity/**.png
 *   - model files:      model/&lt;ns&gt;/&lt;path&gt;.bbmodel | .ajblueprint   (BIL / Animated Java)
 *                       assets/&lt;ns&gt;/geo/**.geo.json                  (GeckoLib - already Bedrock geo)
 *
 * Then for each registered EntityType it looks up a model + texture (by naming convention, with a
 * hints override for mods that don't follow it), converts the geometry, and registers the carrier
 * variant. Covers the data-driven cases (the majority); client-only-Java mobs still need a hint
 * pointing at a pre-converted geo, or a hand-written discovery.
 *
 * This is a skeleton: the enumeration + dispatch are real; the texture matching heuristic and the
 * GeckoLib passthrough are intentionally simple and marked for iteration.
 */
public final class GenericEntityDiscovery {
    private GenericEntityDiscovery() {}

    /** entity id -> explicit overrides for mobs that don't follow the naming convention. */
    private static final Map<Identifier, String> GEO_HINTS = new HashMap<>();      // id -> "/path/to.geo.json" (classpath) or asset key
    private static final Map<Identifier, String> TEXTURE_HINTS = new HashMap<>();  // id -> "ns:textures/entity/foo"

    /** Animation gate: convert everything, or just an allowlist for a PoC. */
    private static final Set<String> ANIMATE_ALLOWLIST = Set.of("toms_mobs:zebra_baby");
    private static final boolean ANIMATE_ALL = true;

    private record ModelFile(Path path, String kind) {}  // kind: bbmodel | ajblueprint | geckolib

    public static synchronized void discover(Carrier carrier) {
        // 1. Index every entity texture and model file across all mods (one pass).
        Map<String, Path> textures = new HashMap<>();         // "ns:relativeUnderEntity" and "ns:basename"
        Map<String, ModelFile> models = new HashMap<>();      // "ns:path" (from /model) and "ns:basename" (geckolib)

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (Path root : mod.getRootPaths()) {
                indexRoot(root, textures, models);
            }
        }
        LOGGER.info("[bedframe] Generic scan: {} entity textures, {} model files indexed",
                textures.size(), models.size());

        // 2. For each registered entity type, try to resolve a model + texture and register it.
        int registered = 0, animated = 0;
        for (Identifier id : Registries.ENTITY_TYPE.getIds()) {
            try {
                ModelFile model = resolveModel(id, models);
                if (model == null) continue;                 // no geometry for this entity - skip

                EntityType<?> type = Registries.ENTITY_TYPE.get(id);

                if (model.kind().equals("geckolib")) {
                    // GeckoLib geo.json is already Bedrock geometry - near passthrough. Texture is a
                    // separate file (referenced in the client renderer), so pair it by convention.
                    String geoJson = Files.readString(model.path());
                    String geometryId = extractGeometryId(geoJson, id);
                    Path tex = resolveTexture(id, textures);
                    if (tex == null) { LOGGER.warn("[bedframe] {} geckolib geo but no texture found", id); continue; }
                    byte[] png = Files.readAllBytes(tex);
                    // TODO: bf_ prefix bones + convert geckolib's separate .animation.json before register.
                    BedframeEntities.register(type, carrier, geometryId, geoJson, png);
                    registered++;
                } else {
                    // .bbmodel / .ajblueprint: embedded texture + embedded animations. Read the bytes
                    // once (BbModelConverter needs a stream, BbAnimationConverter needs the JSON root).
                    byte[] bytes = Files.readAllBytes(model.path());
                    BbModelConverter.Converted c = BbModelConverter.convert(
                            id.getNamespace(), id.getPath(), new ByteArrayInputStream(bytes));
                    if (c.texturePng().length == 0) {
                        LOGGER.warn("[bedframe] {} model has no embedded texture, skipping", id);
                        continue;
                    }

                    // Animation conversion - keyframe pipeline, so the
                    // animated render that already worked stays wired through the generic path.
                    String animationsJson = null;
                    List<String> clips = null;
                    if (ANIMATE_ALL || ANIMATE_ALLOWLIST.contains(id.toString())) {
                        JsonObject root = JsonParser.parseReader(new InputStreamReader(
                                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).getAsJsonObject();
                        BbAnimationConverter.Converted anim =
                                BbAnimationConverter.convert(id.getNamespace(), id.getPath(), root);
                        if (anim != null) {
                            animationsJson = anim.animationsJson();
                            clips = anim.clips();
                            animated++;
                            LOGGER.info("[bedframe] Converted {} animation clip(s) for {}: {}",
                                    clips.size(), id, clips);
                        }
                    }

                    BedframeEntities.registerAnimated(type, carrier, c.geometryId(), c.geometryJson(),
                            c.texturePng(), animationsJson, clips);
                    registered++;
                }
            } catch (Throwable t) {
                LOGGER.warn("[bedframe] Generic discovery failed for {}: {}", id, t.toString());
            }
        }
        LOGGER.info("[bedframe] Generic discovery registered {} entities ({} animated)", registered, animated);
    }

    // --- indexing -----------------------------------------------------------------------------

    private static void indexRoot(Path root, Map<String, Path> textures, Map<String, ModelFile> models) {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');

                // entity textures: assets/<ns>/textures/entity/<sub>.png
                if (rel.startsWith("assets/") && rel.contains("/textures/entity/") && rel.endsWith(".png")) {
                    String ns = rel.split("/")[1];
                    String sub = rel.substring(rel.indexOf("/textures/entity/") + "/textures/entity/".length(),
                            rel.length() - 4);
                    textures.putIfAbsent(ns + ":" + sub, p);
                    textures.putIfAbsent(ns + ":" + basename(sub), p);
                    return;
                }

                // BIL / Animated Java models: /model/<ns>/<path>.(bbmodel|ajblueprint)
                if (rel.startsWith("model/") && (rel.endsWith(".bbmodel") || rel.endsWith(".ajblueprint"))) {
                    String[] parts = rel.split("/", 3);            // model, ns, path.ext
                    if (parts.length == 3) {
                        String ns = parts[1];
                        String path = parts[2].substring(0, parts[2].lastIndexOf('.'));
                        String kind = rel.endsWith(".ajblueprint") ? "ajblueprint" : "bbmodel";
                        models.putIfAbsent(ns + ":" + path, new ModelFile(p, kind));
                    }
                    return;
                }

                // GeckoLib geometry: assets/<ns>/geo/**.geo.json
                if (rel.startsWith("assets/") && rel.contains("/geo/") && rel.endsWith(".geo.json")) {
                    String ns = rel.split("/")[1];
                    String name = basename(rel.substring(0, rel.length() - ".geo.json".length()));
                    models.putIfAbsent(ns + ":" + name, new ModelFile(p, "geckolib"));
                }
            });
        } catch (Exception e) {
            LOGGER.warn("[bedframe] Failed to walk {}: {}", root, e.toString());
        }
    }

    // --- resolution (convention + hints) ------------------------------------------------------

    private static ModelFile resolveModel(Identifier id, Map<String, ModelFile> models) {
        String hint = GEO_HINTS.get(id);
        if (hint != null && models.containsKey(hint)) return models.get(hint);
        ModelFile m = models.get(id.getNamespace() + ":" + id.getPath());          // exact id match
        if (m == null) m = models.get(id.getNamespace() + ":" + basename(id.getPath()));
        return m;
    }

    private static Path resolveTexture(Identifier id, Map<String, Path> textures) {
        String hint = TEXTURE_HINTS.get(id);
        if (hint != null && textures.containsKey(hint)) return textures.get(hint);
        Path t = textures.get(id.getNamespace() + ":" + id.getPath());
        if (t == null) t = textures.get(id.getNamespace() + ":" + basename(id.getPath()));
        return t;
    }

    // --- helpers --------------------------------------------------------------------------------

    private static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** Pull the identifier out of a geo.json so the render controller can reference it. */
    private static String extractGeometryId(String geoJson, Identifier id) {
        int i = geoJson.indexOf("\"identifier\"");
        if (i >= 0) {
            int q1 = geoJson.indexOf('"', geoJson.indexOf(':', i) + 1);
            int q2 = geoJson.indexOf('"', q1 + 1);
            if (q1 >= 0 && q2 > q1) return geoJson.substring(q1 + 1, q2);
        }
        return "geometry." + id.getNamespace() + "." + id.getPath();
    }
}
