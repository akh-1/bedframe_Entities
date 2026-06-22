package lol.sylvie.bedframe.geyser.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static lol.sylvie.bedframe.util.BedframeConstants.LOGGER;

/**
 * Converts FactoryTools / emuvanilla DECLARATIVE animations (vanilla-style AnimationDefinition:
 * keyframe channels per bone) into Bedrock .animation.json clips - the FactoryTools analogue of
 * BbAnimationConverter. This is fully generic/data-driven: it reflects a PolyModelInstance's model
 * for Animation / AnimationDefinition fields and converts whatever keyframes it finds, so any mob's
 * declarative clips (eat_grass, attack, ...) come through without per-mod code.
 *
 * Structure (read from the FactoryTools jar):
 *   AnimationDefinition { float lengthInSeconds; boolean looping; Map<String,List<Transformation>> boneAnimations }
 *   Transformation      { Target target (MOVE_ORIGIN|ROTATE|SCALE); Keyframe[] keyframes }
 *   Keyframe            { float timestamp; Vector3f target; Interpolation interpolation (LINEAR|CUBIC) }
 *
 * ROTATE values are radians (AnimationHelper.createRotationalVector) -> converted to Bedrock degrees
 * with the same X/Y flip the geometry uses ([-x,-y,z]). Bone names are bf_ prefixed to match the geo.
 *
 * NOTE: situational clips (eat_grass, attack) are emitted into the pack but need a state signal to be
 * TRIGGERED on Bedrock (the carrier is a vanilla mob; Geyser has no "is eating" flag). Locomotion is
 * handled separately by ProceduralAnimations via movement Molang. This converter makes the clips exist
 * and be wired into the entity; triggering situational ones is a later step (entity-property/controller).
 */
public final class EmuAnimationConverter {
    private EmuAnimationConverter() {}

    public record Converted(String animationsJson, List<String> clips) {}

