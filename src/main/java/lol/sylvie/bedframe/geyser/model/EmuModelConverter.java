package lol.sylvie.bedframe.geyser.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lol.sylvie.bedframe.geyser.model.BbModelConverter.Converted;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Converts a FactoryTools emuvanilla2 model (a vanilla-style LayerDefinition: a tree of
 * PartDefinition + CubeDefinition built with texOffs(...).addBox(...)) into Bedrock entity
 * geometry, producing the same {@link Converted} output as {@link BbModelConverter} so the
 * EntityTranslator/pack pipeline is unchanged.
 *
 * This is what mods like ChocoCraft use (via PolyModelInstance.create(modelCreator, layer,
 * texture)) instead of a .bbmodel.
 *
 * Everything is read by reflection so we don't compile against FactoryTools and so we stay
 * resilient across its versions. The cube math mirrors BbModelConverter (Java/Blockbench ->
 * Bedrock: X-axis flip on origins/pivots). Vanilla PartPoses are RELATIVE to the parent and
 * rotations are in radians, while Bedrock wants absolute pivots in degrees, so we accumulate
 * translations down the tree.
 */
public final class EmuModelConverter {
    private EmuModelConverter() {}

    /**
     * Ajuste vertical por defecto en pixeles (16 px = 1 bloque) para mobs que en su carrier de
     * Bedrock quedan hundidos (p.ej. chocobo, drifter). Cada discovery puede pasar su propio
     * valor a {@link #convert}; los mobs de suelo (rustle, rubblemite) usan 0. Recuerda subir
     * mod_version una vez para que Bedrock re-descargue el pack.
     */
    public static final double DEFAULT_Y_OFFSET = 0.0;

    /**
     * Shifts the whole model vertically so its lowest cube sits at y=0, i.e. the feet rest on
     * the ground. Robust across models/mods without a per-model magic offset (the various
     * vanilla renderer translate() values differ per entity).
     */
    private static void groundModel(JsonArray bones, double yOffset) {
        double minY = Double.POSITIVE_INFINITY;
        for (var b : bones) {
            JsonObject bone = b.getAsJsonObject();
            if (!bone.has("cubes")) continue;
            for (var c : bone.getAsJsonArray("cubes")) {
                minY = Math.min(minY, c.getAsJsonObject().getAsJsonArray("origin").get(1).getAsDouble());
            }
        }
        if (!Double.isFinite(minY)) return;          // sin cubos, nada que aterrizar
        double shift = -minY + yOffset;              // patas en y=0, mas el ajuste manual
        if (shift == 0) return;
        for (var b : bones) {
            JsonObject bone = b.getAsJsonObject();
            shiftY(bone.getAsJsonArray("pivot"), shift);
            if (!bone.has("cubes")) continue;
            for (var c : bone.getAsJsonArray("cubes")) {
                shiftY(c.getAsJsonObject().getAsJsonArray("origin"), shift);
            }
        }
    }

    private static void shiftY(JsonArray xyz, double dy) {
        if (xyz == null || xyz.size() < 2) return;
        double y = xyz.get(1).getAsDouble() + dy;
        xyz.set(1, new com.google.gson.JsonPrimitive(y));
    }

