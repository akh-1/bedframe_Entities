package lol.sylvie.bedframe.geyser.translator;

import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Either;
import eu.pb4.polymer.blocks.api.BlockResourceCreator;
import eu.pb4.polymer.blocks.api.MultiPolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import lol.sylvie.bedframe.geyser.TranslationManager;
import lol.sylvie.bedframe.geyser.Translator;
import lol.sylvie.bedframe.geyser.model.JavaGeometryConverter;
import lol.sylvie.bedframe.mixin.BlockResourceCreatorAccessor;
import lol.sylvie.bedframe.mixin.PolymerBlockResourceUtilsAccessor;
import lol.sylvie.bedframe.util.ResourceHelper;
import net.kyori.adventure.key.Key;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;
import org.geysermc.geyser.api.block.custom.CustomBlockData;
import org.geysermc.geyser.api.block.custom.CustomBlockPermutation;
import org.geysermc.geyser.api.block.custom.CustomBlockState;
import org.geysermc.geyser.api.block.custom.NonVanillaCustomBlockData;
import org.geysermc.geyser.api.block.custom.component.*;
import org.geysermc.geyser.api.block.custom.nonvanilla.JavaBlockState;
import org.geysermc.geyser.api.block.custom.nonvanilla.JavaBoundingBox;
import org.geysermc.geyser.api.event.EventBus;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.api.util.CreativeCategory;
import org.geysermc.geyser.util.MathUtils;
import org.geysermc.geyser.util.SoundUtils;
import org.geysermc.pack.bedrock.resource.models.entity.ModelEntity;
import org.geysermc.pack.converter.type.model.ModelStitcher;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ModelTexture;
import team.unnamed.creative.model.ModelTextures;
import team.unnamed.creative.serialize.minecraft.model.ModelSerializer;
import xyz.nucleoid.packettweaker.PacketContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static lol.sylvie.bedframe.util.BedframeConstants.LOGGER;
import static lol.sylvie.bedframe.util.PathHelper.createDirectoryOrThrow;

public class BlockTranslator extends Translator {
    // Maps parent models to a map containing the translations between Java sides and Bedrock sides.
    // NOTE: block/cross is intentionally excluded — it has no equivalent built-in Bedrock geometry,
    // so it must go through JavaGeometryConverter to produce a geometry.cross.geo.json file.
    private static final Map<String, List<Pair<String, String>>> parentFaceMap = Map.of(
            "block/cube_all", List.of(
                    new Pair<>("all", "*")
            ),
            "block/cube_bottom_top", List.of(
                    new Pair<>("side", "*"),
                    new Pair<>("top", "up"),
                    new Pair<>("bottom", "down"),
                    new Pair<>("side", "north"),
                    new Pair<>("side", "south"),
                    new Pair<>("side", "east"),
                    new Pair<>("side", "west")
            ),
            "block/cube_column", List.of(
                    new Pair<>("side", "*"),
                    new Pair<>("end", "up"),
                    new Pair<>("end", "down"),
                    new Pair<>("side", "north"),
                    new Pair<>("side", "south"),
                    new Pair<>("side", "east"),
                    new Pair<>("side", "west")
            ),
            "block/cube_column_horizontal", List.of(
                    new Pair<>("side", "*"),
                    new Pair<>("end", "up"),
                    new Pair<>("end", "down"),
                    new Pair<>("side", "north"),
                    new Pair<>("side", "south"),
                    new Pair<>("side", "east"),
                    new Pair<>("side", "west")
            ),
            "block/orientable", List.of(
                    new Pair<>("side", "*"),
                    new Pair<>("front", "north"),
                    new Pair<>("top", "up"),
                    new Pair<>("bottom", "down")
            )
    );

    // HashSet instead of ArrayList: contains() is the only operation done on this
    // collection and HashSet gives O(1) lookup. With hundreds of registered blocks,
    // the ArrayList version was doing thousands of unnecessary equality checks per
    // pack-generation pass.
    private static final java.util.HashSet<PolymerBlock> registeredBlocks = new java.util.HashSet<>();
    private final HashMap<Identifier, PolymerBlock> blocks = new HashMap<>();

    /**
     * Mods whose blocks Bedframe must register even though their blocks do NOT have a
     * {@link PolymerSyncedObject} associated. See the equivalent set in
     * {@link ItemTranslator} for the full rationale.
     */
    private static final Set<String> BEDFRAME_NON_POLYMER_FORCE_REGISTER_MODS = Set.of();

