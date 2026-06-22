package lol.sylvie.bedframe.geyser.translator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lol.sylvie.bedframe.geyser.Translator;
import lol.sylvie.bedframe.util.BedframeConstants;
import lol.sylvie.bedframe.util.ResourceHelper;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ShieldItem;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.geysermc.geyser.api.event.EventBus;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Generates Bedrock {@code attachables/<item>.json} entries plus copies the worn-on-body
 * textures into {@code textures/entity/equipment/...} for every modded item that has a
 * Java {@code minecraft:equippable} data component with an asset id.
 *
 * <p>Without this, modded armor and shields show the correct icon in the inventory
 * (handled by {@link ItemTranslator}) but render invisible when actually equipped on a
 * Bedrock player. The vanilla armor texture is what Bedrock falls back to, but custom
 * armor needs its own attachable definition pointing to the modded skin.
 *
 * <p>Logic ported from Hydraulic's {@code ArmorPackModule} (Mojang mappings) to
 * Bedframe's runtime model (Yarn mappings + direct mod-jar asset reading).
 */
public class EquipmentTranslator extends Translator {
    private static final Logger LOGGER = LoggerFactory.getLogger("bedframe-equipment");

    /** Bedrock armor texture path template: textures/entity/equipment/<layer>/<name>.png */
    private static final String BEDROCK_ARMOR_TEXTURE_TEMPLATE = "textures/entity/equipment/%s/%s.png";

    @Override
    public void register(EventBus<EventRegistrar> eventBus, Path packRoot) {
        try {
            emitAttachables(packRoot);
        } catch (Exception e) {
            LOGGER.error("Couldn't emit equipment attachables", e);
        }
        markResourcesProvided();
    }

    private void emitAttachables(Path packRoot) throws IOException {
        Path attachablesDir = packRoot.resolve("attachables");
        Files.createDirectories(attachablesDir);

        int count = 0;
        for (Identifier itemId : Registries.ITEM.getIds()) {
            if ("minecraft".equals(itemId.getNamespace())) continue;
            Item item = Registries.ITEM.get(itemId);
            if (item == Items.AIR) continue;

            // Two distinct attachable cases: armor (uses Equippable component) and shield
            // (no equippable but extends ShieldItem). Each branch emits its own JSON shape.
            EquippableComponent equip = item.getComponents().get(DataComponentTypes.EQUIPPABLE);
            if (equip != null && equip.assetId().isPresent()) {
                if (emitArmorAttachable(item, itemId, equip, packRoot)) count++;
            } else if (item instanceof ShieldItem) {
                if (emitShieldAttachable(item, itemId, packRoot)) count++;
            }
        }

        if (count > 0) {
            LOGGER.info("Emitted {} equipment attachables", count);
        }
    }

