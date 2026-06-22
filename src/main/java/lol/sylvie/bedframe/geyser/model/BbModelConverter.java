package lol.sylvie.bedframe.geyser.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts a Blockbench .bbmodel into Bedrock entity geometry + texture.
 *
 * Port of the validated reference (bbconv.py). Blockbench is the Bedrock model editor,
 * so the conversion is mostly 1:1; the quirks handled here are the X-axis flip on
 * origins/pivots and the X/Y rotation inversion, plus per-face UV.
 *
 * Animations are NOT handled here yet (separate step). The model renders in bind pose.
 */
public final class BbModelConverter {

    public record Converted(String geometryId, String geometryJson, byte[] texturePng) {}

    private BbModelConverter() {}

    public static Converted convert(String namespace, String path, InputStream bbmodelStream) {
        JsonObject root = JsonParser.parseReader(
                new InputStreamReader(bbmodelStream, StandardCharsets.UTF_8)).getAsJsonObject();

        int texW = 64, texH = 64;
        if (root.has("resolution")) {
            JsonObject res = root.getAsJsonObject("resolution");
            texW = res.get("width").getAsInt();
            texH = res.get("height").getAsInt();
        }
        // Blockbench stores per-face UVs in the TEXTURE's own uv space, which can be larger than
        // the project "resolution" (e.g. Tom's brown/red squirrel: resolution 16 but texture 64;
        // tiger: 32 vs 128; elephant: 128 vs 256). Keying texture_width/height off resolution alone
        // rescales those UVs so only a corner of the texture lands on the model. Take the larger of
        // the two: when uv_width <= resolution (e.g. box_uv models) this is a no-op, leaving models
        // that already render correctly untouched.
        if (root.has("textures")) {
            JsonArray texs = root.getAsJsonArray("textures");
            if (texs.size() > 0 && texs.get(0).isJsonObject()) {
                JsonObject t0 = texs.get(0).getAsJsonObject();
                if (t0.has("uv_width"))  texW = Math.max(texW, t0.get("uv_width").getAsInt());
                if (t0.has("uv_height")) texH = Math.max(texH, t0.get("uv_height").getAsInt());
            }
        }

        // index cube elements by uuid
        Map<String, JsonObject> elements = new HashMap<>();
        if (root.has("elements")) {
            for (JsonElement el : root.getAsJsonArray("elements")) {
                JsonObject e = el.getAsJsonObject();
                String type = e.has("type") ? e.get("type").getAsString() : "cube";
                if (!"cube".equals(type)) continue;
                // Skip elements hidden in Blockbench (e.g. BIL's reference/hitbox box),
                // otherwise they render as an extra cube covering the model.
                if (e.has("visibility") && !e.get("visibility").getAsBoolean()) continue;
                // Some reference boxes aren't marked invisible but have no texture on any face
                // (e.g. Tom's budgie). They'd render as a solid untextured cube over the model,
                // so skip any cube with zero textured faces - real model cubes always have one.
                if (!hasTexturedFace(e)) continue;
                if (e.has("uuid")) elements.put(e.get("uuid").getAsString(), e);
            }
        }

        JsonArray bones = new JsonArray();
        if (root.has("outliner")) {
            for (JsonElement node : root.getAsJsonArray("outliner")) {
                walk(node, null, elements, bones);
            }
        }

        String geometryId = "geometry." + namespace + "." + path;
        JsonObject description = new JsonObject();
        description.addProperty("identifier", geometryId);
        description.addProperty("texture_width", texW);
        description.addProperty("texture_height", texH);
        description.addProperty("visible_bounds_width", 4);
        description.addProperty("visible_bounds_height", 4);
        JsonArray vbo = new JsonArray();
        vbo.add(0); vbo.add(1); vbo.add(0);
        description.add("visible_bounds_offset", vbo);

        JsonObject geo = new JsonObject();
        geo.add("description", description);
        geo.add("bones", bones);
        JsonArray geoArr = new JsonArray();
        geoArr.add(geo);

        JsonObject out = new JsonObject();
        out.addProperty("format_version", "1.12.0");
        out.add("minecraft:geometry", geoArr);

        byte[] texture = extractTexture(root);

        return new Converted(geometryId, out.toString(), texture);
    }

    // ----------------------------------------------------------------------

    private static void walk(JsonElement node, String parent,
                             Map<String, JsonObject> elements, JsonArray bones) {
        if (!node.isJsonObject()) return;            // bare cube uuid handled by its parent bone
        JsonObject group = node.getAsJsonObject();

        JsonObject bone = new JsonObject();
        // Prefix every bone with bf_ so the polar_bear carrier's Bedrock animations (which target
        // bones named body/head/leg0..3) never bind to a converted mob. Tom's raccoon has a bone
        // literally named "body", so without this the bear's body animation yanks its torso out of
        // place. Renaming is render-neutral; only animation binding is affected.
        String name = "bf_" + boneName(group);
        bone.addProperty("name", name);
        bone.add("pivot", flipX(arr(group, "origin")));
        if (parent != null) bone.addProperty("parent", parent);

        double[] rot = optArr(group, "rotation");
        if (rot != null && (rot[0] != 0 || rot[1] != 0 || rot[2] != 0)) {
            JsonArray r = new JsonArray();
            r.add(-rot[0]); r.add(-rot[1]); r.add(rot[2]);
            bone.add("rotation", r);
        }

        JsonArray cubes = new JsonArray();
        if (group.has("children")) {
            for (JsonElement child : group.getAsJsonArray("children")) {
                if (child.isJsonPrimitive()) {
                    JsonObject e = elements.get(child.getAsString());
                    if (e != null) cubes.add(makeCube(e));
                } else {
                    walk(child, name, elements, bones);
                }
            }
        }
        if (cubes.size() > 0) bone.add("cubes", cubes);
        bones.add(bone);
    }

