package lol.sylvie.bedframe.geyser.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts the {@code animations} array of a Blockbench .bbmodel into a Bedrock
 * .animation.json. Sibling to {@link BbModelConverter} (which handles geometry).
 *
 * Why this mirrors BbModelConverter's axis math:
 * the geometry converter puts the model into Bedrock space with bone rotation
 * {@code [-x, -y, z]} and origins flipped on X ({@code [-x, y, z]}). Animation channels
 * move those already-flipped bones, so they MUST apply the same flips or the motion goes
 * the wrong way:
 *   rotation keyframe [x,y,z] -> [-x, -y, z]
 *   position keyframe [x,y,z] -> [-x,  y, z]
 *   scale    keyframe [x,y,z] -> [ x,  y, z]   (unchanged)
 *
 * Bones are prefixed {@code bf_} to match the converted geometry (see BbModelConverter,
 * the bf_ prefix keeps the carrier's vanilla animations from binding to custom bones).
 *
 * Animation ids are deterministic: {@code animation.bedframe.<namespace>.<path>.<clip>}
 * so {@link lol.sylvie.bedframe.geyser.translator.EntityTranslator} can reference them
 * without the converter having to know the runtime variant index.
 */
public final class BbAnimationConverter {

    /**
     * @param animationsJson the full .animation.json content (format_version 1.8.0)
     * @param clips          ordered clip base names that were emitted (e.g. idle, walk, run, death)
     * @param idPrefix       the shared id prefix, "animation.bedframe.<ns>.<path>"
     */
    public record Converted(String animationsJson, List<String> clips, String idPrefix) {}

    private BbAnimationConverter() {}

    /** Returns null if the model declares no animations. */
    public static Converted convert(String namespace, String path, JsonObject bbmodelRoot) {
        if (!bbmodelRoot.has("animations") || !bbmodelRoot.get("animations").isJsonArray()) return null;
        JsonArray anims = bbmodelRoot.getAsJsonArray("animations");
        if (anims.size() == 0) return null;

        // Resolve animator -> bone name the SAME way BbModelConverter names geometry bones, keyed by
        // the bone's uuid (which is the animator's key). Some Tom's models (e.g. possum) ship outliner
        // groups with NO name, so geometry names them "bone_<uuid>" while the animator still carries a
        // display name like "head" - using that display name would target a bone that doesn't exist and
        // the animation would silently do nothing. Looking up by uuid guarantees the names match.
        Map<String, String> boneByUuid = new java.util.HashMap<>();
        if (bbmodelRoot.has("outliner")) {
            for (JsonElement node : bbmodelRoot.getAsJsonArray("outliner")) collectBones(node, boneByUuid);
        }
        java.util.Set<String> geomBoneNames = new java.util.HashSet<>(boneByUuid.values());

        String idPrefix = "animation.bedframe." + namespace + "." + path;

        JsonObject animationsMap = new JsonObject();
        List<String> clips = new ArrayList<>();

        for (JsonElement ae : anims) {
            if (!ae.isJsonObject()) continue;
            JsonObject anim = ae.getAsJsonObject();
            String clip = anim.has("name") ? anim.get("name").getAsString() : null;
            if (clip == null || clip.isBlank()) continue;

            JsonObject bedrockAnim = convertClip(anim, boneByUuid, geomBoneNames);
            if (bedrockAnim == null) continue;

            animationsMap.add(idPrefix + "." + clip, bedrockAnim);
            clips.add(clip);
        }

        if (clips.isEmpty()) return null;

        JsonObject out = new JsonObject();
        out.addProperty("format_version", "1.8.0");
        out.add("animations", animationsMap);
        return new Converted(out.toString(), clips, idPrefix);
    }

    /** Walk the outliner building uuid -> bone name, matching BbModelConverter.boneName(). */
    private static void collectBones(JsonElement node, Map<String, String> out) {
        if (!node.isJsonObject()) return;
        JsonObject group = node.getAsJsonObject();
        String name;
        if (group.has("name") && group.get("name").isJsonPrimitive()) {
            name = group.get("name").getAsString();
        } else if (group.has("uuid")) {
            name = "bone_" + group.get("uuid").getAsString().replace("-", "");
        } else {
            name = "bone";
        }
        if (group.has("uuid")) out.put(group.get("uuid").getAsString(), name);
        if (group.has("children")) {
            for (JsonElement c : group.getAsJsonArray("children")) collectBones(c, out);
        }
    }

    // ----------------------------------------------------------------------

    private static JsonObject convertClip(JsonObject anim, Map<String, String> boneByUuid,
                                          java.util.Set<String> geomBoneNames) {
        JsonObject body = new JsonObject();

        String loop = anim.has("loop") ? anim.get("loop").getAsString() : "once";
        switch (loop) {
            case "loop" -> body.addProperty("loop", true);
            case "hold_on_last_frame" -> body.addProperty("loop", "hold_on_last_pose");
            default -> { /* "once": Bedrock default, omit */ }
        }
        if (anim.has("length")) body.addProperty("animation_length", anim.get("length").getAsDouble());

        if (!anim.has("animators") || !anim.get("animators").isJsonObject()) return body;
        JsonObject animators = anim.getAsJsonObject("animators");

        JsonObject bones = new JsonObject();
        for (Map.Entry<String, JsonElement> e : animators.entrySet()) {
            JsonObject animator = e.getValue().getAsJsonObject();
            // Only effect-bone (model) animators have a usable name; skip sound/particle/effect tracks.
            String type = animator.has("type") ? animator.get("type").getAsString() : "bone";
            if (!"bone".equals(type)) continue;

            // Prefer uuid-keyed lookup (always matches geometry); fall back to the animator's display
            // name only if it actually exists as a geometry bone; otherwise the track is an orphan
            // (e.g. partridge "tailWing") that binds to nothing - skip it.
            String resolved = boneByUuid.get(e.getKey());
            if (resolved == null) {
                String displayName = animator.has("name") ? animator.get("name").getAsString() : null;
                if (displayName != null && geomBoneNames.contains(displayName)) resolved = displayName;
            }
            if (resolved == null) continue;

            String boneName = "bf_" + resolved;
            JsonObject boneChannels = convertBone(animator);
            if (boneChannels.size() > 0) bones.add(boneName, boneChannels);
        }
        if (bones.size() > 0) body.add("bones", bones);
        return body;
    }

    /** One animator -> { "rotation": {...}, "position": {...} }, grouped by channel. */
    private static JsonObject convertBone(JsonObject animator) {
        // channel -> (time -> value element), sorted by time
        Map<String, java.util.TreeMap<Double, JsonElement>> byChannel = new LinkedHashMap<>();

        if (!animator.has("keyframes")) return new JsonObject();
        for (JsonElement ke : animator.getAsJsonArray("keyframes")) {
            JsonObject kf = ke.getAsJsonObject();
            String channel = kf.has("channel") ? kf.get("channel").getAsString() : null;
            if (channel == null) continue;
            if (!"rotation".equals(channel) && !"position".equals(channel) && !"scale".equals(channel)) continue;

            double time = kf.has("time") ? kf.get("time").getAsDouble() : 0.0;
            String interp = kf.has("interpolation") ? kf.get("interpolation").getAsString() : "linear";

            JsonArray dp = kf.getAsJsonArray("data_points");
            if (dp == null || dp.size() == 0) continue;
            JsonObject p = dp.get(0).getAsJsonObject();

            JsonElement value = buildValue(channel, p, interp);
            byChannel.computeIfAbsent(channel, k -> new java.util.TreeMap<>()).put(time, value);
        }

        JsonObject boneOut = new JsonObject();
        for (Map.Entry<String, java.util.TreeMap<Double, JsonElement>> ce : byChannel.entrySet()) {
            JsonObject frames = new JsonObject();
            for (Map.Entry<Double, JsonElement> fe : ce.getValue().entrySet()) {
                frames.add(timeKey(fe.getKey()), fe.getValue());
            }
            boneOut.add(ce.getKey(), frames);
        }
        return boneOut;
    }

    /** Apply axis flips for the channel, returning either a [x,y,z] array (linear) or
     *  a { "post": [x,y,z], "lerp_mode": "catmullrom" } object (smooth). */
    private static JsonElement buildValue(String channel, JsonObject p, String interp) {
        JsonElement x = flipAxis(p.get("x"), channel.equals("rotation") || channel.equals("position")); // negate X
        JsonElement y = flipAxis(p.get("y"), channel.equals("rotation"));                                // negate Y only for rotation
        JsonElement z = flipAxis(p.get("z"), false);                                                     // Z unchanged

        JsonArray vec = new JsonArray();
        vec.add(x); vec.add(y); vec.add(z);

        if ("catmullrom".equals(interp)) {
            JsonObject smooth = new JsonObject();
            smooth.add("post", vec);
            smooth.addProperty("lerp_mode", "catmullrom");
            return smooth;
        }
        return vec;
    }

    /** Numeric data points -> number (optionally negated). Molang-string data points ->
     *  string (negation wraps the expression). Missing axis -> 0. */
    private static JsonElement flipAxis(JsonElement raw, boolean negate) {
        if (raw == null || raw.isJsonNull()) return prim(0.0, negate);
        String s = raw.getAsString();
        try {
            double v = Double.parseDouble(s);
            return prim(v, negate);
        } catch (NumberFormatException notNumber) {
            // genuine Molang expression
            String expr = negate ? "-(" + s + ")" : s;
            return new com.google.gson.JsonPrimitive(expr);
        }
    }

    private static JsonElement prim(double v, boolean negate) {
        double out = negate ? -v : v;
        // collapse -0.0
        if (out == 0.0) out = 0.0;
        return new com.google.gson.JsonPrimitive(out);
    }

    private static String timeKey(double t) {
        if (t == Math.floor(t) && !Double.isInfinite(t)) return Integer.toString((int) t) + ".0";
        return Double.toString(t);
    }
}
