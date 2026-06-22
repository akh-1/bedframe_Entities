package lol.sylvie.bedframe.geyser.translator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lol.sylvie.bedframe.geyser.Translator;
import lol.sylvie.bedframe.util.BedframeConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.geysermc.geyser.api.event.EventBus;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Walks every loaded mod's {@code assets/<ns>/sounds.json} and copies the referenced
 * {@code .ogg} files into the Bedrock resource pack, plus emits a
 * {@code sounds/sound_definitions.json} that maps Java sound event ids
 * ({@code <ns>:<event>}) to the copied bedrock-side files.
 *
 * <p>Why this exists: Hydraulic does this for free via the {@code pack-converter} library,
 * but Bedframe's {@code PackGenerator} skips that pipeline entirely. Result: music discs,
 * Polymer-patched mob sounds, custom GUI clicks, and similar audio simply don't play for
 * Bedrock users when only Bedframe is loaded.
 *
 * <p>What this implementation copies:
 * <ul>
 *   <li>Every {@code .ogg} listed under {@code "sounds": [...]} in any mod's {@code sounds.json}</li>
 *   <li>Both string-form entries ({@code "ns:path"}) and object-form entries
 *       ({@code {"name": "ns:path", ...}})</li>
 * </ul>
 *
 * <p>Format produced ({@code sounds/sound_definitions.json}):
 * <pre>
 * {
 *   "format_version": "1.20.20",
 *   "sound_definitions": {
 *     "namespace:event_name": {
 *       "category": "neutral",
 *       "sounds": ["sounds/<ns>/<file_no_ext>"]
 *     }
 *   }
 * }
 * </pre>
 */
public class SoundTranslator extends Translator {
    private static final Logger LOGGER = LoggerFactory.getLogger("bedframe-sounds");

    /** Mods we don't bother scanning — vanilla sounds are already part of the Bedrock client. */
    private static final Set<String> SKIP_NAMESPACES = Set.of(
        "minecraft", "geyser-fabric", "geyser-neoforge", "neoforge",
        "floodgate", "fabric-permissions-api-v0", "mixinextras", "cloud", "polymer"
    );

    @Override
    public void register(EventBus<EventRegistrar> eventBus, Path packRoot) {
        // Emit sounds synchronously: there is no Geyser data dependency here, only the
        // mod loader's asset trees, and packRoot is already known. Doing it deferred (on
        // GeyserDefineCustomBlocksEvent / PostInitialize) created a race where the
        // GeyserDefineResourcePacksEvent fired before sounds finished copying — so
        // hasProvidedResources() was false when the pack zip got built, and on subsequent
        // runs the existing bedframe.zip was held open while a deletion was attempted.
        try {
            emitSounds(packRoot);
        } catch (Exception e) {
            LOGGER.error("Couldn't emit sound pack", e);
        }
        markResourcesProvided();
    }

    private void emitSounds(Path packRoot) throws IOException {
        Path soundsDir = packRoot.resolve("sounds");
        Files.createDirectories(soundsDir);

        JsonObject soundDefinitions = new JsonObject();
        int totalCopied = 0;
        Set<String> seenEvents = new HashSet<>();

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String modId = mod.getMetadata().getId();
            if (SKIP_NAMESPACES.contains(modId)) continue;

            // sounds.json can use either the mod id as namespace OR a different namespace
            // (rare — usually the mod id matches). We probe directly by mod id.
            String soundsJsonPath = "assets/" + modId + "/sounds.json";

            JsonObject soundsJson;
            try (InputStream stream = mod.findPath(soundsJsonPath)
                    .map(p -> {
                        try { return Files.newInputStream(p); }
                        catch (IOException e) { return null; }
                    })
                    .orElse(null)) {
                if (stream == null) continue;
                soundsJson = BedframeConstants.GSON.fromJson(
                    new java.io.InputStreamReader(stream), JsonObject.class);
            } catch (Exception e) {
                LOGGER.debug("Couldn't read sounds.json for mod {}", modId, e);
                continue;
            }
            if (soundsJson == null) continue;

            for (java.util.Map.Entry<String, JsonElement> entry : soundsJson.entrySet()) {
                String eventName = entry.getKey();
                JsonElement value = entry.getValue();
                if (!value.isJsonObject()) continue;

                JsonObject eventObj = value.getAsJsonObject();
                JsonElement soundsArr = eventObj.get("sounds");
                if (soundsArr == null || !soundsArr.isJsonArray()) continue;

                JsonArray bedrockSoundArray = new JsonArray();
                for (JsonElement soundEl : soundsArr.getAsJsonArray()) {
                    String soundRef = extractSoundRef(soundEl);
                    if (soundRef == null) continue;
                    String copied = copySoundFile(mod, modId, soundRef, packRoot);
                    if (copied != null) {
                        bedrockSoundArray.add(copied);
                        totalCopied++;
                    }
                }
                if (bedrockSoundArray.isEmpty()) continue;

                String fullEventId = modId + ":" + eventName;
                if (!seenEvents.add(fullEventId)) continue;
                JsonObject defObj = new JsonObject();
                defObj.addProperty("category", inferCategory(eventName));
                defObj.add("sounds", bedrockSoundArray);
                soundDefinitions.add(fullEventId, defObj);
            }
        }

