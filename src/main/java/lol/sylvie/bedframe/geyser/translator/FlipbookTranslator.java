package lol.sylvie.bedframe.geyser.translator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lol.sylvie.bedframe.geyser.Translator;
import lol.sylvie.bedframe.util.BedframeConstants;
import lol.sylvie.bedframe.util.ResourceHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.geysermc.geyser.api.event.EventBus;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generates Bedrock {@code textures/flipbook_textures.json} entries from Java
 * {@code .png.mcmeta} animation metadata files.
 *
 * <p>Java edition uses a sidecar mcmeta file alongside an animated texture:
 * <pre>
 *   prismarine.png.mcmeta
 *   {
 *     "animation": {
 *       "frametime": 3,
 *       "frames": [0, 1, 2, 3]
 *     }
 *   }
 * </pre>
 *
 * <p>Bedrock requires that information in a single file at the pack root:
 * <pre>
 *   textures/flipbook_textures.json
 *   [{ "flipbook_texture": "textures/blocks/prismarine", "ticks_per_frame": 3, "frames": [0,1,2,3] }]
 * </pre>
 *
 * <p>This translator scans every loaded mod's {@code assets/&lt;mod&gt;/textures/}
 * tree for {@code .png.mcmeta} files, parses the animation block, converts the
 * Java path to the Bedrock equivalent (block→blocks, item→items via
 * {@link ResourceHelper#javaToBedrockTexture}) and writes the assembled
 * flipbook list. The textures themselves are copied by BlockTranslator /
 * ItemTranslator on demand; if a particular texture isn't copied the flipbook
 * entry is harmless — Bedrock just ignores entries pointing to missing files.
 */
public class FlipbookTranslator extends Translator {
    private static final Logger LOGGER = LoggerFactory.getLogger("bedframe-flipbook");

    /** Mods we don't bother scanning — vanilla animations are baked into Bedrock. */
    private static final Set<String> SKIP_NAMESPACES = Set.of(
        "minecraft", "geyser-fabric", "geyser-neoforge", "neoforge",
        "floodgate", "fabric-permissions-api-v0", "mixinextras", "cloud", "polymer"
    );

    @Override
    public void register(EventBus<EventRegistrar> eventBus, Path packRoot) {
        try {
            emitFlipbooks(packRoot);
        } catch (Exception e) {
            LOGGER.error("Couldn't emit flipbook animations", e);
        }
        markResourcesProvided();
    }

    private void emitFlipbooks(Path packRoot) throws IOException {
        JsonArray flipbookArray = new JsonArray();
        int count = 0;

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String modId = mod.getMetadata().getId();
            if (SKIP_NAMESPACES.contains(modId)) continue;

            // Look for the textures tree in this mod's classpath. findPath returns a
            // Path inside the mod's JAR FileSystem (or the mod's source directory in dev).
            Path texturesRoot = mod.findPath("assets/" + modId + "/textures").orElse(null);
            if (texturesRoot == null || !Files.isDirectory(texturesRoot)) continue;

            try (Stream<Path> stream = Files.walk(texturesRoot)) {
                for (Path mcmetaPath : (Iterable<Path>) stream::iterator) {
                    if (!mcmetaPath.getFileName().toString().endsWith(".png.mcmeta")) continue;

                    JsonObject animation = readAnimationBlock(mcmetaPath);
                    if (animation == null) continue;

                    // Path inside the mod's textures tree, relative to texturesRoot.
                    // e.g. "block/prismarine.png.mcmeta" → "block/prismarine.png"
                    String relPng = texturesRoot.relativize(mcmetaPath).toString()
                        .replace(java.io.File.separatorChar, '/')
                        .replaceFirst("\\.mcmeta$", "");
                    // Build the Java-style and Bedrock-style relative paths.
                    // Java:    textures/block/prismarine.png
                    // Bedrock: textures/<modid>/blocks/prismarine
                    // The Bedrock path is namespaced under the mod id so it matches the
                    // namespaced output that BlockTranslator/ItemTranslator write — without
                    // that, when two mods ship the same filename, the flipbook entry points
                    // at the wrong file.
                    String bedrockPath = ResourceHelper.javaToBedrockTexture("textures/" + modId + "/" + relPng)
                        .replaceFirst("\\.png$", "");

                    JsonObject entry = new JsonObject();
                    entry.addProperty("flipbook_texture", bedrockPath);

                    // atlas_tile MUST match the key Bedframe uses in terrain_texture.json
                    // for the block's material_instance to actually pick up the animation.
                    // BlockTranslator registers each block face texture under the key
                    // "<namespace>:block/<filename_without_extension>" (or "item/" for items).
                    // Anything else and Bedrock just renders the static texture.
                    //
                    // relPng here looks like "block/void_campfire_fire.png"; we strip the
                    // extension and prepend the mod namespace.
                    String relWithoutExt = relPng.replaceFirst("\\.png$", "");
                    String atlasTile = modId + ":" + relWithoutExt;
                    entry.addProperty("atlas_tile", atlasTile);

                    // Java "frametime" → Bedrock "ticks_per_frame" (both are in ticks).
                    int frametime = animation.has("frametime")
                        ? animation.get("frametime").getAsInt()
                        : 1;
                    entry.addProperty("ticks_per_frame", frametime);

                    // If specific frames were listed, emit them. They can be:
                    //   simple ints: [0, 1, 2, 3]
                    //   objects with per-frame timing: [{"index": 0, "time": 5}, ...]
                    // Bedrock only supports the simple int form, so we flatten objects to
                    // their index and rely on a uniform ticks_per_frame.
                    if (animation.has("frames")) {
                        JsonArray frames = new JsonArray();
                        for (JsonElement f : animation.getAsJsonArray("frames")) {
                            if (f.isJsonPrimitive()) {
                                frames.add(f.getAsInt());
                            } else if (f.isJsonObject() && f.getAsJsonObject().has("index")) {
                                frames.add(f.getAsJsonObject().get("index").getAsInt());
                            }
                        }
                        if (!frames.isEmpty()) entry.add("frames", frames);
                    }

                    // Optional: Bedrock supports "blend_frames" for smooth interpolation.
                    // Java's "interpolate": true maps to this.
                    if (animation.has("interpolate") && animation.get("interpolate").getAsBoolean()) {
                        entry.addProperty("blend_frames", true);
                    }

                    flipbookArray.add(entry);
                    count++;
                }
            } catch (IOException e) {
                LOGGER.debug("Couldn't walk textures for mod {}: {}", modId, e.getMessage());
            }
        }

        if (flipbookArray.isEmpty()) {
            LOGGER.debug("No animated textures found — skipping flipbook_textures.json");
            return;
        }

        Path texturesDir = packRoot.resolve("textures");
        Files.createDirectories(texturesDir);
        Path outPath = texturesDir.resolve("flipbook_textures.json");
        try (java.io.FileWriter w = new java.io.FileWriter(outPath.toFile())) {
            BedframeConstants.GSON.toJson(flipbookArray, w);
        }
        LOGGER.info("Emitted {} flipbook animation entries", count);
    }

    /**
     * Reads a Java {@code .png.mcmeta} file and returns the {@code animation}
     * sub-object, or null if the file doesn't define an animation (mcmeta files
     * can also describe villager textures, GUI blur, etc.).
     */
    private static JsonObject readAnimationBlock(Path mcmetaPath) {
        try (java.io.InputStream in = Files.newInputStream(mcmetaPath);
             InputStreamReader reader = new InputStreamReader(in)) {
            JsonObject root = BedframeConstants.GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("animation")) return null;
            JsonElement anim = root.get("animation");
            return anim.isJsonObject() ? anim.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