    /**
     * Emits an attachable for a piece of armor. Reads the mod's Java
     * {@code assets/<ns>/equipment/<asset>.json}, picks the layer that matches the
     * armor's equip slot, copies the worn texture into the bedrock pack and writes the
     * Bedrock attachable JSON.
     */
    private boolean emitArmorAttachable(Item item, Identifier itemId, EquippableComponent equip, Path packRoot) {
        EquipmentSlot slot = equip.slot();
        String layerType = layerForSlot(slot);
        if (layerType == null) {
            // Not a humanoid slot — it may be animal body armor (horse/wolf), which Java
            // marks via the equippable's allowed-entities set rather than the slot. Mirrors
            // Hydraulic's ArmorPackModule, which falls back to HORSE_BODY/WOLF_BODY here.
            layerType = animalLayerFor(equip);
            if (layerType == null) return false; // genuinely unsupported slot
        }

        Identifier assetId = equip.assetId().orElseThrow().getValue();

        // Read assets/<ns>/equipment/<assetPath>.json from the mod's classpath.
        JsonObject equipJson = readJsonOrNull(assetId.getNamespace(), "equipment/" + assetId.getPath() + ".json");
        if (equipJson == null) return false;

        JsonObject layers = equipJson.has("layers") ? equipJson.getAsJsonObject("layers") : null;
        if (layers == null || !layers.has(layerType)) return false;

        JsonArray layerArr = layers.getAsJsonArray(layerType);
        if (layerArr.isEmpty()) return false;

        JsonObject firstLayer = layerArr.get(0).getAsJsonObject();
        if (!firstLayer.has("texture")) return false;
        Identifier texId = Identifier.of(firstLayer.get("texture").getAsString());

        // Copy texture: assets/<ns>/textures/entity/equipment/<layerType>/<path>.png
        // → bedrock pack textures/entity/equipment/<layerType>/<path>.png
        String javaTexPath = "textures/entity/equipment/" + layerType + "/" + texId.getPath() + ".png";
        String bedrockTexPath = String.format(BEDROCK_ARMOR_TEXTURE_TEMPLATE, layerType, texId.getPath());
        if (!copyResource(texId.getNamespace(), javaTexPath, packRoot.resolve(bedrockTexPath))) {
            return false;
        }

        // Build the attachable JSON.
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");

        JsonObject description = new JsonObject();
        description.addProperty("identifier", itemId.toString());

        JsonObject materials = new JsonObject();
        materials.addProperty("default", "armor");
        materials.addProperty("enchanted", "armor_enchanted");
        description.add("materials", materials);

        JsonObject scripts = new JsonObject();
        scripts.addProperty("parent_setup", "variable.chest_layer_visible = 0.0;");
        description.add("scripts", scripts);

        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.armor");
        description.add("render_controllers", renderControllers);

        JsonObject items = new JsonObject();
        // Known limitation (inherited from Hydraulic): when the player simply HOLDS this
        // item in the hotbar/mainhand, Bedrock will also render it on the equipped slot.
        // The proper fix requires registering a Bedrock-side `minecraft:wearable` component
        // on the item itself (like vanilla armor does) so that Bedrock only triggers the
        // attachable when the item is actually in an armor slot. Bedframe currently does
        // not emit the wearable component, so this query is the same as Hydraulic uses.
        // Tried tightening with `query.armor_slot == N` but that breaks rendering for
        // most clients since armor_slot is undefined in the attachable render context.
        items.addProperty(itemId + "_item",
            "query.is_owner_identifier_any('minecraft:player')");
        description.add("item", items);

        JsonObject textures = new JsonObject();
        textures.addProperty("default", bedrockTexPath.replace(".png", ""));
        textures.addProperty("enchanted", "textures/misc/enchanted_actor_glint");
        description.add("textures", textures);

        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", geometryForLayer(layerType, slot));
        description.add("geometry", geometry);

        JsonObject attachable = new JsonObject();
        attachable.add("description", description);
        root.add("minecraft:attachable", attachable);

        Path outPath = packRoot.resolve("attachables/" + itemId.getPath() + ".json");
        return writeJson(root, outPath);
    }

    /**
     * Emits an attachable for a custom shield. Bedrock has a builtin shield model;
     * we override its texture by pointing the attachable at our copied .png.
     *
     * <p>The mod typically ships a shield texture at
     * {@code assets/<ns>/textures/item/<shield_name>.png}. We try that first; if the
     * mod uses the new {@code entity/shield} folder for the on-hand model we fall back
     * to that.
     */
    private boolean emitShieldAttachable(Item item, Identifier itemId, Path packRoot) {
        String[] candidatePaths = {
            "textures/item/" + itemId.getPath() + ".png",
            "textures/entity/shield/" + itemId.getPath() + ".png"
        };
        String chosen = null;
        for (String p : candidatePaths) {
            if (ResourceHelper.hasResource(itemId.getNamespace(), p)) { chosen = p; break; }
        }
        if (chosen == null) return false;

        // Copy as textures/entity/shield/<modid>_<name>.png to avoid colliding with
        // vanilla shield textures.
        String bedrockTexPath = "textures/entity/shield/" + itemId.getNamespace() + "_" + itemId.getPath() + ".png";
        if (!copyResource(itemId.getNamespace(), chosen, packRoot.resolve(bedrockTexPath))) {
            return false;
        }

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");

        JsonObject description = new JsonObject();
        description.addProperty("identifier", itemId.toString());

        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest");
        materials.addProperty("enchanted", "entity_alphatest_glint");
        description.add("materials", materials);

        JsonObject textures = new JsonObject();
        textures.addProperty("default", bedrockTexPath.replace(".png", ""));
        textures.addProperty("enchanted", "textures/misc/enchanted_actor_glint");
        description.add("textures", textures);

        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", "geometry.shield");
        description.add("geometry", geometry);

        JsonObject scripts = new JsonObject();
        JsonArray preAnim = new JsonArray();
        preAnim.add("variable.is_first_person = context.is_first_person ?? 0.0;");
        scripts.add("pre_animation", preAnim);
        description.add("scripts", scripts);

        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.item_default");
        description.add("render_controllers", renderControllers);

        JsonObject attachable = new JsonObject();
        attachable.add("description", description);
        root.add("minecraft:attachable", attachable);

        Path outPath = packRoot.resolve("attachables/" + itemId.getPath() + ".json");
        return writeJson(root, outPath);
    }

