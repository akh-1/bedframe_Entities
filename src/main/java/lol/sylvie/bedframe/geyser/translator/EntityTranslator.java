package lol.sylvie.bedframe.geyser.translator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lol.sylvie.bedframe.api.BedframeEntities;
import lol.sylvie.bedframe.api.BedframeEntities.Carrier;
import lol.sylvie.bedframe.api.BedframeEntities.Registration;
import lol.sylvie.bedframe.geyser.FactoryToolsDiscovery;
import lol.sylvie.bedframe.geyser.GenericEntityDiscovery;
import lol.sylvie.bedframe.geyser.Translator;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.event.EventBus;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static lol.sylvie.bedframe.util.BedframeConstants.LOGGER;

/**
 * Generalises the bedrocktest pipeline: for every entity registered via
 * {@link BedframeEntities}, generate the Bedrock client_entity override of its carrier,
 * the render controllers, the geometry and texture, and register an int property
 * "bedframe:variant" on the carrier so the right variant renders.
 *
 * Add {@code new EntityTranslator()} to the translator list in TranslationManager.
 */
public class EntityTranslator extends Translator {

    /** carrier bedrock id -> its variant property, captured for the runtime pusher. */
    public static final Map<String, GeyserIntEntityProperty> VARIANT_PROPS = new ConcurrentHashMap<>();

    @Override
    public void register(EventBus<EventRegistrar> eventBus, Path packRoot) {
        // Auto-discover BIL/Blockbench mobs (e.g. Tom's Mobs) that ship a .bbmodel,
        // Auto-discovery: scan installed mods for entity models/textures (bbmodel, geckolib, and
        // FactoryTools Polymer patches), convert them, and register them. Manual
        // BedframeEntities.register() calls (for your own mods) are picked up too.
        GenericEntityDiscovery.discover(BedframeEntities.Carrier.POLAR_BEAR);     // bbmodel / ajblueprint / geckolib
        FactoryToolsDiscovery.discover(BedframeEntities.Carrier.POLAR_BEAR);      // FactoryTools PolyModelInstance (v1/v2)

        Map<String, List<Registration>> byCarrier = BedframeEntities.byCarrier();
        if (byCarrier.isEmpty()) {
            // Nothing registered - must still mark provided so pack generation isn't blocked.
            markResourcesProvided();
            return;
        }

        eventBus.subscribe(this, GeyserDefineEntityPropertiesEvent.class, event -> {
            try {
                writePack(packRoot, byCarrier);
                for (Map.Entry<String, List<Registration>> e : byCarrier.entrySet()) {
                    String carrierId = e.getKey();
                    int max = e.getValue().stream().mapToInt(Registration::variant).max().orElse(0);
                    String ns = namespaceOf(carrierId);
                    String path = pathOf(carrierId);

                    GeyserIntEntityProperty prop = event.registerIntegerProperty(
                            org.geysermc.geyser.api.util.Identifier.of(ns, path),
                            org.geysermc.geyser.api.util.Identifier.of("bedframe", "variant"),
                            0, max, 0);
                    VARIANT_PROPS.put(carrierId, prop);
                    LOGGER.info("[bedframe] Registered variant property on {} (0..{})", carrierId, max);
                }
            } catch (Exception ex) {
                LOGGER.error("[bedframe] EntityTranslator failed to generate entity resources", ex);
            }
            markResourcesProvided();
        });
    }

    // ----------------------------------------------------------------------

