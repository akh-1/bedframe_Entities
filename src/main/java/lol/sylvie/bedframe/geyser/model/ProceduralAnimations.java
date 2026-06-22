package lol.sylvie.bedframe.geyser.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;

/**
 * Hand-ported procedural animations for mods whose entity animation is Java code (a vanilla-style
 * {@code setupAnim} using sin/cos over walkAnimation + tickCount), not Blockbench keyframe data.
 * These cannot be auto-converted like Tom's Mobs; each model's math is translated to Molang.
 *
 * The output is a normal Bedrock .animation.json with ONE looping clip ("proc") whose bone
 * rotations are Molang EXPRESSIONS (re-evaluated every frame) rather than time keyframes. That
 * single query-driven clip reproduces idle + walk (+ air flap) at once, so the EntityTranslator
 * wires it as a single always-on, variant-gated controller.
 *
 * <h3>Java -> Molang conversion rules</h3>
 * <ul>
 *   <li>Java {@code Mth.cos/sin} take RADIANS; Molang {@code math.cos/sin} take DEGREES, so a
 *       Java argument {@code x} (radians) becomes {@code x * 57.2958} degrees. The vanilla leg
 *       factor {@code 0.6662 rad} therefore becomes {@code 38.17} (= 0.6662 * 180/pi), matching
 *       vanilla Bedrock's own walk factor.</li>
 *   <li>Rotation amounts authored in radians become degrees: {@code 0.8 rad -> 45.84},
 *       {@code 1.4 rad -> 80.21}, {@code 0.2094395 rad -> 12.0}, {@code PI/2 - PI/12 -> 75}.</li>
 *   <li>{@code limbSwing} (walkAnimation.position) -> {@code query.modified_distance_moved};
 *       {@code limbSwingAmount} (walkAnimation.speed, clamped) -> {@code math.clamp(query.modified_move_speed,0,1)};
 *       {@code !onGround} -> {@code !query.is_on_ground}.</li>
 *   <li>The emu geometry pipeline maps bone rotation {@code [x,y,z] -> [-x,-y,z]} (see
 *       EmuModelConverter), so each ported rotation negates X and Y to land in Bedrock space -
 *       the same convention the validated bind-pose rotations use.</li>
 * </ul>
 */
public final class ProceduralAnimations {
    private ProceduralAnimations() {}

    /** The single clip name used for procedural animations. Not idle/walk/run/swim/fly on purpose,
     *  so EntityTranslator wires it as one always-on state instead of a speed state machine. */
    public static final List<String> CLIPS = List.of("proc");

    // Reusable Molang fragments.
    private static final String AMT   = "math.clamp(query.modified_move_speed, 0, 1)";
    private static final String COS   = "math.cos(query.modified_distance_moved * 38.17)";
    private static final String COS180 = "math.cos(query.modified_distance_moved * 38.17 + 180)";

    /**
     * Generic locomotion driven purely by bone NAMES - no per-model code. Any bone whose name
     * contains "leg" is swung on its pitch; the phase is inferred so a quadruple's diagonal gait
     * falls out automatically:
     *   - named legs (front/hind/back + left/right): phase A when (isFront == isLeft), else B
     *     -> (front-left, hind-right) vs (front-right, hind-left), the standard diagonal gait;
     *   - biped (left/right only): left vs right;
     *   - numbered/unknown legs: alternate by encounter order.
     * Returns null when nothing leg-like is found, so such mobs just register static.
     *
     * This reproduces e.g. the deer's exact gait from its bf_*_leg bones, and covers any future
     * mob with conventionally named legs without a bespoke method.
     */
    public static String genericWalk(String ns, String path, java.util.List<String> boneNames) {
        JsonObject bones = new JsonObject();
        String swingA = "-(57.3 * " + COS + " * " + AMT + ")";
        String swingB = "-(57.3 * " + COS180 + " * " + AMT + ")";
        int unknownLeg = 0;
        for (String bone : boneNames) {
            String n = bone.toLowerCase();
            if (!n.contains("leg")) continue;
            boolean hasSide = n.contains("left") || n.contains("right");
            boolean hasEnd  = n.contains("front") || n.contains("hind") || n.contains("back") || n.contains("rear");
            boolean phaseA;
            if (hasSide && hasEnd)      phaseA = (n.contains("front") == n.contains("left"));
            else if (hasSide)          phaseA = n.contains("left");
            else                       phaseA = (unknownLeg++ % 2 == 0);
            bones.add(bone, rot(phaseA ? swingA : swingB, 0, 0));
        }
        if (bones.size() == 0) return null;
        return wrap(ns, path, bones);
    }

    private static JsonObject rot(Object x, Object y, Object z) {
        JsonObject channel = new JsonObject();
        JsonArray a = new JsonArray();
        a.add(prim(x)); a.add(prim(y)); a.add(prim(z));
        channel.add("rotation", a);
        return channel;
    }

    private static JsonPrimitive prim(Object o) {
        return (o instanceof Number n) ? new JsonPrimitive(n) : new JsonPrimitive(String.valueOf(o));
    }

    /** Wrap a bones object into a looping clip body. */
    private static JsonObject clip(JsonObject bones) {
        JsonObject c = new JsonObject();
        c.addProperty("loop", true);
        c.addProperty("animation_length", 1.0);
        c.add("bones", bones);
        return c;
    }

    /** A complete .animation.json file from a map of animation-id -> clip body. */
    private static String file(JsonObject clipsById) {
        JsonObject out = new JsonObject();
        out.addProperty("format_version", "1.8.0");
        out.add("animations", clipsById);
        return out.toString();
    }

    /** Single-clip file using the standard "proc" clip name. */
    private static String wrap(String ns, String path, JsonObject bones) {
        JsonObject clips = new JsonObject();
        clips.add("animation.bedframe." + ns + "." + path + ".proc", clip(bones));
        return file(clips);
    }
}