    /** True if any face of this cube has a texture assigned. Untextured cubes are BIL
     *  reference/hitbox boxes (faces with "texture": null or no texture key) and must not render. */
    private static boolean hasTexturedFace(JsonObject e) {
        if (!e.has("faces") || !e.get("faces").isJsonObject()) return false;
        JsonObject faces = e.getAsJsonObject("faces");
        for (String key : faces.keySet()) {
            JsonElement fe = faces.get(key);
            if (fe != null && fe.isJsonObject()) {
                JsonElement tex = fe.getAsJsonObject().get("texture");
                if (tex != null && !tex.isJsonNull()) return true;
            }
        }
        return false;
    }

    /** Bedrock bone name: the group's name, or a uuid-derived fallback for unnamed groups
     *  (some Blockbench exports, e.g. Tom's possum, ship groups with only uuid/children). */
    private static String boneName(JsonObject group) {
        if (group.has("name") && group.get("name").isJsonPrimitive()) {
            return group.get("name").getAsString();
        }
        if (group.has("uuid")) {
            return "bone_" + group.get("uuid").getAsString().replace("-", "");
        }
        return "bone";
    }

    private static JsonObject makeCube(JsonObject e) {
        double[] from = optArr(e, "from");
        double[] to = optArr(e, "to");
        JsonObject cube = new JsonObject();

        // Blockbench allows from/to in either order; an "inverted" cube has from > to on an axis.
        // Tom's elephant has a full-length body panel authored inverted on Z (28 -> -28); taken
        // literally that yields a negative size and the panel renders flipped, so the body texture
        // (which includes the face at the front) ends up on the rear. Normalise to min/max so the
        // cube is always well-formed. X stays mirrored for Bedrock (origin = -maxX).
        double minX = Math.min(from[0], to[0]), maxX = Math.max(from[0], to[0]);
        double minY = Math.min(from[1], to[1]), maxY = Math.max(from[1], to[1]);
        double minZ = Math.min(from[2], to[2]), maxZ = Math.max(from[2], to[2]);

        JsonArray origin = new JsonArray();
        origin.add(-maxX); origin.add(minY); origin.add(minZ);
        cube.add("origin", origin);

        JsonArray size = new JsonArray();
        size.add(maxX - minX); size.add(maxY - minY); size.add(maxZ - minZ);
        cube.add("size", size);

        if (e.has("inflate")) cube.addProperty("inflate", e.get("inflate").getAsDouble());

        double[] rot = optArr(e, "rotation");
        if (rot != null && (rot[0] != 0 || rot[1] != 0 || rot[2] != 0)) {
            cube.add("pivot", flipX(arr(e, "origin")));
            JsonArray r = new JsonArray();
            r.add(-rot[0]); r.add(-rot[1]); r.add(rot[2]);
            cube.add("rotation", r);
        }

        boolean boxUv = e.has("box_uv") && e.get("box_uv").getAsBoolean();
        if (!boxUv && e.has("faces")) {
            JsonObject uv = new JsonObject();
            JsonObject faces = e.getAsJsonObject("faces");
            for (String face : faces.keySet()) {
                JsonObject fd = faces.getAsJsonObject(face);
                if (!fd.has("uv")) continue;
                JsonArray u = fd.getAsJsonArray("uv");
                double u0 = u.get(0).getAsDouble(), v0 = u.get(1).getAsDouble();
                double u1 = u.get(2).getAsDouble(), v1 = u.get(3).getAsDouble();
                JsonObject faceUv = new JsonObject();
                JsonArray uvPos = new JsonArray(); uvPos.add(u0); uvPos.add(v0);
                JsonArray uvSize = new JsonArray(); uvSize.add(u1 - u0); uvSize.add(v1 - v0);
                faceUv.add("uv", uvPos);
                faceUv.add("uv_size", uvSize);
                uv.add(face, faceUv);
            }
            cube.add("uv", uv);
        } else if (boxUv) {
            double[] off = optArr(e, "uv_offset");
            JsonArray uv = new JsonArray();
            uv.add(off != null ? off[0] : 0);
            uv.add(off != null ? off[1] : 0);
            cube.add("uv", uv);
        }
        return cube;
    }

    private static byte[] extractTexture(JsonObject root) {
        if (!root.has("textures")) return new byte[0];
        for (JsonElement t : root.getAsJsonArray("textures")) {
            JsonObject tex = t.getAsJsonObject();
            if (!tex.has("source")) continue;
            String src = tex.get("source").getAsString();
            int comma = src.indexOf(',');
            if (src.startsWith("data:image") && comma > 0) {
                return Base64.getDecoder().decode(src.substring(comma + 1));
            }
        }
        return new byte[0];
    }

    private static JsonArray flipX(double[] v) {
        JsonArray a = new JsonArray();
        a.add(-v[0]); a.add(v[1]); a.add(v[2]);
        return a;
    }

    private static double[] arr(JsonObject o, String key) {
        double[] r = optArr(o, key);
        return r != null ? r : new double[]{0, 0, 0};
    }

    private static double[] optArr(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return null;
        JsonArray a = o.getAsJsonArray(key);
        double[] r = new double[a.size()];
        for (int i = 0; i < a.size(); i++) r[i] = a.get(i).getAsDouble();
        return r;
    }
}