    private void writePack(Path packRoot, Map<String, List<Registration>> byCarrier) throws IOException {
        Path entityDir = Files.createDirectories(packRoot.resolve("entity"));
        Path rcDir = Files.createDirectories(packRoot.resolve("render_controllers"));
        Path geoDir = Files.createDirectories(packRoot.resolve("models").resolve("entity"));
        Path texDir = Files.createDirectories(packRoot.resolve("textures").resolve("entity"));
        Path animDir = Files.createDirectories(packRoot.resolve("animations"));
        Path acDir = Files.createDirectories(packRoot.resolve("animation_controllers"));

        for (Map.Entry<String, List<Registration>> entry : byCarrier.entrySet()) {
            String carrierId = entry.getKey();
            List<Registration> regs = entry.getValue();
            Carrier carrier = regs.get(0).carrier();
            String cp = pathOf(carrierId);

            // Maps shared by the client_entity description.
            JsonObject textures = new JsonObject();
            textures.addProperty("default", carrier.vanillaTexture());
            JsonObject geometry = new JsonObject();
            geometry.addProperty("default", carrier.vanillaGeometry());
            JsonObject materials = new JsonObject();
            // Use the alpha-test entity material rather than the carrier's opaque vanilla material
            // (e.g. "polar_bear"). The opaque material renders alpha=0 pixels as solid black, so any
            // custom texture with transparency shows black where it should be see-through. alphatest
            // discards fully-transparent pixels (cutout) and is harmless for opaque textures, so the
            // vanilla carrier (variant 0) is unaffected.
            materials.addProperty("default", "entity_alphatest");

            // render_controllers: list inside client_entity + bodies in the controller file.
            JsonArray rcList = new JsonArray();
            JsonObject controllers = new JsonObject();

            // animations: short-name -> id map in client_entity, plus scripts.animate entries
            // (one per animated variant, gated by bedframe:variant) and the controller bodies.
            JsonObject animMap = new JsonObject();
            JsonArray animateScript = new JsonArray();
            JsonObject animControllers = new JsonObject();

            String defaultId = "controller.render.bedframe_" + cp + "_default";
            rcList.add(rcEntry(defaultId, "query.property('bedframe:variant') == 0"));
            controllers.add(defaultId, controllerBody("Geometry.default", "Texture.default", "Material.default"));

            java.util.Set<String> writtenGeometry = new java.util.HashSet<>();

            for (Registration r : regs) {
                String safe = safeName(r.javaType());     // "namespace/path"
                String key = "v" + r.variant();
                // Unique texture path PER VARIANT (many variants of one type share a geometry but
                // each needs its own texture - otherwise they'd overwrite each other on disk).
                String texRel = safe + "/v" + r.variant();

                textures.addProperty(key, "textures/entity/" + texRel);
                geometry.addProperty(key, r.geometryId());

                String cid = "controller.render.bedframe_" + cp + "_" + key;
                rcList.add(rcEntry(cid, "query.property('bedframe:variant') == " + r.variant()));
                controllers.add(cid, controllerBody("Geometry." + key, "Texture." + key, "Material.default"));

                // Geometry: write once per geometry id (variants that share a model reuse it).
                // A blank geometryJson means "reference a built-in Bedrock geometry" (e.g. a vanilla
                // carrier's own geometry like geometry.creeper) - just point at the id, write no file.
                if (r.geometryJson() != null && !r.geometryJson().isBlank()
                        && writtenGeometry.add(r.geometryId())) {
                    Path geoOut = geoDir.resolve(safe + "/" + sanitize(r.geometryId()) + ".geo.json");
                    Files.createDirectories(geoOut.getParent());
                    Files.writeString(geoOut, r.geometryJson());
                }

                // Texture: write per variant.
                Path texOut = texDir.resolve(texRel + ".png");
                Files.createDirectories(texOut.getParent());
                Files.write(texOut, r.texturePng());

                // Animations (optional): write the .animation.json, build a per-variant
                // animation controller (idle/move/run by speed), and gate the whole controller
                // to this variant via scripts.animate so it can't drive other mobs on this carrier.
                if (r.hasAnimations()) {
                    Path animOut = animDir.resolve(safe + ".animation.json");
                    Files.createDirectories(animOut.getParent());
                    Files.writeString(animOut, r.animationsJson());

                    String idPrefix = "animation.bedframe." + safe.replace('/', '.');
                    // Controller id must be unique PER VARIANT: mobs like the chocobo register many
                    // variants under one java type (shared geometry), so a per-type id would collide
                    // and only the last variant's controller would survive.
                    String ctrlId = "controller.animation.bedframe." + safe.replace('/', '.') + "_v" + r.variant();
                    String sn = "v" + r.variant() + "_";

                    // Expose each clip + the controller under globally-unique short names.
                    for (String clip : r.animationClips()) {
                        animMap.addProperty(sn + clip, idPrefix + "." + clip);
                    }
                    animMap.addProperty(sn + "ctrl", ctrlId);

                    animControllers.add(ctrlId, animController(sn, r.animationClips()));

                    JsonObject gate = new JsonObject();
                    gate.addProperty(sn + "ctrl", "query.property('bedframe:variant') == " + r.variant());
                    animateScript.add(gate);
                }
            }

            JsonObject desc = new JsonObject();
            desc.addProperty("identifier", carrierId);
            desc.add("materials", materials);
            desc.add("textures", textures);
            desc.add("geometry", geometry);
            if (animMap.size() > 0) {
                desc.add("animations", animMap);
                JsonObject scripts = new JsonObject();
                scripts.add("animate", animateScript);
                desc.add("scripts", scripts);
            }
            desc.add("render_controllers", rcList);

            JsonObject clientEntity = new JsonObject();
            clientEntity.addProperty("format_version", "1.10.0");
            JsonObject wrap = new JsonObject();
            wrap.add("description", desc);
            clientEntity.add("minecraft:client_entity", wrap);
            writeJsonToFile(clientEntity, entityDir.resolve("bedframe_" + cp + ".entity.json").toFile());

            JsonObject rcFile = new JsonObject();
            rcFile.addProperty("format_version", "1.10.0");
            rcFile.add("render_controllers", controllers);
            writeJsonToFile(rcFile, rcDir.resolve("bedframe_" + cp + ".render_controllers.json").toFile());

            if (animControllers.size() > 0) {
                JsonObject acFile = new JsonObject();
                acFile.addProperty("format_version", "1.10.0");
                acFile.add("animation_controllers", animControllers);
                writeJsonToFile(acFile, acDir.resolve("bedframe_" + cp + ".animation_controllers.json").toFile());
            }
        }
    }