    /** Maps a Java equipment slot to the Bedrock equipment layer name used in folder paths. */
    private static String layerForSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD, CHEST, FEET -> "humanoid";
            case LEGS -> "humanoid_leggings";
            default -> null;
        };
    }

    /** Maps a Java equipment slot to the Bedrock player-armor geometry id. */
    private static String geometryForSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "geometry.player.armor.helmet";
            case CHEST -> "geometry.player.armor.chestplate";
            case FEET -> "geometry.player.armor.boots";
            case LEGS -> "geometry.player.armor.leggings";
            default -> "geometry.player.armor.chestplate";
        };
    }

    /**
     * Returns the Bedrock equipment layer name for animal body armor (horse/wolf) by
     * inspecting the equippable's allowed-entities set, or {@code null} if it isn't animal
     * armor. The returned name matches both the Java equipment-JSON layer key
     * ({@code horse_body}/{@code wolf_body}) and the texture folder, so the existing layer
     * lookup and texture-copy logic work unchanged. Mirrors Hydraulic's ArmorPackModule.
     */
    private static String animalLayerFor(EquippableComponent equip) {
        java.util.Optional<net.minecraft.registry.entry.RegistryEntryList<net.minecraft.entity.EntityType<?>>>
            allowed = equip.allowedEntities();
        if (allowed.isEmpty()) return null;
        net.minecraft.registry.entry.RegistryEntryList<net.minecraft.entity.EntityType<?>> entities = allowed.get();
        if (entities.contains(Registries.ENTITY_TYPE.getEntry(net.minecraft.entity.EntityType.HORSE)))
            return "horse_body";
        if (entities.contains(Registries.ENTITY_TYPE.getEntry(net.minecraft.entity.EntityType.WOLF)))
            return "wolf_body";
        return null;
    }

    /**
     * Bedrock geometry id for an emitted armor attachable. Humanoid pieces use the player
     * armor geometries; animal body armor uses the corresponding mob armor geometry.
     *
     * <p><b>Limitation (the same open TODO Hydraulic has):</b> horse/wolf body armor is only
     * partially supported through this resource-pack path. The texture and attachable are
     * emitted, but Bedrock renders mount/pet armor as part of the animal entity rather than
     * as a player attachable, so full in-world rendering on the animal still needs
     * Geyser-side support that doesn't exist yet. Humanoid armor is unaffected.
     */
    private static String geometryForLayer(String layerType, EquipmentSlot slot) {
        return switch (layerType) {
            case "horse_body" -> "geometry.horse_armor";
            case "wolf_body" -> "geometry.wolf_armor";
            default -> geometryForSlot(slot);
        };
    }

    // ── small helpers ────────────────────────────────────────────────────────────

    private static JsonObject readJsonOrNull(String namespace, String path) {
        try {
            return ResourceHelper.readJsonResource(namespace, path);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean copyResource(String namespace, String path, Path dest) {
        try (InputStream s = ResourceHelper.getResource("assets/" + namespace + "/" + path)) {
            if (s == null) return false;
            Files.createDirectories(dest.getParent());
            Files.copy(s, dest, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            LOGGER.debug("Couldn't copy resource {}/{}: {}", namespace, path, e.getMessage());
            return false;
        }
    }

    private static boolean writeJson(JsonObject root, Path outPath) {
        try {
            Files.createDirectories(outPath.getParent());
            try (java.io.FileWriter w = new java.io.FileWriter(outPath.toFile())) {
                BedframeConstants.GSON.toJson(root, w);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("Couldn't write attachable {}", outPath, e);
            return false;
        }
    }
}