    /** Reflect the instance's model for declarative animations and convert them. Null if none found. */
    public static Converted fromModel(String ns, String path, Object polyModelInstance) {
        Object model;
        try {
            model = field(polyModelInstance, "model");          // emuvanilla EntityModel
        } catch (Throwable t) {
            return null;
        }
        if (model == null) return null;

        JsonObject clipsById = new JsonObject();
        List<String> clipNames = new ArrayList<>();

        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(model);
                    if (val == null) continue;
                    Object def = asAnimationDefinition(val);
                    if (def == null) continue;

                    String clipName = camelToSnake(stripSuffix(f.getName(), "Animation"));
                    String clipId = "animation.bedframe." + ns + "." + path + "." + clipName;
                    JsonObject clip = convertDefinition(def);
                    if (clip == null) continue;
                    clipsById.add(clipId, clip);
                    clipNames.add(clipName);
                } catch (Throwable t) {
                    LOGGER.warn("[bedframe] Emu animation field {} failed: {}", f.getName(), t.toString());
                }
            }
        }

        if (clipNames.isEmpty()) return null;
        JsonObject out = new JsonObject();
        out.addProperty("format_version", "1.8.0");
        out.add("animations", clipsById);
        return new Converted(out.toString(), clipNames);
    }

    /** Accept either an emuvanilla Animation (unwrap .definition) or an AnimationDefinition directly. */
    private static Object asAnimationDefinition(Object val) throws ReflectiveOperationException {
        String cn = val.getClass().getName();
        if (!cn.contains("emuvanilla")) return null;
        String simple = val.getClass().getSimpleName();
        if (simple.equals("AnimationDefinition")) return val;
        if (simple.equals("Animation")) {
            try { return field(val, "definition"); } catch (NoSuchFieldException e) { return null; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static JsonObject convertDefinition(Object def) throws ReflectiveOperationException {
        float length = ((Number) field(def, "lengthInSeconds")).floatValue();
        boolean looping = (Boolean) field(def, "looping");
        Map<String, Object> boneAnimations = (Map<String, Object>) field(def, "boneAnimations");
        if (boneAnimations == null || boneAnimations.isEmpty()) return null;

        JsonObject bones = new JsonObject();
        for (Map.Entry<String, Object> e : boneAnimations.entrySet()) {
            JsonObject channels = new JsonObject();
            for (Object transform : iterate(e.getValue())) {
                Object target = field(transform, "target");
                Object[] keyframes = (Object[]) field(transform, "keyframes");
                String channel = channelName(target);
                if (channel == null || keyframes.length == 0) continue;

                JsonObject frames = new JsonObject();
                for (Object kf : keyframes) {
                    float time = ((Number) field(kf, "timestamp")).floatValue();
                    Object vec = field(kf, "target");                       // joml Vector3f
                    double x = vec(vec, "x"), y = vec(vec, "y"), z = vec(vec, "z");
                    JsonArray value = channelValue(channel, x, y, z);
                    frames.add(trimFloat(time), wrapKeyframe(value, isCubic(field(kf, "interpolation"))));
                }
                channels.add(channel, frames);
            }
            if (channels.size() > 0) bones.add("bf_" + e.getKey(), channels);
        }
        if (bones.size() == 0) return null;

        JsonObject clip = new JsonObject();
        clip.addProperty("loop", looping);
        clip.addProperty("animation_length", length);
        clip.add("bones", bones);
        return clip;
    }

    // ROTATE -> "rotation" (rad->deg, [-x,-y,z]); MOVE_ORIGIN -> "position" ([-x,-y,z]); SCALE -> "scale".
    private static String channelName(Object target) {
        String s = String.valueOf(target).toUpperCase();
        if (s.contains("ROTATE")) return "rotation";
        if (s.contains("MOVE") || s.contains("ORIGIN") || s.contains("TRANSLAT")) return "position";
        if (s.contains("SCALE")) return "scale";
        return null;
    }

    private static JsonArray channelValue(String channel, double x, double y, double z) {
        JsonArray a = new JsonArray();
        if (channel.equals("rotation")) {
            double d = 180.0 / Math.PI;
            a.add(round(-x * d)); a.add(round(-y * d)); a.add(round(z * d));
        } else if (channel.equals("position")) {
            a.add(round(-x)); a.add(round(-y)); a.add(round(z));
        } else {                                   // scale
            a.add(round(x)); a.add(round(y)); a.add(round(z));
        }
        return a;
    }

    /** Bedrock keyframe: bare array for linear, {pre/post + catmullrom} for cubic. */
    private static com.google.gson.JsonElement wrapKeyframe(JsonArray value, boolean cubic) {
        if (!cubic) return value;
        JsonObject o = new JsonObject();
        o.add("post", value);
        o.addProperty("lerp_mode", "catmullrom");
        return o;
    }

    private static boolean isCubic(Object interpolation) {
        return String.valueOf(interpolation).toUpperCase().contains("CUBIC");
    }

    private static Iterable<Object> iterate(Object value) {
        List<Object> out = new ArrayList<>();
        if (value instanceof Object[] arr) { for (Object o : arr) out.add(o); }
        else if (value instanceof Iterable<?> it) { for (Object o : it) out.add(o); }
        else if (value != null) out.add(value);
        return out;
    }

    // ---- small helpers (mirrors EmuModelConverter style) ----

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        Field f = findField(o.getClass(), name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static double vec(Object v, String comp) throws ReflectiveOperationException {
        return ((Number) v.getClass().getField(comp).get(v)).doubleValue();
    }

    private static double round(double d) { return Math.round(d * 1000.0) / 1000.0; }

    private static String trimFloat(float f) {
        String s = Float.toString(Math.round(f * 10000f) / 10000f);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String stripSuffix(String s, String suffix) {
        return s.toLowerCase().endsWith(suffix.toLowerCase()) ? s.substring(0, s.length() - suffix.length()) : s;
    }

    private static String camelToSnake(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isUpperCase(ch)) { if (b.length() > 0) b.append('_'); b.append(Character.toLowerCase(ch)); }
            else b.append(ch);
        }
        String out = b.toString();
        return out.isEmpty() ? "anim" : out;
    }
}
