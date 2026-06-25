package lol.sylvie.bedframe.geyser.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static lol.sylvie.bedframe.util.BedframeConstants.LOGGER;

/**
 * Reads user-supplied custom animations from disk and layers them on top of whatever Bedframe
 * generated automatically (procedural walk/swim + converted declaratives). This is the escape hatch
 * for anything the generic heuristics can't infer - bespoke idles, fly cycles, attacks, or replacing
 * the auto walk with a hand-tuned one - WITHOUT recompiling the mod.
 *
 * Layout: one file per mob at
 *     config/bedframe/animations/&lt;namespace&gt;/&lt;entity&gt;.animation.json
 * e.g. config/bedframe/animations/deermod/deer.animation.json applies to entity "deermod:deer".
 *
 * The file is a normal Bedrock .animation.json. Clips merge over the generated ones (override wins on
 * matching id), so naming a clip "animation.bedframe.&lt;ns&gt;.&lt;entity&gt;.walk" replaces the auto
 * walk. Clip ids ending in idle/walk/swim/run/fly get wired to the movement state machine; any other
 * clip is emitted but needs its own trigger.
 */
public final class AnimationOverrideHub {
    private AnimationOverrideHub() {}

    private static final Path DIR =
            FabricLoader.getInstance().getConfigDir().resolve("bedframe").resolve("animations");

    public record Result(String animationsJson, List<String> clips) {}

    /** Layer the on-disk override (if any) over the generated animations for this entity. */
    public static Result apply(Identifier typeId, String baseJson, List<String> baseClips) {
        List<String> clips = new ArrayList<>(baseClips == null ? List.of() : baseClips);
        Path file = DIR.resolve(typeId.getNamespace()).resolve(typeId.getPath() + ".animation.json");
        if (!Files.isRegularFile(file)) return new Result(baseJson, clips);

        try {
            String overrideJson = Files.readString(file);
            JsonObject root = JsonParser.parseString(overrideJson).getAsJsonObject();
            JsonObject anims = root.getAsJsonObject("animations");
            if (anims == null) return new Result(baseJson, clips);

            for (String id : anims.keySet()) {
                String shortName = id.substring(id.lastIndexOf('.') + 1);
                if (!clips.contains(shortName)) clips.add(shortName);
            }
            String merged = mergeAnimations(baseJson, overrideJson);   // override wins on collision
            LOGGER.info("[bedframe] Applied animation override for {} ({} clip(s))", typeId, anims.size());
            return new Result(merged, clips);
        } catch (Exception e) {
            LOGGER.warn("[bedframe] Failed to read animation override {}: {}", file, e.toString());
            return new Result(baseJson, clips);
        }
    }

    /** Merge two .animation.json strings into one; entries in {@code b} overwrite those in {@code a}. */
    public static String mergeAnimations(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        try {
            JsonObject ra = JsonParser.parseString(a).getAsJsonObject();
            JsonObject rb = JsonParser.parseString(b).getAsJsonObject();
            JsonObject anims = ra.getAsJsonObject("animations");
            for (Map.Entry<String, com.google.gson.JsonElement> e : rb.getAsJsonObject("animations").entrySet())
                anims.add(e.getKey(), e.getValue());
            return ra.toString();
        } catch (Exception e) {
            return a;
        }
    }

    /**
     * Short content hash of every override .json in this folder (path + bytes, sorted for
     * determinism). PackGenerator folds this into the pack UUID so that ANY edit/add/remove of an
     * override file makes Bedrock re-download the pack on the next restart - and an unchanged folder
     * keeps the same hash, so there's no spurious re-download. Returns "" when there's nothing here.
     */
    public static String contentHash() {
        if (!Files.isDirectory(DIR)) return "";
        try (Stream<Path> walk = Files.walk(DIR)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted().toList();
            if (files.isEmpty()) return "";
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (Path f : files) {
                md.update(DIR.relativize(f).toString().getBytes(StandardCharsets.UTF_8));
                md.update(Files.readAllBytes(f));
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i] & 0xff));  // 12 hex chars
            return sb.toString();
        } catch (Exception e) {
            LOGGER.warn("[bedframe] Failed to hash animation overrides: {}", e.toString());
            return "";
        }
    }

    /** Create the folder + a README and example file the first time, so users discover the feature. */
    public static void ensureScaffold() {
        try {
            if (Files.exists(DIR)) return;
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve("README.md"), README);
            Path ex = DIR.resolve("examplemod");
            Files.createDirectories(ex);
            Files.writeString(ex.resolve("example_mob.animation.json"), EXAMPLE);
            LOGGER.info("[bedframe] Created animation override folder at {}", DIR);
        } catch (Exception e) {
            LOGGER.warn("[bedframe] Could not create animation override scaffold: {}", e.toString());
        }
    }

    private static final String README = """
            # Bedframe — Custom animations

            Drop your own Bedrock animations here to override or extend what Bedframe generates
            automatically. No recompiling needed.

            ## Where files go
            One file per mob:

                config/bedframe/animations/<namespace>/<entity>.animation.json

            Example: `config/bedframe/animations/deermod/deer.animation.json` targets the entity
            `deermod:deer`. The folder/file names ARE the entity id.

            ## File format
            A normal Bedrock `.animation.json` (the same thing Blockbench exports). Important rules:

            - **Bone names are prefixed with `bf_`.** Bedframe renames every bone to `bf_<original>`
              to avoid colliding with the vanilla carrier's skeleton, so target `bf_head`, `bf_tail`,
              `bf_leg0`, etc. Open the generated geometry in the Bedrock pack to see the exact names.
            - **Clip ids** look like `animation.bedframe.<ns>.<entity>.<clip>`. The last segment is the
              clip name.

            ## How clips are used
            - A clip whose name is `idle`, `walk`, `swim`, `run` or `fly` is wired into the movement
              state machine automatically (plays based on speed / in-water / on-ground).
            - Any other clip name is included in the pack but needs its own trigger to play.
            - If your clip id exactly matches a generated one (e.g. `...walk`), yours **replaces** it.
              That's how you swap the auto-generated procedural walk for a hand-made one.

            ## Applying changes
            Just edit, add or remove files and restart the server. Bedframe hashes this folder into the
            pack's UUID, so any change makes Bedrock re-download the pack automatically on the next join
            - no config edits and no version bump needed. If nothing here changed, the UUID stays the
            same and there's no re-download.

            See `examplemod/example_mob.animation.json` for a working sample.
            """;

    private static final String EXAMPLE = """
            {
              "format_version": "1.8.0",
              "animations": {
                "animation.bedframe.examplemod.example_mob.walk": {
                  "loop": true,
                  "animation_length": 1.0,
                  "bones": {
                    "bf_leg0": { "rotation": ["-(40 * math.cos(query.modified_distance_moved * 38.17) * math.clamp(query.modified_move_speed, 0, 1))", 0, 0] },
                    "bf_leg1": { "rotation": ["-(40 * math.cos(query.modified_distance_moved * 38.17 + 180) * math.clamp(query.modified_move_speed, 0, 1))", 0, 0] }
                  }
                },
                "animation.bedframe.examplemod.example_mob.idle": {
                  "loop": true,
                  "animation_length": 2.0,
                  "bones": {
                    "bf_head": { "rotation": [0, "8 * math.sin(query.life_time * 60)", 0] }
                  }
                }
              }
            }
            """;
}