    private static JsonObject rcEntry(String controllerId, String condition) {
        JsonObject o = new JsonObject();
        o.addProperty(controllerId, condition);
        return o;
    }

    private static JsonObject controllerBody(String geometryRef, String textureRef, String materialRef) {
        JsonObject o = new JsonObject();
        o.addProperty("geometry", geometryRef);
        JsonArray mats = new JsonArray();
        JsonObject m = new JsonObject();
        m.addProperty("*", materialRef);
        mats.add(m);
        o.add("materials", mats);
        JsonArray texs = new JsonArray();
        texs.add(textureRef);
        o.add("textures", texs);
        return o;
    }

    /**
     * Builds a Bedrock animation controller (state machine) from the available clips.
     * States reference the per-variant short names ({@code sn} = "v<N>_"). Transitions are
     * driven by {@code query.modified_move_speed}; the carrier moves at the entity's real
     * speed so this reflects actual locomotion.
     *
     * Recognised clips: idle, walk, run. Others (e.g. death) are emitted to the
     * .animation.json but not wired here - they need an explicit trigger.
     */
    private static JsonObject animController(String sn, List<String> clips) {
        final double MOVE = 0.1, RUN = 0.7;
        boolean hasIdle = clips.contains("idle");
        boolean hasRun = clips.contains("run");
        boolean hasFly = clips.contains("fly");
        // Ground locomotion clip: walk, else swim. (fly is handled as an air state, not here.)
        String groundMove = clips.contains("walk") ? "walk"
                : clips.contains("swim") ? "swim"
                : null;
        // Where the fly state returns to on landing.
        String landState = hasIdle ? "idle" : (groundMove != null ? "move" : null);

        JsonObject states = new JsonObject();

        if (hasIdle) {
            JsonArray t = new JsonArray();
            if (hasFly) t.add(transitionObj("fly", "!query.is_on_ground"));
            if (groundMove != null) t.add(transitionObj("move", "query.modified_move_speed > " + MOVE));
            states.add("idle", stateArr(sn + "idle", t));
        }
        if (groundMove != null) {
            JsonArray t = new JsonArray();
            if (hasFly) t.add(transitionObj("fly", "!query.is_on_ground"));
            if (hasIdle) t.add(transitionObj("idle", "query.modified_move_speed <= " + MOVE));
            if (hasRun)  t.add(transitionObj("run",  "query.modified_move_speed > " + RUN));
            states.add("move", stateArr(sn + groundMove, t));
        }
        if (hasRun) {
            JsonArray t = new JsonArray();
            if (hasFly) t.add(transitionObj("fly", "!query.is_on_ground"));
            t.add(transitionObj("move", "query.modified_move_speed <= " + RUN));
            states.add("run", stateArr(sn + "run", t));
        }
        if (hasFly) {
            JsonArray t = new JsonArray();
            if (landState != null) t.add(transitionObj(landState, "query.is_on_ground"));
            states.add("fly", stateArr(sn + "fly", t));
        }

        // Fallback: mob has a clip but none of idle/walk/swim/run/fly (e.g. ice_cluster "spawn").
        String initial;
        if (hasIdle) initial = "idle";
        else if (groundMove != null) initial = "move";
        else if (hasFly) initial = "fly";
        else {
            initial = "default";
            if (!clips.isEmpty()) states.add("default", stateArr(sn + clips.get(0), new JsonArray()));
        }

        JsonObject ctrl = new JsonObject();
        ctrl.addProperty("initial_state", initial);
        ctrl.add("states", states);
        return ctrl;
    }

    private static JsonObject stateArr(String animShortName, JsonArray transitions) {
        JsonObject s = new JsonObject();
        JsonArray anims = new JsonArray();
        anims.add(animShortName);
        s.add("animations", anims);
        if (transitions != null && transitions.size() > 0) s.add("transitions", transitions);
        return s;
    }

    private static JsonObject transitionObj(String target, String condition) {
        JsonObject o = new JsonObject();
        o.addProperty(target, condition);
        return o;
    }

    private static String safeName(EntityType<?> type) {
        Identifier id = Registries.ENTITY_TYPE.getId(type);
        return id.getNamespace() + "/" + id.getPath();
    }

    /** Geometry id (e.g. "geometry.chococraft.chocobo_g0") -> a safe file stem. */
    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String namespaceOf(String id) {
        return id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
    }

    private static String pathOf(String id) {
        return id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
    }
}