    /**
     * @param layerDefinition a FactoryTools emuvanilla2 LayerDefinition instance (Object to avoid a compile dep)
     * @param texturePng      the model's texture PNG bytes (loaded by the caller from the source mod)
     * @param texW/texH       texture resolution in pixels (the LayerDefinition's material size)
     */
    public static Converted convert(String namespace, String path, Object layerDefinition,
                                    byte[] texturePng, int texW, int texH, double yOffset) {
        try {
            JsonArray bones = new JsonArray();
            if (isV1(layerDefinition)) {
                // emuvanilla v1: yarn-style TexturedModelData.data(ModelData).data(ModelPartData root)
                Object root = field(field(layerDefinition, "data"), "data");
                for (Map.Entry<String, Object> child : childMapV1(root))
                    walkV1(child.getKey(), child.getValue(), null, new double[]{0, 0, 0}, bones);
            } else {
                // emuvanilla2: Mojmap LayerDefinition -> MeshDefinition -> root PartDefinition
                Object meshRoot = meshRoot(layerDefinition);
                // The mesh root itself is an unnamed container; walk its named children as top-level bones.
                for (Map.Entry<String, Object> child : children(meshRoot)) {
                    walk(String.valueOf(child.getKey()), child.getValue(), null,
                            new double[]{0, 0, 0}, bones);
                }
            }
            groundModel(bones, yOffset);   // lift so the lowest cube rests at y=0 (feet on the ground)

            String geometryId = "geometry." + namespace + "." + path;
            JsonObject description = new JsonObject();
            description.addProperty("identifier", geometryId);
            description.addProperty("texture_width", texW);
            description.addProperty("texture_height", texH);

            JsonObject geo = new JsonObject();
            geo.add("description", description);
            geo.add("bones", bones);

            JsonArray geoList = new JsonArray();
            geoList.add(geo);
            JsonObject out = new JsonObject();
            out.addProperty("format_version", "1.16.0");
            out.add("minecraft:geometry", geoList);

            return new Converted(geometryId, out.toString(), texturePng);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("emuvanilla2 model conversion failed for "
                    + namespace + ":" + path, e);
        }
    }

    /** Walk a PartDefinition into a Bedrock bone, accumulating absolute translation. */
    private static void walk(String name, Object part, String parent,
                             double[] parentAbs, JsonArray bones) throws ReflectiveOperationException {
        Object pose = pose(part);
        double px = f(pose, "x"), py = f(pose, "y"), pz = f(pose, "z");
        double[] abs = {parentAbs[0] + px, parentAbs[1] + py, parentAbs[2] + pz};

        // Prefix bone names so the vanilla carrier's bone animations (polar_bear moves bones
        // named head/body/leg0..3) don't bind to ours and tear the model apart - this is what
        // detached Rustle's head (a separate top-level bone) while nested models (Drifter) were fine.
        String boneName = "bf_" + name;
        JsonObject bone = new JsonObject();
        bone.addProperty("name", boneName);
        if (parent != null) bone.addProperty("parent", parent);
        bone.add("pivot", flipXY(abs));                      // absolute pivot, X+Y flipped

        double rx = deg(f(pose, "xRot")), ry = deg(f(pose, "yRot")), rz = deg(f(pose, "zRot"));
        if (rx != 0 || ry != 0 || rz != 0) {
            JsonArray r = new JsonArray();
            r.add(-rx); r.add(-ry); r.add(rz);               // same sign convention as bbmodel
            bone.add("rotation", r);
        }

        JsonArray cubes = new JsonArray();
        for (Object cube : cubes(part)) cubes.add(makeCube(cube, abs));
        if (cubes.size() > 0) bone.add("cubes", cubes);
        bones.add(bone);

        for (Map.Entry<String, Object> c : children(part)) {
            walk(String.valueOf(c.getKey()), c.getValue(), boneName, abs, bones);
        }
    }

    /** CubeDefinition (addBox x,y,z,w,h,d + texOffs u,v) -> Bedrock cube in model space. */
    private static JsonObject makeCube(Object cube, double[] abs) throws ReflectiveOperationException {
        Object origin = field(cube, "origin");               // org.joml.Vector3f, local addBox corner
        Object dims = field(cube, "dimensions");             // w,h,d
        double ox = vec(origin, "x"), oy = vec(origin, "y"), oz = vec(origin, "z");
        double w = vec(dims, "x"), h = vec(dims, "y"), d = vec(dims, "z");

        // Move local cube coords into model space, then flip X and Y (vanilla Java entity
        // models are X- and Y-inverted relative to Bedrock geometry). Bedrock origin is the
        // min corner, so the flipped axes use -(from + size).
        double fromX = abs[0] + ox, fromY = abs[1] + oy, fromZ = abs[2] + oz;

        JsonObject out = new JsonObject();
        JsonArray bOrigin = new JsonArray();
        bOrigin.add(-(fromX + w)); bOrigin.add(-(fromY + h)); bOrigin.add(fromZ);
        out.add("origin", bOrigin);

        JsonArray size = new JsonArray();
        size.add(w); size.add(h); size.add(d);
        out.add("size", size);

        double inflate = grow(cube);
        if (inflate != 0) out.addProperty("inflate", inflate);

        if (boolField(cube, "mirror")) out.addProperty("mirror", true);

        // Box UV: texOffs(u, v).
        Object uv = field(cube, "texCoord");                 // UVPair
        JsonArray bUv = new JsonArray();
        bUv.add(uvComponent(uv, true));
        bUv.add(uvComponent(uv, false));
        out.add("uv", bUv);
        return out;
    }