    public BlockTranslator() {
        Stream<Identifier> blockIds = Registries.BLOCK.getIds().stream();

        blockIds.forEach(identifier -> {
            Block block = Registries.BLOCK.get(identifier);
            // Same approach as ItemTranslator: PolymerSyncedObject.getSyncedObject covers
            // both native PolymerBlock implementors AND overlay-registered blocks from
            // Polymer patch mods (PolymerBlock.registerOverlay).
            if (PolymerSyncedObject.getSyncedObject(Registries.BLOCK, block) instanceof PolymerBlock polymerBlock) {
                blocks.put(identifier, polymerBlock);
                return;
            }

            // Force-register path: blocks from mods in BEDFRAME_NON_POLYMER_FORCE_REGISTER_MODS.
            // Build a synthetic identity PolymerBlock in memory — no registry mutation — so
            // the block is declared to Geyser. Without this, a Bedrock client receiving an
            // unknown block ID can crash.
            if (BEDFRAME_NON_POLYMER_FORCE_REGISTER_MODS.contains(identifier.getNamespace())) {
                blocks.put(identifier, new PolymerBlock() {
                    @Override
                    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
                        return state;
                    }
                });
            }
        });
    }

    private void forEachBlock(BiConsumer<Identifier, PolymerBlock> function) {
        for (Map.Entry<Identifier, PolymerBlock> entry : blocks.entrySet()) {
            try {
                function.accept(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                LOGGER.error("Couldn't load block {}", entry.getKey(), e);
            }
        }
    }

    // Bedrock custom-block properties may not exceed 16 values (the BDS limit Geyser
    // warns about: "<name> contains more than 16 values, but BDS specifies it should
    // not"). A Java IntProperty above this — e.g. Beautify hanging_pot's 'potflower'
    // with 26 values — registers an out-of-spec property whose higher block-states the
    // Bedrock client mis-maps; the moment such a state is shown (e.g. a flower reaching
    // grown=true, which lives in those high state IDs) it corrupts ALL custom-block
    // rendering. We split an oversized int property into a base-16 pair: 'name' (the low
    // 0..15 index) and 'name_hi' (the high index). Properties with <=16 values are
    // emitted exactly as before.
    private static final int MAX_BEDROCK_PROPERTY_VALUES = 16;

    private static boolean isOversized(IntProperty property) {
        return property.getValues().size() > MAX_BEDROCK_PROPERTY_VALUES;
    }

    /** Stable, sorted value list so an int value maps to a deterministic 0-based index. */
    private static List<Integer> sortedValues(IntProperty property) {
        return property.getValues().stream().sorted().toList();
    }

    private static List<Integer> indexRange(int maxInclusive) {
        List<Integer> out = new ArrayList<>(maxInclusive + 1);
        for (int i = 0; i <= maxInclusive; i++) out.add(i);
        return out;
    }

    private void populateProperties(CustomBlockData.Builder builder, Collection<Property<?>> properties) {
        for (Property<?> property : properties) {
            switch (property) {
                case IntProperty intProperty -> {
                    if (isOversized(intProperty)) {
                        int hiMax = (intProperty.getValues().size() - 1) / MAX_BEDROCK_PROPERTY_VALUES;
                        builder.intProperty(property.getName(), indexRange(MAX_BEDROCK_PROPERTY_VALUES - 1));
                        builder.intProperty(property.getName() + "_hi", indexRange(hiMax));
                    } else {
                        builder.intProperty(property.getName(), List.copyOf(intProperty.getValues()));
                    }
                }
                case BooleanProperty ignored ->
                        builder.booleanProperty(property.getName());
                case EnumProperty<?> enumProperty ->
                        builder.stringProperty(enumProperty.getName(), enumProperty.getValues().stream().map(Enum::name).map(String::toLowerCase).toList());
                default ->
                        LOGGER.error("Unknown property type: {}", property.getClass().getName());
            }
        }
    }

    // okay so this is very much hydraulic code
    // TODO: see if shape.getBoundingBox() can replace the code here
    private BoxComponent voxelShapeToBoxComponent(VoxelShape shape) {
        if (shape.isEmpty())
            return BoxComponent.emptyBox();

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;

        for (Box boundingBox : shape.getBoundingBoxes()) {
            double offsetX = boundingBox.getLengthX() * 0.5;
            double offsetY = boundingBox.getLengthY() * 0.5;
            double offsetZ = boundingBox.getLengthZ() * 0.5;

            Vec3d center = boundingBox.getCenter();

            minX = Math.min(minX, (float) (center.getX() - offsetX));
            minY = Math.min(minY, (float) (center.getY() - offsetY));
            minZ = Math.min(minZ, (float) (center.getZ() - offsetZ));

            maxX = Math.max(maxX, (float) (center.getX() + offsetX));
            maxY = Math.max(maxY, (float) (center.getY() + offsetY));
            maxZ = Math.max(maxZ, (float) (center.getZ() + offsetZ));
        }

        minX = MathUtils.clamp(minX, 0, 1);
        minY = MathUtils.clamp(minY, 0, 1);
        minZ = MathUtils.clamp(minZ, 0, 1);

        maxX = MathUtils.clamp(maxX, 0, 1);
        maxY = MathUtils.clamp(maxY, 0, 1);
        maxZ = MathUtils.clamp(maxZ, 0, 1);

        return new BoxComponent(
                16 * (1 - maxX) - 8,
                16 * minY,
                16 * minZ - 8,
                16 * (maxX - minX),
                16 * (maxY - minY),
                16 * (maxZ - minZ)
        );
    }

    private Model resolveModel(Identifier identifier) {
        // This is unstable (https://unnamed.team/docs/creative/latest/serialization/minecraft)
        try {
            String modelPath = identifier.getPath();
            if (!(modelPath.startsWith("item/") || modelPath.startsWith("block/"))) modelPath = "block/" + modelPath;
            JsonObject model = ResourceHelper.readJsonResource(identifier.getNamespace(), "models/" + modelPath + ".json");
            return ModelSerializer.INSTANCE.deserializeFromJson(model, Key.key(identifier.toString()));
        } catch (RuntimeException e) {
            // Demoted to debug: with the tolerant stitch provider, missing parent models
            // (like minecraft:block/block) are expected and handled by returning an empty
            // placeholder. We only care here when the WHOLE root model is missing.
            LOGGER.debug("Couldn't resolve model {}", identifier);
            return null;
        }
    }

    /**
     * Reads the mod's own blockstate JSON to resolve a Java Model for the given state.
     * Used for blocks that have no entry in Polymer's BlockResourceCreator map (e.g. mod
     * blocks patched via {@code PolymerBlock.registerOverlay} that map to vanilla states
     * via StateCopyFactoryBlock — Terrestria slabs, fences, stairs, buttons, shelves, etc.).
     *
     * <p>The blockstate JSON lives at {@code assets/<namespace>/blockstates/<path>.json}.
     * For Terrestria, that JAR is on the classpath, so {@link ResourceHelper#hasResource}
     * finds it via the classloader fallback even when Polymer hasn't materialised it into
     * the pack builder yet.
     *
     * @return Pair&lt;model, [rotX, rotY]&gt; on success, or null if no matching variant is found
     */
    /**
     * Common namespace suffixes/prefixes used by polymer patch mods.
     * When a mod's own blockstate JSON points to a placeholder model with no geometry,
     * the real geometry usually lives under one of these patch namespaces. This is the
     * pattern used by comforts-polymer-patch, enderscape-patch, and others.
     */
    private static final String[] PATCH_NAMESPACE_TEMPLATES = {
        "%s-polymer-patch",
        "%s-patch",
        "%s_polymer_patch",
        "polymer-%s",
        "polymer_%s"
    };

    private Pair<Model, int[]> resolveBlockstateModel(Identifier blockId, BlockState state) {
        // First try the block's own namespace.
        Pair<Model, int[]> primary = resolveBlockstateFromNamespace(
            blockId.getNamespace(), blockId.getPath(), state);
        if (primary != null && hasElements(primary.getLeft())) return primary;

        // Primary either failed or yielded an empty model (e.g. comforts points to a
        // placeholder). Try patch namespaces — many polymer patch mods stash the real
        // geometry under "<original>-polymer-patch" or similar.
        for (String tpl : PATCH_NAMESPACE_TEMPLATES) {
            String patchNs = String.format(tpl, blockId.getNamespace());
            // Only attempt namespaces that actually have an asset directory loaded —
            // ResourceHelper.hasResource quickly returns false for unknown namespaces
            // via the classloader miss.
            if (!ResourceHelper.hasResource(patchNs, "blockstates/" + blockId.getPath() + ".json")) continue;
            Pair<Model, int[]> patched = resolveBlockstateFromNamespace(patchNs, blockId.getPath(), state);
            if (patched != null && hasElements(patched.getLeft())) return patched;
        }

        // No patch produced geometry — return the primary (may be null or empty) so the
        // caller can decide what to do (fall through to furniture / skip).
        return primary;
    }

    private static boolean hasElements(Model m) {
        return m != null && m.elements() != null && !m.elements().isEmpty();
    }

    private Pair<Model, int[]> resolveBlockstateFromNamespace(String namespace, String path, BlockState state) {
        try {
            JsonObject bsJson = ResourceHelper.readJsonResource(
                namespace, "blockstates/" + path + ".json");

            List<String> stateProps = new ArrayList<>();
            for (Property<?> prop : state.getProperties()) {
                stateProps.add(prop.getName() + "=" + state.get(prop).toString().toLowerCase());
            }

            String pickedModel = null;
            int pickedX = 0, pickedY = 0;

            // === variants branch ===
            if (bsJson.has("variants")) {
                JsonObject variants = bsJson.getAsJsonObject("variants");
                for (Map.Entry<String, com.google.gson.JsonElement> entry : variants.entrySet()) {
                    String variantKey = entry.getKey();
                    boolean matches = variantKey.isEmpty();
                    if (!matches) {
                        matches = true;
                        for (String prop : variantKey.split(",")) {
                            if (!stateProps.contains(prop)) { matches = false; break; }
                        }
                    }
                    if (!matches) continue;
                    com.google.gson.JsonElement v = entry.getValue();
                    JsonObject variant = v.isJsonArray()
                        ? v.getAsJsonArray().get(0).getAsJsonObject()
                        : v.getAsJsonObject();
                    pickedModel = variant.get("model").getAsString();
                    if (variant.has("x")) pickedX = variant.get("x").getAsInt();
                    if (variant.has("y")) pickedY = variant.get("y").getAsInt();
                    break;
                }
            }

            // === multipart branch ===
            if (pickedModel == null && bsJson.has("multipart")) {
                for (com.google.gson.JsonElement partEl : bsJson.getAsJsonArray("multipart")) {
                    JsonObject part = partEl.getAsJsonObject();
                    boolean matches = !part.has("when") || multipartConditionMatches(part.getAsJsonObject("when"), stateProps);
                    if (!matches) continue;
                    com.google.gson.JsonElement applyEl = part.get("apply");
                    JsonObject apply = applyEl.isJsonArray()
                        ? applyEl.getAsJsonArray().get(0).getAsJsonObject()
                        : applyEl.getAsJsonObject();
                    pickedModel = apply.get("model").getAsString();
                    if (apply.has("x")) pickedX = apply.get("x").getAsInt();
                    if (apply.has("y")) pickedY = apply.get("y").getAsInt();
                    break;
                }
            }

            if (pickedModel == null) return null;

            Model resolved = resolveModel(Identifier.of(pickedModel));
            if (resolved == null) return null;

            // Stitch the parent chain so the model has concrete elements/textures.
            ModelStitcher.Provider provider = tolerantStitchProvider();
            try {
                Model stitched = new ModelStitcher(provider, resolved).stitch();
                if (stitched != null) resolved = stitched;
            } catch (Exception ignored) { /* fall back to unstitched */ }

            return new Pair<>(resolved, new int[]{pickedX, pickedY});
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Tests whether a multipart "when" condition matches the state's properties.
     * Supports flat property maps, OR composites, and AND composites — all lower-cased.
     */
    private static boolean multipartConditionMatches(JsonObject when, List<String> stateProps) {
        if (when.has("OR")) {
            for (com.google.gson.JsonElement el : when.getAsJsonArray("OR")) {
                if (multipartConditionMatches(el.getAsJsonObject(), stateProps)) return true;
            }
            return false;
        }
        if (when.has("AND")) {
            for (com.google.gson.JsonElement el : when.getAsJsonArray("AND")) {
                if (!multipartConditionMatches(el.getAsJsonObject(), stateProps)) return false;
            }
            return true;
        }
        for (Map.Entry<String, com.google.gson.JsonElement> e : when.entrySet()) {
            String k = e.getKey();
            // Value can be a single value or a "|"-separated list (e.g. "north|south")
            String[] allowed = e.getValue().getAsString().toLowerCase().split("\\|");
            boolean any = false;
            for (String v : allowed) {
                if (stateProps.contains(k + "=" + v)) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }

    /**
     * Returns true if the given Polymer block models is just a placeholder pointing to
     * {@code polymer:block/empty}. The polymer-patch-bundle uses this for blocks that
     * are rendered via a Display Entity (BlockWithElementHolder) — slabs, doors,
     * saplings, signs, etc. The placeholder has no real geometry.
     */
    private static boolean polymerBlockModelsArePolymerEmpty(Either<PolymerBlockModel[], MultiPolymerBlockModel> models) {
        PolymerBlockModel[] arr = models.left().isPresent()
            ? models.left().orElseThrow()
            : models.right().orElseThrow().models().getFirst();
        if (arr.length == 0) return false;
        for (PolymerBlockModel m : arr) {
            String s = m.model().toString();
            if (!"polymer:block/empty".equals(s)) return false;
        }
        return true;
    }

    /**
     * Returns true if the PNG file at the given path is mostly grayscale, meaning it's
     * a candidate for multiplicative biome tinting. Java's BlockColors return white
     * (no-op) for pre-colored textures and a real color for grayscale ones — we mirror
     * that decision by inspecting the actual pixels rather than guessing from the name.
     *
     * <p>Uses a sample-and-threshold approach for performance: we don't need to be
     * exact, just to distinguish "grayscale needing tint" from "already colored". If
     * the file is missing or unreadable we err on NOT tinting (keeps the texture as
     * the mod shipped it, the safer default).
     */
    /**
     * Cache of grayscale-check results keyed by absolute path. The result for a
     * given PNG never changes, but {@link #isGrayscaleTexture} is called once per
     * block-face that references that texture — for a mod like Terrestria where many
     * leaf blocks share a small set of leaf textures, that's dozens of redundant
     * ImageIO.read calls. Caching shaves significant time off pack generation
     * without changing behavior.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> GRAYSCALE_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean isGrayscaleTexture(Path pngPath) {
        String key = pngPath.toAbsolutePath().toString();
        Boolean cached = GRAYSCALE_CACHE.get(key);
        if (cached != null) return cached;
        boolean result = computeIsGrayscale(pngPath);
        GRAYSCALE_CACHE.put(key, result);
        return result;
    }

    private static boolean computeIsGrayscale(Path pngPath) {
        try {
            if (!Files.exists(pngPath)) return false;
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngPath.toFile());
            if (img == null) return false;
            int w = img.getWidth(), h = img.getHeight();
            int sampledOpaque = 0, grayscaleHits = 0;
            // Sample on a 16-step grid — 256 reads max regardless of texture size.
            int stepX = Math.max(1, w / 16), stepY = Math.max(1, h / 16);
            for (int y = 0; y < h; y += stepY) {
                for (int x = 0; x < w; x += stepX) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    if (a < 32) continue; // skip transparent pixels (matters for cross / leaves alpha)
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    sampledOpaque++;
                    // Tolerance: 5/255 channel diff. Vanilla Mojang grayscale leaves vary
                    // slightly due to PNG compression; this catches that without producing
                    // false positives on real colored textures (sakura R-G diff ~60).
                    if (Math.abs(r - g) <= 5 && Math.abs(g - b) <= 5 && Math.abs(r - b) <= 5) {
                        grayscaleHits++;
                    }
                }
            }
            if (sampledOpaque == 0) return false;
            // 70% threshold: a few stray colored pixels (mossy details, tiny berries) shouldn't
            // disqualify a fundamentally-grayscale texture from getting biome tint.
            return (grayscaleHits * 100 / sampledOpaque) >= 70;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Blocks where Java has {@code tintindex} in the model BUT the mod's BlockColors
     * registration overrides the tint to white ({@code 0xFFFFFF}), making the texture
     * render with its raw (usually grayscale) appearance. We can't see Java code from
     * here, so we maintain a list of known cases.
     *
     * <p>Common pattern: End/Nether/Void themed mods where the grayscale palette is
     * intentional (e.g. Enderscape veiled leaves are deliberately white).
     */
    private static final Set<String> NO_TINT_NAMESPACES = Set.of(
        "enderscape"  // End-themed: all leaves are decoratively grayscale
    );

    private static final Set<String> NO_TINT_KEYWORDS = Set.of(
        "void", "shadow", "veiled", "nebula", "nether"
    );

    /**
     * Returns true if this block should skip biome tinting even when its model has
     * tintindex and its texture is grayscale. Catches cases where the mod intentionally
     * keeps the gray look (Enderscape's veiled leaves, Nether-themed leaves, etc.) by
     * registering a 0xFFFFFF BlockColor on the Java side that we can't see from JSON.
     */
    private static boolean shouldSkipTint(Identifier blockId) {
        if (NO_TINT_NAMESPACES.contains(blockId.getNamespace())) return true;
        String pathLower = blockId.getPath().toLowerCase();
        for (String kw : NO_TINT_KEYWORDS) {
            if (pathLower.contains(kw)) return true;
        }
        return false;
    }

    public static boolean isRegisteredBlock(PolymerBlock block) {
        return registeredBlocks.contains(block);
    }

    /**
     * Heuristically picks a Bedrock {@code tint_method} for a block based on its id.
     * Java's tint comes from BlockColors registered in code, which we can't introspect
     * — so we match on common naming patterns.
     *
     * <p>Tint methods supported by Bedrock:
     * <ul>
     *   <li>{@code grass} — grass blocks, ferns, tall grass (full biome temperature/rainfall)</li>
     *   <li>{@code default_foliage} — generic leaves (oak, birch in temperate biomes)</li>
     *   <li>{@code evergreen_foliage} — spruce/pine leaves (constant dark green)</li>
     *   <li>{@code birch_foliage} — birch leaves (constant yellow-green)</li>
     *   <li>{@code dry_foliage} — desert palms 1.21+</li>
     *   <li>{@code water} — water blocks</li>
     * </ul>
     */
    private static String inferTintMethod(Identifier blockId) {
        String name = blockId.getPath().toLowerCase();
        if (name.contains("spruce") && name.contains("leaves")) return "evergreen_foliage";
        if (name.contains("pine") && name.contains("leaves")) return "evergreen_foliage";
        if (name.contains("redwood") && name.contains("leaves")) return "evergreen_foliage";
        if (name.contains("birch") && name.contains("leaves")) return "birch_foliage";
        if (name.contains("leaves") || name.contains("leaf")
                || name.contains("vine") || name.contains("lily_pad")) {
            return "default_foliage";
        }
        if (name.contains("grass_block")) return "grass";
        if (name.contains("grass") || name.contains("fern")
                || name.contains("tall_grass") || name.contains("tallgrass")) {
            return "grass";
        }
        if (name.contains("water")) return "water";
        // Default for any tintindex'd block we can't classify — foliage is the most common
        // tinted material in mods (orchards, tree mods). Better than gray.
        return "default_foliage";
    }

    /**
     * A model provider for {@link ModelStitcher} that tolerates missing parents.
     *
     * <p>The vanilla Geyser pack does not include some special root models (notably
     * {@code minecraft:block/block} which holds only {@code display} and {@code gui_light}).
     * If the stitcher encounters a missing model in the parent chain it gives up and the
     * resulting model has no elements — which then makes {@code JavaGeometryConverter}
     * reject it as empty. By returning a dummy empty Model instead of null, the stitcher
     * keeps the elements/textures already collected from earlier in the chain.
     */
    private ModelStitcher.Provider tolerantStitchProvider() {
        return key -> {
            Model m = resolveModel(Identifier.of(key.asString()));
            if (m == null) {
                m = Model.model().key(key).build();
            }
            return m;
        };
    }

    // Referenced https://github.com/GeyserMC/Hydraulic/blob/master/shared/src/main/java/org/geysermc/hydraulic/block/BlockPackModule.java#L54
    public void handle(GeyserDefineCustomBlocksEvent event, Path packRoot) {
        Path textureDir = createDirectoryOrThrow(packRoot.resolve("textures"));
        createDirectoryOrThrow(textureDir.resolve("blocks"));

        Path modelsDir = createDirectoryOrThrow(packRoot.resolve("models"));
        Path blockModelsDir = createDirectoryOrThrow(modelsDir.resolve("blocks"));

        JsonObject terrainTextureObject = new JsonObject();
        terrainTextureObject.addProperty("resource_pack_name", "Bedframe");
        terrainTextureObject.addProperty("texture_name", "atlas.terrain");

        JsonObject blocksJson = new JsonObject();
        blocksJson.addProperty("format_version", "1.21.40");

        JsonObject soundsJson = new JsonObject();
        JsonObject blockSoundsObject = new JsonObject();
        JsonObject interactiveSoundsObject = new JsonObject();

        JsonObject interactiveSoundsWrapper = new JsonObject();
        JsonObject textureDataObject = new JsonObject();

        forEachBlock((identifier, block) -> {
            Block realBlock = Registries.BLOCK.get(identifier);
            // Block names
            addTranslationKey("block." + identifier.getNamespace() + "." + identifier.getPath(), realBlock.getTranslationKey());

            NonVanillaCustomBlockData.Builder builder = NonVanillaCustomBlockData.builder()
                    .name(identifier.getPath())
                    .namespace(identifier.getNamespace())
                    .creativeGroup("itemGroup." + identifier.getNamespace() + ".blocks")
                    .creativeCategory(CreativeCategory.CONSTRUCTION)
                    .includedInCreativeInventory(true);

            // Properties
            populateProperties(builder, realBlock.getStateManager().getProperties());

            // Block states/permutations
            List<CustomBlockPermutation> permutations = new ArrayList<>();
            for (BlockState state : realBlock.getStateManager().getStates()) {
                CustomBlockComponents.Builder stateComponentBuilder = CustomBlockComponents.builder();

                // Hardness
                float hardness = state.getHardness(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
                stateComponentBuilder.destructibleByMining(hardness);

                // Obtain model data from polymers internal api
                TranslationManager.INCLUDE_OPTIONAL_TEXTURES_HACK = true;
                BlockState polymerBlockState = block.getPolymerBlockState(state, PacketContext.get());
                BlockResourceCreator creator = PolymerBlockResourceUtilsAccessor.getCREATOR();
                Either<PolymerBlockModel[], MultiPolymerBlockModel> polymerBlockModels = ((BlockResourceCreatorAccessor) (Object) creator).getModels().get(polymerBlockState);
                TranslationManager.INCLUDE_OPTIONAL_TEXTURES_HACK = false;

                // Flag to track if we fell back to the item model
                // (used for display-entity blocks like TSA Decorations furniture)
                boolean usingItemModelFallback = false;

                // Pre-resolved model + rotation from blockstate JSON fallback.
                // Set when polymerBlockModels == null but the block has its own blockstate JSON
                // (e.g. Terrestria slabs/stairs/fences patched via PolymerBlock overlay).
                Model preResolvedModel = null;
                int[] preResolvedRotation = null;

                if (polymerBlockModels == null) {
                    // Try reading the block's own blockstate JSON before falling back to furniture.
                    // This covers PolymerBlock-patched vanilla blocks (Terrestria, etc.) that map
                    // to vanilla states and thus have no entry in Polymer's BlockResourceCreator map,
                    // but DO have their own assets accessible via the classloader.
                    Pair<Model, int[]> bsResult = resolveBlockstateModel(identifier, state);
                    if (bsResult != null) {
                        preResolvedModel = bsResult.getLeft();
                        preResolvedRotation = bsResult.getRight();
                        LOGGER.debug("Resolved block {} via blockstate JSON fallback", identifier);
                    } else {
                        // Block has no Polymer block model and no resolvable blockstate JSON.
                        // Likely uses a Display Entity for visuals (TSA Decorations furniture, etc.).
                        LOGGER.debug("Models are null for blockstate {} — trying item model fallback", state);
                        usingItemModelFallback = true;
                    }
                } else if (polymerBlockModelsArePolymerEmpty(polymerBlockModels)) {
                    // The Polymer model exists but is the placeholder polymer:block/empty.
                    // This means the patch uses a Display Entity (BlockWithElementHolder) for
                    // visuals — exactly the case for Terrestria doors/saplings/slabs/signs
                    // patched via the polymer-patch-bundle. The mod's OWN blockstate JSON
                    // still has the real geometry, so try that first.
                    Pair<Model, int[]> bsResult = resolveBlockstateModel(identifier, state);
                    if (bsResult != null) {
                        preResolvedModel = bsResult.getLeft();
                        preResolvedRotation = bsResult.getRight();
                        LOGGER.debug("Resolved block {} via blockstate JSON (polymer:block/empty placeholder)", identifier);
                    } else {
                        usingItemModelFallback = true;
                    }
                }

                PolymerBlockModel[] listModels = null;
                if (!usingItemModelFallback && preResolvedModel == null) {
                    if (polymerBlockModels.left().isPresent()) {
                        listModels = polymerBlockModels.left().orElseThrow();
                    } else {
                        listModels = polymerBlockModels.right().orElseThrow().models().getFirst();
                    }

                    if (listModels.length == 0) {
                        LOGGER.warn("Models are empty for blockstate {}", state);
                        continue;
                    }
                }

                // Rotation
                TransformationComponent rotationComponent;
                if (usingItemModelFallback) {
                    rotationComponent = new TransformationComponent(0, 0, 0);
                } else if (preResolvedRotation != null) {
                    rotationComponent = new TransformationComponent(
                        (360 - preResolvedRotation[0]) % 360,
                        (360 - preResolvedRotation[1]) % 360, 0);
                } else {
                    PolymerBlockModel modelEntry = listModels[0];
                    rotationComponent = new TransformationComponent((360 - modelEntry.x()) % 360, (360 - modelEntry.y()) % 360, 0);
                }
                stateComponentBuilder.transformation(rotationComponent);

                // Geometry
                String renderMethod = state.isOpaque() ? "opaque" : "blend";

                // Resolve the model
                Model blockModel;
                if (usingItemModelFallback) {
                    // Block uses Display Entities and has no resolvable Java block model.
                    // The Filament/TSA furniture fallback has been removed, so there is
                    // nothing left to render for these blocks — skip them.
                    LOGGER.debug("Skipping display-entity block {} (no resolvable block model)", identifier);
                    continue;
                } else if (preResolvedModel != null) {
                    // Blockstate JSON path — model already resolved, pass it through
                    blockModel = preResolvedModel;
                } else {
                    blockModel = resolveModel(listModels[0].model());
                    if (blockModel == null) {
                        LOGGER.warn("Couldn't load model for blockstate {}", state);
                        continue;
                    }
                    // The Polymer model was registered but is a placeholder shell with no
                    // geometry (e.g. comforts:block/light_blue_cloth — only has particle
                    // texture). Try the blockstate JSON fallback, which understands patch
                    // namespaces and will look for the real geometry under
                    // <namespace>-polymer-patch:blockstates/<path>.json.
                    Model probeStitched = blockModel;
                    try {
                        probeStitched = new ModelStitcher(tolerantStitchProvider(), blockModel).stitch();
                        if (probeStitched == null) probeStitched = blockModel;
                    } catch (Exception ignored) { }
                    if (!hasElements(probeStitched)) {
                        Pair<Model, int[]> bsResult = resolveBlockstateModel(identifier, state);
                        if (bsResult != null && hasElements(bsResult.getLeft())) {
                            blockModel = bsResult.getLeft();
                            // Override rotation as well — the placeholder doesn't carry it
                            int[] r = bsResult.getRight();
                            stateComponentBuilder.transformation(new TransformationComponent(
                                (360 - r[0]) % 360, (360 - r[1]) % 360, 0));
                            LOGGER.debug("Substituted empty placeholder model for {} with patch blockstate geometry", identifier);
                        }
                    }
                }
                // Textures
                HashMap<String, ModelTexture> materials = new HashMap<>();
                Key modelParentKey = blockModel.parent();

                if (modelParentKey != null && parentFaceMap.containsKey(modelParentKey.value())) {
                    // Vanilla parent (cube_all, cube_bottom_top, cube_column, orientable)
                    String geometryIdentifier = "minecraft:geometry.full_block";

                    GeometryComponent geometryComponent = GeometryComponent.builder().identifier(geometryIdentifier).build();
                    stateComponentBuilder.geometry(geometryComponent);

                    ModelTextures textures = blockModel.textures();
                    Map<String, ModelTexture> textureMap = textures.variables();
                    List<Pair<String, String>> faceMap = parentFaceMap.get(modelParentKey.value());

                    for (Pair<String, String> face : faceMap) {
                        String javaFaceName = face.getLeft();
                        String bedrockFaceName = face.getRight();
                        if (!textureMap.containsKey(javaFaceName)) continue;
                        materials.put(bedrockFaceName, textureMap.get(javaFaceName));
                    }
                } else {
                    // Custom model
                    ModelStitcher.Provider provider = tolerantStitchProvider();
                    blockModel = new ModelStitcher(provider, blockModel).stitch(); // This resolves parent models (?)

                    Pair<String, ModelEntity> nameAndModel = JavaGeometryConverter.convert(blockModel);
                    if (nameAndModel == null) {
                        LOGGER.error("Couldn't convert model for blockstate {}", state);
                        continue;
                    }
                    String geometryId = nameAndModel.getLeft();
                    writeJsonToFile(nameAndModel.getRight(), blockModelsDir.resolve(geometryId + ".geo.json").toFile());

                    for (Map.Entry<String, ModelTexture> entry : blockModel.textures().variables().entrySet()) {
                        String key = entry.getKey();
                        ModelTexture texture = entry.getValue();
                        materials.put(key, texture);
                    }

                    GeometryComponent geometryComponent = GeometryComponent.builder().identifier(geometryId).build();
                    stateComponentBuilder.geometry(geometryComponent);
                }

                if (materials.isEmpty()) {
                    LOGGER.error("Couldn't generate materials for blockstate {}", state);
                    continue;
                }

                // Cross blocks (flowers, saplings, etc.) need alpha_test_single_sided so
                // Bedrock renders the transparent pixels correctly. modelParentKey is captured
                // before the geometry branch so it still holds the original parent after stitching.
                if (modelParentKey != null && "block/cross".equals(modelParentKey.value())) {
                    renderMethod = "alpha_test_single_sided";
                }

                // Biome tint detection.
                // Java edition uses `"tintindex": N` on a face inside the block model to mark
                // it as biome-tinted. Vanilla applies the tint via BlockColors registered in
                // code (grass tint for grass-like blocks, foliage tint for leaves, etc.).
                // Bedrock can replicate this via `tint_method` on the material_instance —
                // values: "grass", "default_foliage", "birch_foliage", "evergreen_foliage",
                // "dry_foliage", "water", "none".
                //
                // CRITICAL — the texture MUST be grayscale for tint to look right. Many mods
                // (sakura, japanese_maple, avocado) ship pre-colored leaf textures that have
                // tintindex on the face but DON'T expect a runtime multiplicative tint
                // (Java's BlockColors usually returns 0xFFFFFF white = no-op for those).
                // Applying the bedrock tint to those textures dyes them ugly green/brown.
                //
                // We only apply tint when:
                //   1. The model has tintindex set on at least one face, AND
                //   2. The actual texture is mostly grayscale (Java relies on that to multiply).
                // Per-material-instance check: each face may have a different texture, so each
                // entry decides independently.
                boolean modelHasTintindex = blockModel.elements() != null
                    && !blockModel.elements().isEmpty()
                    && blockModel.elements().stream()
                        .flatMap(el -> el.faces().values().stream())
                        .anyMatch(face -> face.tintIndex() >= 0);

                // Particles
                ModelTextures textures = blockModel.textures();
                if (!materials.containsKey("*")) {
                    ModelTexture texture = textures.particle() == null ? materials.values().iterator().next() : textures.particle();
                    materials.put("*", texture);
                }

                for (Map.Entry<String, ModelTexture> entry : materials.entrySet()) {
                    ModelTexture texture = entry.getValue();

                    while (texture.key() == null) {
                        String reference = texture.reference();
                        if (reference == null || !materials.containsKey(reference)) {
                            break;
                        }

                        texture = materials.get(reference);
                    }

                    if (texture.key() == null) {
                        LOGGER.warn("Texture for block {} on side {} is missing", identifier, entry.getKey());
                        continue;
                    }

                    String textureName = texture.key().asString();
                    if (!textureDataObject.has(textureName)) {
                        Identifier textureIdentifier = Identifier.of(textureName);

                        String texturePath = "textures/" + textureIdentifier.getPath();
                        // Include the namespace in the destination filename so two mods
                        // that ship a texture with the same path (e.g. terrestria's
                        // cherry_leaves.png and traverse's cherry_leaves.png) don't
                        // overwrite each other on disk — that collision causes random
                        // blocks to show the wrong texture or fall back to Bedrock's
                        // magenta/black missing-texture default once the pack grows.
                        String bedrockPath = ResourceHelper.javaToBedrockTexture(
                            "textures/" + textureIdentifier.getNamespace() + "/" + textureIdentifier.getPath());

                        JsonObject thisTexture = new JsonObject();
                        thisTexture.addProperty("textures", bedrockPath);
                        textureDataObject.add(textureName, thisTexture);

                        ResourceHelper.copyResource(textureIdentifier.getNamespace(), texturePath + ".png", packRoot.resolve(bedrockPath + ".png"));
                    }

                    MaterialInstance.Builder matBuilder = MaterialInstance.builder()
                            .renderMethod(renderMethod)
                            .texture(textureName)
                            .faceDimming(true)
                            .ambientOcclusion(blockModel.ambientOcclusion());

                    // Apply biome tint only when:
                    //   1. The model has tintindex on at least one face, AND
                    //   2. THIS specific texture is grayscale (so multiplicative tinting
                    //      will actually colourise it instead of mucking with already-coloured pixels).
                    // Pre-colored leaves like sakura/japanese_maple/avocado fail check (2),
                    // so they keep their original colors and don't get re-tinted.
                    if (modelHasTintindex && !shouldSkipTint(identifier)) {
                        Identifier textureIdentifier = Identifier.of(textureName);
                        Path texturePngPath = packRoot.resolve(
                            ResourceHelper.javaToBedrockTexture(
                                "textures/" + textureIdentifier.getNamespace() + "/" + textureIdentifier.getPath()) + ".png");
                        if (isGrayscaleTexture(texturePngPath)) {
                            String tint = inferTintMethod(identifier);
                            try {
                                matBuilder.getClass().getMethod("tintMethod", String.class)
                                    .invoke(matBuilder, tint);
                            } catch (Exception e) {
                                LOGGER.debug("Geyser API doesn't support tintMethod (older build) — block {} renders gray", identifier);
                            }
                        }
                    }

                    stateComponentBuilder.materialInstance(entry.getKey(), matBuilder.build());
                }

                // Collision
                VoxelShape collisionBox = state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
                stateComponentBuilder.collisionBox(voxelShapeToBoxComponent(collisionBox));

                VoxelShape outlineBox = state.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
                stateComponentBuilder.selectionBox(voxelShapeToBoxComponent(outlineBox));

                stateComponentBuilder.lightEmission(state.getLuminance());

                CustomBlockComponents stateComponents = stateComponentBuilder.build();
                if (state.getProperties().isEmpty()) {
                    builder.components(stateComponents);
                    continue;
                }

                // Conditions
                // Essentially telling Bedrock what components to activate when
                List<String> conditions = new ArrayList<>();
                for (Property<?> property : state.getProperties()) {
                    if (property instanceof IntProperty intProperty && isOversized(intProperty)) {
                        // Mirror the base-16 split used when registering/mapping the property.
                        int idx = sortedValues(intProperty).indexOf(state.get(intProperty));
                        conditions.add("q.block_property('" + property.getName() + "') == " + (idx % MAX_BEDROCK_PROPERTY_VALUES));
                        conditions.add("q.block_property('" + property.getName() + "_hi') == " + (idx / MAX_BEDROCK_PROPERTY_VALUES));
                        continue;
                    }

                    String propertyValue = state.get(property).toString();
                    if (property instanceof EnumProperty<?>) {
                        propertyValue = "'" + propertyValue.toLowerCase() + "'";
                    }

                    conditions.add("q.block_property('%name%') == %value%"
                            .replace("%name%", property.getName())
                            .replace("%value%", propertyValue));
                }

                String stateCondition = String.join(" && ", conditions);
                permutations.add(new CustomBlockPermutation(stateComponents, stateCondition));
            }
            builder.permutations(permutations);

            // Sounds
            // blocks.json
            String blockAsString = identifier.toString();
            JsonObject thisBlockObject = new JsonObject();
            thisBlockObject.addProperty("sound", blockAsString);
            blocksJson.add(blockAsString, thisBlockObject);

            // sounds.json
            BlockSoundGroup soundGroup = realBlock.getDefaultState().getSoundGroup();
            // base sounds (break, hit, place)
            JsonObject baseSoundObject = new JsonObject();
            baseSoundObject.addProperty("pitch", soundGroup.getPitch());
            baseSoundObject.addProperty("volume", soundGroup.getVolume());

            JsonObject soundEventsObject = new JsonObject();
            soundEventsObject.addProperty("break", SoundUtils.translatePlaySound(soundGroup.getBreakSound().id().toString()));
            soundEventsObject.addProperty("hit", SoundUtils.translatePlaySound(soundGroup.getHitSound().id().toString()));
            soundEventsObject.addProperty("place", SoundUtils.translatePlaySound(soundGroup.getPlaceSound().id().toString()));
            baseSoundObject.add("events", soundEventsObject);

            blockSoundsObject.add(blockAsString, baseSoundObject);
            // interactive sounds
            JsonObject interactiveSoundObject = new JsonObject();
            interactiveSoundObject.addProperty("pitch", soundGroup.getPitch());
            interactiveSoundObject.addProperty("volume", soundGroup.getVolume() * .4); // The multiplier is arbitrary, its just too loud by default :(

            JsonObject interactiveEventsObject = new JsonObject();
            interactiveEventsObject.addProperty("fall", SoundUtils.translatePlaySound(soundGroup.getFallSound().id().toString()));
            interactiveEventsObject.addProperty("jump", SoundUtils.translatePlaySound(soundGroup.getStepSound().id().toString()));
            interactiveEventsObject.addProperty("step", SoundUtils.translatePlaySound(soundGroup.getStepSound().id().toString()));
            interactiveEventsObject.addProperty("land", SoundUtils.translatePlaySound(soundGroup.getFallSound().id().toString()));
            interactiveSoundObject.add("events", interactiveEventsObject);
            interactiveSoundsObject.add(blockAsString, interactiveSoundObject);

            // Registration
            NonVanillaCustomBlockData data = builder.build();
            event.register(data);
            registeredBlocks.add(block);

            // Registering the block states
            for (BlockState state : realBlock.getStateManager().getStates()) {
                CustomBlockState.Builder stateBuilder = data.blockStateBuilder();

                for (Property<?> property : state.getProperties()) {
                    switch (property) {
                        case IntProperty intProperty -> {
                            int value = state.get(intProperty);
                            if (isOversized(intProperty)) {
                                int idx = sortedValues(intProperty).indexOf(value);
                                stateBuilder.intProperty(property.getName(), idx % MAX_BEDROCK_PROPERTY_VALUES);
                                stateBuilder.intProperty(property.getName() + "_hi", idx / MAX_BEDROCK_PROPERTY_VALUES);
                            } else {
                                stateBuilder.intProperty(property.getName(), value);
                            }
                        }
                        case BooleanProperty booleanProperty ->
                                stateBuilder.booleanProperty(property.getName(), state.get(booleanProperty));
                        case EnumProperty<?> enumProperty ->
                                stateBuilder.stringProperty(enumProperty.getName(), state.get(enumProperty).toString().toLowerCase());
                        default ->
                                throw new IllegalArgumentException("Unknown property type: " + property.getClass().getName());
                    }
                }

                CustomBlockState customBlockState = stateBuilder.build();
                JavaBlockState.Builder javaBlockState = JavaBlockState.builder();
                javaBlockState.blockHardness(state.getHardness(EmptyBlockView.INSTANCE, BlockPos.ORIGIN));

                VoxelShape shape = state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
                if (shape.isEmpty()) {
                    javaBlockState.collision(new JavaBoundingBox[0]);
                } else {
                    Box box = shape.getBoundingBox();
                    javaBlockState.collision(new JavaBoundingBox[]{
                        new JavaBoundingBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
                    });
                }

                javaBlockState.javaId(Block.getRawIdFromState(state));
                javaBlockState.identifier(BlockArgumentParser.stringifyBlockState(state));
                // A block counts as "waterlogged" for Bedrock whenever its ACTUAL fluid
                // state is water — not only when it carries the vanilla WATERLOGGED
                // property. Farmers Delight rice (RiceBlock) has NO waterlogged property:
                // it overrides getFluidState() to always return a water source, so on
                // Java it renders correctly planted in water. Reading only the property
                // returned false, so Geyser was told the override wasn't waterlogged and
                // Bedrock removed the water, planting the rice on dry ground. Checking the
                // fluid state fixes rice (and any kelp-like/intrinsic-water block), while
                // OR-ing the property keeps every existing SimpleWaterloggedBlock exactly
                // as before (their getFluidState already returns water when WATERLOGGED).
                javaBlockState.waterlogged(
                        state.get(Properties.WATERLOGGED, false)
                                || state.getFluidState().isIn(FluidTags.WATER));
                if (realBlock.asItem() != null) javaBlockState.pickItem(Registries.ITEM.getId(realBlock.asItem()).toString());
                javaBlockState.canBreakWithHand(state.isToolRequired());

                PistonBehavior pistonBehavior = state.getPistonBehavior();
                javaBlockState.pistonBehavior(pistonBehavior == PistonBehavior.IGNORE ? "NORMAL" : pistonBehavior.name());

                event.registerOverride(javaBlockState.build(), customBlockState);
            }
        });

        terrainTextureObject.add("texture_data", textureDataObject);
        soundsJson.add("block_sounds", blockSoundsObject);
        interactiveSoundsWrapper.add("block_sounds", interactiveSoundsObject);
        soundsJson.add("interactive_sounds", interactiveSoundsWrapper);
        writeJsonToFile(terrainTextureObject, textureDir.resolve("terrain_texture.json").toFile());
        writeJsonToFile(blocksJson, packRoot.resolve("blocks.json").toFile());
        writeJsonToFile(soundsJson, packRoot.resolve("sounds.json").toFile());
        markResourcesProvided();

        // Free per-generation caches now that the pack is written. These hold strings
        // and a few KB of results; not huge, but the bigger benefit is releasing GC
        // pressure once the one-time pack build is done. Safe to drop: these are only
        // used during pack generation; if a regen happens later we'll rebuild them.
        GRAYSCALE_CACHE.clear();
        ResourceHelper.clearCaches();
    }

    @Override
    public void register(EventBus<EventRegistrar> eventBus, Path packRoot) {
        eventBus.subscribe(this, GeyserDefineCustomBlocksEvent.class, event -> handle(event, packRoot));
    }
}