        if (soundDefinitions.size() == 0) {
            LOGGER.debug("No mod sounds found — skipping sound_definitions.json");
            return;
        }

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.20.20");
        root.add("sound_definitions", soundDefinitions);
        writeJsonToFile(root, soundsDir.resolve("sound_definitions.json").toFile());

        LOGGER.info("Emitted {} sound definitions ({} ogg files copied)",
            soundDefinitions.size(), totalCopied);
    }

    /** Extracts a "namespace:path" reference from either string-form or object-form sounds[] entries. */
    private static String extractSoundRef(JsonElement el) {
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonObject()) {
            JsonElement name = el.getAsJsonObject().get("name");
            if (name != null && name.isJsonPrimitive()) return name.getAsString();
        }
        return null;
    }

    /**
     * Copies the .ogg file backing a sound reference into the Bedrock pack at
     * {@code sounds/<ns>/<path>.ogg} and returns the bedrock-side relative path
     * (without extension), suitable for the {@code "sounds": [...]} array of a
     * sound definition.
     */
    private static String copySoundFile(ModContainer mod, String modId, String soundRef, Path packRoot) {
        // Resolve namespace and path from the reference.
        String ns, relPath;
        int colon = soundRef.indexOf(':');
        if (colon >= 0) {
            ns = soundRef.substring(0, colon);
            relPath = soundRef.substring(colon + 1);
        } else {
            ns = modId;
            relPath = soundRef;
        }

        // Source on the mod's classpath.
        String sourcePath = "assets/" + ns + "/sounds/" + relPath + ".ogg";

        // Try the owning mod first; if the asset lives in another mod (cross-namespace
        // refs are rare but legal), fall back to the classloader.
        InputStream stream = null;
        java.nio.file.Path foundPath = mod.findPath(sourcePath).orElse(null);
        try {
            if (foundPath != null) {
                stream = Files.newInputStream(foundPath);
            } else {
                stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(sourcePath);
            }
            if (stream == null) {
                LOGGER.debug("Couldn't locate ogg for {}: {}", soundRef, sourcePath);
                return null;
            }

            String bedrockRel = "sounds/" + ns + "/" + relPath + ".ogg";
            Path destination = packRoot.resolve(bedrockRel);
            Files.createDirectories(destination.getParent());
            try (InputStream s = stream) {
                Files.copy(s, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // Bedrock's "sounds" array entries omit the .ogg extension.
            return "sounds/" + ns + "/" + relPath;
        } catch (IOException e) {
            LOGGER.debug("Couldn't copy ogg {}: {}", soundRef, e.getMessage());
            return null;
        }
    }

    /**
     * Heuristic mapping from event name to a Bedrock sound category. The category mostly
     * affects in-game volume sliders. "neutral" is a safe default.
     */
    private static String inferCategory(String eventName) {
        String lower = eventName.toLowerCase();
        if (lower.startsWith("music_disc") || lower.startsWith("music.")) return "record";
        if (lower.startsWith("music")) return "music";
        if (lower.contains("ambient") || lower.startsWith("weather")) return "ambient";
        if (lower.startsWith("block.") || lower.contains("step") || lower.contains("dig")) return "block";
        if (lower.startsWith("entity.") || lower.contains("mob")) return "neutral";
        if (lower.startsWith("ui.") || lower.startsWith("item.")) return "ui";
        return "neutral";
    }
}