    // ---- reflection helpers (cached lookups would be nicer; conversion runs once at startup) ----

    private static Object meshRoot(Object layerDefinition) throws ReflectiveOperationException {
        Object mesh = field(layerDefinition, "mesh");        // MeshDefinition
        return invoke(mesh, "getRoot");                      // root PartDefinition
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<Map.Entry<String, Object>> children(Object part) throws ReflectiveOperationException {
        // emuvanilla2 (like vanilla) returns the entry SET from getChildren(), not the map.
        return (java.util.Set<Map.Entry<String, Object>>) invoke(part, "getChildren");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> cubes(Object part) throws ReflectiveOperationException {
        // PartDefinition exposes children via getChildren() but the cubes are a plain field.
        return (List<Object>) field(part, "cubes");
    }

    private static Object pose(Object part) throws ReflectiveOperationException {
        return field(part, "partPose");                      // PartPose record
    }

    /** PartPose record accessor (x(), y(), xRot(), ...). */
    private static double f(Object pose, String comp) throws ReflectiveOperationException {
        return ((Number) invoke(pose, comp)).doubleValue();
    }

    private static double vec(Object v3f, String comp) throws ReflectiveOperationException {
        // org.joml.Vector3f exposes public fields x/y/z.
        Field fld = v3f.getClass().getField(comp);
        return fld.getFloat(v3f);
    }

    private static double grow(Object cube) throws ReflectiveOperationException {
        Object def = field(cube, "grow");                    // CubeDeformation
        if (def == null) return 0;
        // Bedrock inflate is uniform; vanilla grow is per-axis but practically uniform here.
        try { return ((Number) field(def, "growX")).doubleValue(); }
        catch (NoSuchFieldException e) { return 0; }
    }

    private static double uvComponent(Object uvPair, boolean u) throws ReflectiveOperationException {
        // UVPair is a record of two floats; read by record component order [u, v] so we don't
        // depend on the component names.
        var comps = uvPair.getClass().getRecordComponents();
        if (comps != null && comps.length >= 2) {
            var acc = comps[u ? 0 : 1].getAccessor();
            acc.setAccessible(true);
            return ((Number) acc.invoke(uvPair)).doubleValue();
        }
        try { return ((Number) invoke(uvPair, u ? "u" : "v")).doubleValue(); }
        catch (NoSuchMethodException ignored) { }
        return ((Number) field(uvPair, u ? "u" : "v")).doubleValue();
    }

    private static double deg(double radians) { return Math.toDegrees(radians); }

    private static JsonArray flipX(double[] v) {
        JsonArray a = new JsonArray();
        a.add(-v[0]); a.add(v[1]); a.add(v[2]);
        return a;
    }

    private static JsonArray flipXY(double[] v) {
        JsonArray a = new JsonArray();
        a.add(-v[0]); a.add(-v[1]); a.add(v[2]);
        return a;
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        Field f = findField(o.getClass(), name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static boolean boolField(Object o, String name) throws ReflectiveOperationException {
        Field f = findField(o.getClass(), name);
        f.setAccessible(true);
        return f.getBoolean(o);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name + " in " + c);
    }

    private static Object invoke(Object o, String method) throws ReflectiveOperationException {
        Method m = o.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(o);
    }

    // ===== emuvanilla v1 (yarn-style TexturedModelData / ModelPartData / ModelCuboidData) =====
    // Same geometry math as v2 (X/Y flip, absolute pivots, feet-grounding); only the field names
    // differ. v1 keeps pivot AND rotation together in ModelTransform (x,y,z + pitch,yaw,roll),
    // cubes in cuboidData, children in a children Map, cube corner in 'offset', UV in 'textureUV'.

    private static boolean isV1(Object data) {
        return data != null && "TexturedModelData".equals(data.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<Map.Entry<String, Object>> childMapV1(Object part) throws ReflectiveOperationException {
        Map<String, Object> m = (Map<String, Object>) field(part, "children");
        return m.entrySet();
    }

    @SuppressWarnings("unchecked")
    private static void walkV1(String name, Object part, String parent,
                               double[] parentAbs, JsonArray bones) throws ReflectiveOperationException {
        Object t = field(part, "rotationData");              // ModelTransform (pivot + rotation)
        double px = ff(t, "x"), py = ff(t, "y"), pz = ff(t, "z");
        double[] abs = {parentAbs[0] + px, parentAbs[1] + py, parentAbs[2] + pz};

        String boneName = "bf_" + name;
        JsonObject bone = new JsonObject();
        bone.addProperty("name", boneName);
        if (parent != null) bone.addProperty("parent", parent);
        bone.add("pivot", flipXY(abs));

        double rx = deg(ff(t, "pitch")), ry = deg(ff(t, "yaw")), rz = deg(ff(t, "roll"));
        if (rx != 0 || ry != 0 || rz != 0) {
            JsonArray r = new JsonArray();
            r.add(-rx); r.add(-ry); r.add(rz);
            bone.add("rotation", r);
        }

        JsonArray cubes = new JsonArray();
        for (Object cube : (List<Object>) field(part, "cuboidData")) cubes.add(makeCubeV1(cube, abs));
        if (cubes.size() > 0) bone.add("cubes", cubes);
        bones.add(bone);

        for (Map.Entry<String, Object> c : childMapV1(part))
            walkV1(c.getKey(), c.getValue(), boneName, abs, bones);
    }

    private static JsonObject makeCubeV1(Object cube, double[] abs) throws ReflectiveOperationException {
        Object offset = field(cube, "offset");               // Vector3f, local addBox corner
        Object dims = field(cube, "dimensions");             // Vector3f, w/h/d
        double ox = vec(offset, "x"), oy = vec(offset, "y"), oz = vec(offset, "z");
        double w = vec(dims, "x"), h = vec(dims, "y"), d = vec(dims, "z");
        double fromX = abs[0] + ox, fromY = abs[1] + oy, fromZ = abs[2] + oz;

        JsonObject out = new JsonObject();
        JsonArray bOrigin = new JsonArray();
        bOrigin.add(-(fromX + w)); bOrigin.add(-(fromY + h)); bOrigin.add(fromZ);
        out.add("origin", bOrigin);

        JsonArray size = new JsonArray();
        size.add(w); size.add(h); size.add(d);
        out.add("size", size);

        Object dil = field(cube, "extraSize");               // Dilation (radiusX/Y/Z)
        if (dil != null) { double inf = ff(dil, "radiusX"); if (inf != 0) out.addProperty("inflate", inf); }

        if (boolField(cube, "mirror")) out.addProperty("mirror", true);

        Object uv = field(cube, "textureUV");                // Vector2f (x=u, y=v)
        JsonArray bUv = new JsonArray();
        bUv.add(vec(uv, "x")); bUv.add(vec(uv, "y"));
        out.add("uv", bUv);
        return out;
    }

    /** Read a (possibly private) float/number field as a double. */
    private static double ff(Object o, String name) throws ReflectiveOperationException {
        return ((Number) field(o, name)).doubleValue();
    }
}
