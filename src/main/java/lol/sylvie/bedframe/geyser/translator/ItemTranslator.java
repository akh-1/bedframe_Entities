package lol.sylvie.bedframe.geyser.translator;

import com.google.gson.JsonObject;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import lol.sylvie.bedframe.geyser.TranslationManager;
import lol.sylvie.bedframe.geyser.Translator;
import lol.sylvie.bedframe.util.BedframeConstants;
import lol.sylvie.bedframe.util.ResourceHelper;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.*;
import net.minecraft.block.Block;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.geysermc.geyser.api.event.EventBus;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.NonVanillaCustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserBlockPlacer;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserChargeable;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserItemDataComponents;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaFoodProperties;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaItemDataComponents;
import org.geysermc.geyser.api.util.CreativeCategory;
import xyz.nucleoid.packettweaker.PacketContext;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModEnvironment;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static lol.sylvie.bedframe.util.BedframeConstants.LOGGER;
import static lol.sylvie.bedframe.util.PathHelper.createDirectoryOrThrow;

public class ItemTranslator extends Translator {
    private final HashMap<Identifier, PolymerItem> items = new HashMap<>();
    // HashSet instead of ArrayList: isTexturedItem() is called from PolymerItemMixin and
    // PolymerItemUtilsMixin on EVERY item packet sent to a Bedrock player. ArrayList.contains()
    // is O(n) — with hundreds of registered items that's a linear scan per packet on the
    // network hot path. HashSet gives O(1) lookup. (Mirrors the same fix in BlockTranslator.)
    private static final java.util.HashSet<Item> registeredItems = new java.util.HashSet<>();

    /**
     * Mods whose items Hydraulic does not touch (internal Geyser plumbing, etc.).
     * When deferToHydraulic is true, items from these namespaces are still
     * registered by Bedframe instead of being skipped.
     */
    private static final Set<String> HYDRAULIC_IGNORED_MODS = Set.of(
        "geyser-fabric", "fabric-permissions-api-v0", "geyser-neoforge",
        "neoforge", "minecraft", "floodgate", "mixinextras", "cloud"
    );

    /**
     * Mods that Bedframe must always register regardless of whether Hydraulic
     * is loaded. Add a mod's namespace here when Hydraulic handles it silently
     * but the result is wrong and Bedframe's own fallback produces better output.
     */
    private static final Set<String> BEDFRAME_FORCE_REGISTER_MODS = Set.of();

    /**
     * Mods whose items Bedframe must register even though their items do NOT have a
     * {@link PolymerSyncedObject} associated. Used for mods that intentionally avoid
     * Polymer overlays (because they crash Java clients with polymer-bundled) but still
     * need to be declared to Geyser so that Bedrock clients don't crash on unknown item IDs.
     *
     * <p>For these items, Bedframe builds a synthetic identity {@link PolymerItem} in memory
     * (no registry changes) so that the rest of the pipeline can register the item with
     * Geyser. Texture extraction works if the mod's item descriptor (in
     * {@code assets/<namespace>/items/<path>.json}) resolves to a model whose root parent
     * is {@code minecraft:item/generated} or {@code minecraft:item/handheld}; otherwise the
     * item is registered but renders with the Geyser fallback icon — which is still better
     * than the Bedrock client crashing.
     */
    private static final Set<String> BEDFRAME_NON_POLYMER_FORCE_REGISTER_MODS = Set.of();

    /** True when the Hydraulic mod is present on this server. */
    private final boolean deferToHydraulic;

    public ItemTranslator(boolean deferToHydraulic) {
        this.deferToHydraulic = deferToHydraulic;
        Stream<Identifier> itemIds = Registries.ITEM.getIds().stream();
        itemIds.forEach(identifier -> {
            Item item = Registries.ITEM.get(identifier);
            // PolymerSyncedObject.getSyncedObject() is the canonical Polymer API for
            // resolving a PolymerItem for any Item — it covers both native implementations
            // (class implements PolymerItem) and overlay registrations (patch mods using
            // PolymerItem.registerOverlay). This is how Polymer itself does it internally
            // in PolymerItemUtils.getPolymerItemStack and isPolymerServerItem.
            if (PolymerSyncedObject.getSyncedObject(Registries.ITEM, item) instanceof PolymerItem polymerItem) {
                items.put(identifier, polymerItem);
                return;
            }

            // Force-register path: items from mods in BEDFRAME_NON_POLYMER_FORCE_REGISTER_MODS.
            // These mods opt out of Polymer overlays (because they crash Java clients with
            // polymer-bundled, e.g. easy_npc + polymer-bundled at the same time triggers
            // "No value with id NNNN" during item registry sync). We still want their items
            // declared to Geyser so Bedrock clients don't crash on unknown IDs.
            //
            // We build a synthetic identity PolymerItem in memory — no registry mutation —
            // that returns the item as-is. The rest of the BedFrame pipeline reads the
            // mod's item descriptor (assets/<ns>/items/<path>.json) and tries to extract
            // textures. If extraction fails (e.g. 3D models), the item is still declared
            // and renders with Geyser's fallback icon, which is acceptable.
            if (BEDFRAME_NON_POLYMER_FORCE_REGISTER_MODS.contains(identifier.getNamespace())) {
                items.put(identifier, new PolymerItem() {
                    @Override
                    public Item getPolymerItem(ItemStack itemStack, PacketContext context) {
                        return item;
                    }

                    @Override
                    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
                        return identifier;
                    }
                });
            }
        });
    }

    /**
     * Returns true when Hydraulic would normally handle this item, meaning
     * Bedframe should skip it to avoid double-registration.
     *
     * <p>Returns false (i.e. Bedframe registers it) when:
     * <ul>
     *   <li>The namespace is in {@link #BEDFRAME_FORCE_REGISTER_MODS}</li>
     *   <li>The namespace is in {@link #HYDRAULIC_IGNORED_MODS}</li>
     *   <li>The owning mod's environment is {@code server} or {@code *} — only
     *       pure {@code client}-environment mods are exclusively handled by
     *       Hydraulic. Mods with {@code *} include Polymer-native mods (like
     *       TSA Decorations) and Polymer-patched mods (like Enderscape via a
     *       patch mod), and must be registered by Bedframe.</li>
     * </ul>
     *
     * @deprecated Use {@link #deferToHydraulic} guard before calling this.
     */
    @Deprecated
    public boolean hydraulicWouldHandle(Identifier identifier) {
        String namespace = identifier.getNamespace();

        // Always let Bedframe register these mods, even when Hydraulic is present.
        if (BEDFRAME_FORCE_REGISTER_MODS.contains(namespace)) return false;

        // Hydraulic itself ignores these namespaces, so Bedframe must cover them.
        if (HYDRAULIC_IGNORED_MODS.contains(namespace)) return false;

        // Only defer to Hydraulic when the mod is EXCLUSIVELY client-side.
        // Mods with environment "*" (UNIVERSAL) include Polymer-native mods and
        // Polymer-patch targets — they must be handled by Bedframe.
        // ModEnvironment.matches(EnvType.CLIENT) returns true for both CLIENT and
        // UNIVERSAL, so we use == to distinguish them.
        return FabricLoader.getInstance()
            .getModContainer(namespace)
            .map(container -> container.getMetadata().getEnvironment() == ModEnvironment.CLIENT)
            .orElse(false);
    }

    private void forEachItem(BiConsumer<Identifier, PolymerItem> function) {
        for (Map.Entry<Identifier, PolymerItem> entry : items.entrySet()) {
            try {
                function.accept(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                LOGGER.error("Couldn't load item {}", entry.getKey(), e);
            }
        }
    }

    public static boolean isTexturedItem(Item item) {
        return registeredItems.contains(item);
    }

    /**
     * Walks a 1.21.4+ item descriptor model selector tree and returns the first
     * concrete {@code minecraft:model} model id it finds, or null. Handles the
     * standard selector types: {@code condition}, {@code select},
     * {@code composite}, {@code range_dispatch}, and plain {@code model}.
     *
     * <p>Examples of structures handled (all from real mods like Enderscape):
     * <pre>
     * { "type": "minecraft:model", "model": "..." }                    → leaf
     * { "type": "minecraft:condition", "on_true": ..., "on_false": ... } → recurse
     * { "type": "minecraft:select", "cases": [{model: ..., when: ...}], "fallback": ... }
     * { "type": "minecraft:composite", "models": [...] }
     * </pre>
     */
    /**
     * Maps a Yarn {@link net.minecraft.entity.EquipmentSlot} to its Geyser
     * {@code JavaEquippable.EquipmentSlot} equivalent. Returns {@code null} for
     * slots that are not represented as Bedrock wearable slots (mainhand, offhand,
     * etc.) — those don't need an explicit Bedrock wearable component.
     */
    private static org.geysermc.geyser.api.item.custom.v2.component.java.JavaEquippable.EquipmentSlot
            bedrockSlotForJavaSlot(net.minecraft.entity.EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> org.geysermc.geyser.api.item.custom.v2.component.java.JavaEquippable.EquipmentSlot.HEAD;
            case CHEST -> org.geysermc.geyser.api.item.custom.v2.component.java.JavaEquippable.EquipmentSlot.CHEST;
            case LEGS -> org.geysermc.geyser.api.item.custom.v2.component.java.JavaEquippable.EquipmentSlot.LEGS;
            case FEET -> org.geysermc.geyser.api.item.custom.v2.component.java.JavaEquippable.EquipmentSlot.FEET;
            default -> null;
        };
    }

    /**
     * Converts a Yarn {@link net.minecraft.registry.entry.RegistryEntryList} (the block set
     * used by a TOOL rule, or the item set of a REPAIRABLE component) into a Geyser
     * {@code Holders}. Mirrors Hydraulic's {@code toHolders(HolderSet)} but built on Yarn's
     * registry-entry API. We expand the set to an explicit id list instead of forwarding the
     * tag, so the result doesn't depend on Geyser resolving Java tags on the Bedrock side:
     * iterating a {@code RegistryEntryList} yields the resolved members for both tag-backed
     * and inline sets.
     */
    private static org.geysermc.geyser.api.util.Holders toHolders(
            net.minecraft.registry.entry.RegistryEntryList<?> entries) {
        java.util.List<org.geysermc.geyser.api.util.Identifier> ids = new java.util.ArrayList<>();
        for (net.minecraft.registry.entry.RegistryEntry<?> entry : entries) {
            entry.getKey().ifPresent(key ->
                ids.add(org.geysermc.geyser.api.util.Identifier.of(key.getValue().toString())));
        }
        return org.geysermc.geyser.api.util.Holders.of(ids);
    }

    private static String extractFirstModelId(com.google.gson.JsonElement node) {
        if (node == null || !node.isJsonObject()) return null;
        JsonObject obj = node.getAsJsonObject();

        // Leaf: { "type": "minecraft:model", "model": "id" } or just { "model": "id" }
        com.google.gson.JsonElement modelEl = obj.get("model");
        if (modelEl != null && modelEl.isJsonPrimitive()) {
            return modelEl.getAsString();
        }

        // Recurse into common selector branches in priority order.
        for (String key : new String[]{"on_false", "on_true", "fallback"}) {
            if (obj.has(key)) {
                String r = extractFirstModelId(obj.get(key));
                if (r != null) return r;
                // Some selectors wrap the leaf one level deeper:
                // { "on_false": { "type": "minecraft:model", "model": "id" } }
                com.google.gson.JsonElement branch = obj.get(key);
                if (branch.isJsonObject()) {
                    com.google.gson.JsonElement inner = branch.getAsJsonObject().get("model");
                    if (inner != null && inner.isJsonPrimitive()) return inner.getAsString();
                }
            }
        }
        // Arrays: select/composite/range_dispatch
        for (String key : new String[]{"cases", "models", "entries"}) {
            if (obj.has(key) && obj.get(key).isJsonArray()) {
                for (com.google.gson.JsonElement el : obj.getAsJsonArray(key)) {
                    if (!el.isJsonObject()) continue;
                    JsonObject caseObj = el.getAsJsonObject();
                    // case entry: { "model": { "type": "minecraft:model", "model": "id" }, ... }
                    com.google.gson.JsonElement caseModel = caseObj.get("model");
                    if (caseModel != null) {
                        if (caseModel.isJsonPrimitive()) return caseModel.getAsString();
                        String r = extractFirstModelId(caseModel);
                        if (r != null) return r;
                    }
                }
            }
        }
        // Recurse into nested "model" objects as a last resort.
        if (modelEl != null && modelEl.isJsonObject()) {
            return extractFirstModelId(modelEl);
        }
        return null;
    }

    private void handle(GeyserDefineCustomItemsEvent event, Path packRoot) {
        Path textureDir = createDirectoryOrThrow(packRoot.resolve("textures"));
        createDirectoryOrThrow(textureDir.resolve("items"));

        JsonObject itemTextureObject = new JsonObject();
        itemTextureObject.addProperty("resource_pack_name", BedframeConstants.MOD_ID);
        itemTextureObject.addProperty("texture_name", "atlas.items");

        JsonObject textureDataObject = new JsonObject();

        forEachItem((identifier, item) -> {
            // Skip items that Hydraulic is already handling to avoid double-registration,
            // UNLESS the namespace is in BEDFRAME_FORCE_REGISTER_MODS (e.g. tsa_decorations).
            if (deferToHydraulic && hydraulicWouldHandle(identifier)) {
                LOGGER.debug("Deferring item {} to Hydraulic", identifier);
                return;
            }

            // True for items from mods that opt out of Polymer overlays. These must ALWAYS
            // be registered with Geyser (even without a resolvable model) so that Bedrock
            // clients don't crash on unknown item IDs when opening the creative inventory.
            boolean forceRegisterMod =
                BEDFRAME_NON_POLYMER_FORCE_REGISTER_MODS.contains(identifier.getNamespace());

            Item realItem = Registries.ITEM.get(identifier);
            ItemStack realDefaultItemStack = realItem.getDefaultStack();

            // Resolve the custom model identifier.
            // Prefer getPolymerItemModel() (the correct API); fall back to reading
            // ITEM_MODEL directly from the polymer stack for mods that override the stack.
            TranslationManager.INCLUDE_OPTIONAL_TEXTURES_HACK = true;
            Identifier model = item.getPolymerItemModel(realDefaultItemStack, PacketContext.get());
            if (model == null) {
                ItemStack polymerStack = item.getPolymerItemStack(
                    realDefaultItemStack, TooltipType.BASIC, PacketContext.get());
                model = polymerStack.get(DataComponentTypes.ITEM_MODEL);
            }
            TranslationManager.INCLUDE_OPTIONAL_TEXTURES_HACK = false;

            // For force-register mods, fall back to the item's own identifier as model id
            // when nothing else is available. This guarantees the item is registered.
            if (model == null) {
                if (!forceRegisterMod) return;
                model = identifier;
            }

            // Check whether a custom model resource exists for this item.
            // IMPORTANT: must use ResourceHelper.hasResource() and NOT a raw
            // PACK_BUILDER.getData() check. Polymer patch mods (e.g. an Enderscape
            // polymer patch) add their target's assets via addModAssets(), but those
            // assets are not written into PACK_BUILDER until the pack is generated —
            // which happens after this registration pass. The classloader fallback in
            // hasResource() finds them immediately from the mod JAR on the classpath.
            boolean hasCustomModelResource =
                ResourceHelper.hasResource(model.getNamespace(), "items/" + model.getPath() + ".json") ||
                ResourceHelper.hasResource(model.getNamespace(), "models/" + model.getPath() + ".json");

            boolean isBlockItem = realItem instanceof BlockItem;
            // For force-register mods, do not early-return: we want the item registered
            // even if there is no extractable texture. It will use Geyser's fallback icon
            // but at least Bedrock will know the item exists and won't crash.
            if (!hasCustomModelResource && !isBlockItem && !forceRegisterMod) return;

            // ── Build the v2 custom item definition ──────────────────────────────────
            org.geysermc.geyser.api.util.Identifier geyserIdentifier =
                org.geysermc.geyser.api.util.Identifier.of(identifier.toString());

            NonVanillaCustomItemDefinition.Builder itemDefinition = NonVanillaCustomItemDefinition.builder(
                geyserIdentifier,
                geyserIdentifier,
                Registries.ITEM.getRawIdOrThrow(realItem)
            ).displayName("%" + realItem.getTranslationKey());

            CustomItemBedrockOptions.Builder bedrockOptions = CustomItemBedrockOptions.builder()
                .allowOffhand(true)
                .creativeCategory(CreativeCategory.CONSTRUCTION);

            // Translation key for item name.
            //
            // Two keys are emitted on purpose:
            //   1. "item.<ns>:<path>.name"  — Bedrock's native custom-item name key,
            //      used when no display_name override is present.
            //   2. realItem.getTranslationKey() ("item.<ns>.<path>" / "block.<ns>.<path>")
            //      — THE key the Bedrock client actually looks up, because the
            //      display_name component is set to "%" + getTranslationKey() above and
            //      Geyser passes that string through verbatim. Without this line that
            //      exact key was never in the .lang, so every non-block item showed its
            //      raw key (block items only worked by luck: their getTranslationKey()
            //      returns "block.<ns>.<path>", which BlockTranslator already adds).
            // The displayName is deliberately NOT changed — using a key with ':' there
            // breaks Bedrock's "%key" parser and blanks out every name.
            addTranslationKey("item." + identifier + ".name", realItem.getTranslationKey());
            addTranslationKey(realItem.getTranslationKey(), realItem.getTranslationKey());

            // ── Components ───────────────────────────────────────────────────────────

            // Food + Consumable
            // FOOD alone is NOT enough to make Bedrock animate eating — Bedrock requires
            // CONSUMABLE to know the item can be consumed and which animation to play.
            // Without CONSUMABLE, the item's hunger/saturation values are registered but
            // the player just instantly eats it without holding to consume.
            FoodComponent foodComponent = realDefaultItemStack.getComponents().get(DataComponentTypes.FOOD);
            if (foodComponent != null) {
                itemDefinition.component(
                    JavaItemDataComponents.FOOD,
                    JavaFoodProperties.of(
                        foodComponent.nutrition(),
                        foodComponent.saturation(),
                        foodComponent.canAlwaysEat()
                    )
                );
                // Default eating animation (1.6s, "eat" animation). The Java consumable
                // component on the item may specify drink/eat but for now we use the
                // default — most modded food uses standard eating.
                itemDefinition.component(
                    JavaItemDataComponents.CONSUMABLE,
                    org.geysermc.geyser.api.item.custom.v2.component.java.JavaConsumable.builder().build()
                );
            }

            // Enchantment glint
            boolean hasGlint = realDefaultItemStack.getComponents()
                .getOrDefault(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
            if (hasGlint) {
                itemDefinition.component(JavaItemDataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }

            // Bows / crossbows
            if (realItem instanceof BowItem) {
                itemDefinition.component(GeyserItemDataComponents.CHARGEABLE,
                    GeyserChargeable.builder().maxDrawDuration(1f).chargeOnDraw(false));
                bedrockOptions.icon(identifier.toString());
            } else if (realItem instanceof CrossbowItem) {
                itemDefinition.component(GeyserItemDataComponents.CHARGEABLE,
                    GeyserChargeable.builder().maxDrawDuration(0f).chargeOnDraw(true));
                bedrockOptions.icon(identifier.toString());
            }

            // Equippable (armor)
            // Tells Bedrock the item belongs in a specific armor slot. Without this:
            //   1) Bedrock won't allow placing the item into the helmet/chest/legs/feet
            //      slot via inventory drag — the user has to right-click in air to equip.
            //   2) The attachable's render query doesn't get a slot context, so attachables
            //      end up rendering whenever the item is held (helmet visible while in hotbar).
            // With this component the item gets a Bedrock-side `minecraft:wearable` that fixes
            // both behaviors.
            net.minecraft.component.type.EquippableComponent equip =
                realDefaultItemStack.getComponents().get(DataComponentTypes.EQUIPPABLE);
            if (equip != null) {
                org.geysermc.geyser.api.item.custom.v2.component.java.JavaEquippable.EquipmentSlot bedrockSlot =
                    bedrockSlotForJavaSlot(equip.slot());
                if (bedrockSlot != null) {
                    itemDefinition.component(
                        JavaItemDataComponents.EQUIPPABLE,
                        org.geysermc.geyser.api.item.custom.v2.component.java.JavaEquippable.of(bedrockSlot)
                    );
                }
            }

            // Tool — per-block mining rules + default mining speed, and whether the item can
            // break blocks in creative. Ported from Hydraulic's ComponentConverter TOOL case
            // to Yarn. Rules without an explicit mining-speed override are skipped (they only
            // affect drops, which Bedrock can't express here), exactly like Hydraulic.
            net.minecraft.component.type.ToolComponent toolComponent =
                realDefaultItemStack.getComponents().get(DataComponentTypes.TOOL);
            if (toolComponent != null) {
                org.geysermc.geyser.api.item.custom.v2.component.java.JavaTool.Builder toolBuilder =
                    org.geysermc.geyser.api.item.custom.v2.component.java.JavaTool.builder()
                        .canDestroyBlocksInCreative(toolComponent.canDestroyBlocksInCreative())
                        .defaultMiningSpeed(toolComponent.defaultMiningSpeed());
                for (net.minecraft.component.type.ToolComponent.Rule rule : toolComponent.rules()) {
                    if (rule.speed().isEmpty()) continue;
                    toolBuilder.rule(
                        org.geysermc.geyser.api.item.custom.v2.component.java.JavaTool.Rule.of(
                            toHolders(rule.blocks()), rule.speed().get()));
                }
                itemDefinition.component(JavaItemDataComponents.TOOL, toolBuilder);
            }

            // Use cooldown — the cooldown applied after using the item, plus its optional
            // shared cooldown group id.
            net.minecraft.component.type.UseCooldownComponent cooldownComponent =
                realDefaultItemStack.getComponents().get(DataComponentTypes.USE_COOLDOWN);
            if (cooldownComponent != null) {
                org.geysermc.geyser.api.util.Identifier cooldownGroup = cooldownComponent.cooldownGroup()
                    .map(id -> org.geysermc.geyser.api.util.Identifier.of(id.toString()))
                    .orElse(null);
                itemDefinition.component(
                    JavaItemDataComponents.USE_COOLDOWN,
                    org.geysermc.geyser.api.item.custom.v2.component.java.JavaUseCooldown.builder()
                        .seconds(cooldownComponent.seconds())
                        .cooldownGroup(cooldownGroup));
            }

            // Repairable — the set of items that can repair this one in an anvil.
            net.minecraft.component.type.RepairableComponent repairableComponent =
                realDefaultItemStack.getComponents().get(DataComponentTypes.REPAIRABLE);
            if (repairableComponent != null) {
                itemDefinition.component(
                    JavaItemDataComponents.REPAIRABLE,
                    org.geysermc.geyser.api.item.custom.v2.component.java.JavaRepairable.builder()
                        .items(toHolders(repairableComponent.items())));
            }

            // ── Texture / icon resolution ─────────────────────────────────────────
            // NOTE: BLOCK_PLACER is set AFTER this block because canUseBlockAsIcon
            // depends on whether we found a flat 2D icon here.
            boolean hasFlat2DIcon = false;
            if (hasCustomModelResource) {
                // Two Java model formats supported:
                //   NEW (1.21.4+): assets/{ns}/items/{path}.json → item description with model selector
                //   OLD (Filament/TSA): assets/{ns}/models/{path}.json → model with textures
                JsonObject modelObject = null;
                try {
                    JsonObject itemDesc = ResourceHelper.readJsonResource(
                        model.getNamespace(), "items/" + model.getPath() + ".json");
                    // Item descriptions can wrap their actual model in nested selectors:
                    //   minecraft:condition (on_true/on_false branches)
                    //   minecraft:select (cases[] + fallback)
                    //   minecraft:composite (models[])
                    //   minecraft:range_dispatch (entries[] + fallback)
                    // Walk the tree until we find a "minecraft:model" leaf and use its model id.
                    String modelId = extractFirstModelId(itemDesc.get("model"));
                    if (modelId != null) {
                        Identifier resolvedId = Identifier.of(modelId);
                        // Try both "models/{path}.json" (where path may already include "item/")
                        // and the path as-is from the item descriptor.
                        try {
                            modelObject = ResourceHelper.readJsonResource(
                                resolvedId.getNamespace(),
                                "models/" + resolvedId.getPath() + ".json");
                        } catch (RuntimeException ignored) { }
                    }
                } catch (RuntimeException e) {
                    // Fall through to OLD format below.
                }
                if (modelObject == null) {
                    try {
                        modelObject = ResourceHelper.readJsonResource(
                            model.getNamespace(), "models/" + model.getPath() + ".json");
                    } catch (RuntimeException ignored) { }
                }

                if (modelObject != null) {
                    try {
                        // Walk the parent chain. The leaf model may inherit from a
                        // template (e.g. enderscape:item/magnia_attractor_template) that
                        // itself extends item/generated. We need to find the ROOT vanilla
                        // parent (generated or handheld) to know the rendering mode, but
                        // keep the LEAF's textures since those are the concrete ones.
                        // Walk up to 8 levels deep as a safety bound — real chains are
                        // rarely deeper than 2-3.
                        JsonObject leaf = modelObject;
                        Identifier rootParent = null;
                        JsonObject cursor = modelObject;
                        for (int depth = 0; depth < 8; depth++) {
                            if (!cursor.has("parent")) break;
                            Identifier p = Identifier.of(cursor.get("parent").getAsString());
                            rootParent = p;
                            if (p.equals(BedframeConstants.GENERATED_IDENTIFIER)
                                    || p.equals(BedframeConstants.HANDHELD_IDENTIFIER)) {
                                break; // found a recognised vanilla root
                            }
                            // Not a root yet — climb. Read the parent's JSON.
                            try {
                                cursor = ResourceHelper.readJsonResource(
                                    p.getNamespace(), "models/" + p.getPath() + ".json");
                            } catch (RuntimeException stop) {
                                break; // can't climb further; treat what we have as the root
                            }
                        }

                        boolean handheld = rootParent != null
                            && rootParent.equals(BedframeConstants.HANDHELD_IDENTIFIER);
                        if (handheld) bedrockOptions.displayHandheld(true);

                        boolean isGenerated = rootParent != null
                            && rootParent.equals(BedframeConstants.GENERATED_IDENTIFIER);

                        if (isGenerated || handheld) {
                            // Flat 2D item — copy layer0 texture as Bedrock icon. Look up
                            // textures starting from the leaf model and walking up if the
                            // leaf doesn't define textures (e.g. when the texture is on the
                            // template). In practice the leaf almost always has them.
                            JsonObject withTextures = leaf;
                            JsonObject scan = leaf;
                            for (int d = 0; d < 8; d++) {
                                if (scan.has("textures")) { withTextures = scan; break; }
                                if (!scan.has("parent")) break;
                                try {
                                    Identifier pp = Identifier.of(scan.get("parent").getAsString());
                                    scan = ResourceHelper.readJsonResource(
                                        pp.getNamespace(), "models/" + pp.getPath() + ".json");
                                } catch (RuntimeException stop) { break; }
                            }
                            if (!withTextures.has("textures")) {
                                LOGGER.debug("Item {} has no resolvable textures in model chain", identifier);
                            } else {
                                Identifier textureId = Identifier.of(
                                    withTextures.get("textures").getAsJsonObject().get("layer0").getAsString());
                                String texturePath = "textures/" + textureId.getPath();
                                // Namespace the bedrock path to avoid two mods overwriting each
                                // other's textures when filenames collide (same root cause as the
                                // block-texture fix in BlockTranslator).
                                String bedrockPath = ResourceHelper.javaToBedrockTexture(
                                    "textures/" + textureId.getNamespace() + "/" + textureId.getPath());
                                String textureName = identifier.toString();

                                JsonObject textureObj = new JsonObject();
                                textureObj.addProperty("textures", bedrockPath);
                                textureDataObject.add(textureName, textureObj);
                                ResourceHelper.copyResource(textureId.getNamespace(),
                                    texturePath + ".png", packRoot.resolve(bedrockPath + ".png"));
                                bedrockOptions.icon(textureName);
                                hasFlat2DIcon = true;
                            }
                        }
                        // 3D element models: no flat icon — block items use block geometry as icon
                    } catch (NullPointerException ignored) {
                        // No "parent" field — 3D custom element model, block geometry used as icon
                        LOGGER.debug("Item {} is a 3D element model without a standard parent", identifier);
                    }
                }
            }

            // Block items — link to the custom block so Bedrock uses its geometry as icon.
            // canUseBlockAsIcon=true whenever there is no separate flat 2D icon (i.e. 3D models
            // like TSA furniture). This must be evaluated AFTER the icon resolution above.
            if (isBlockItem) {
                Block block = ((BlockItem) realItem).getBlock();
                org.geysermc.geyser.api.util.Identifier blockId =
                    org.geysermc.geyser.api.util.Identifier.of(
                        Registries.BLOCK.getEntry(block).getIdAsString());
                itemDefinition.component(GeyserItemDataComponents.BLOCK_PLACER,
                    GeyserBlockPlacer.of(blockId, !hasFlat2DIcon));
            }

            itemDefinition.bedrockOptions(bedrockOptions);
            registeredItems.add(realItem);
            event.register(itemDefinition.build());
        });

        itemTextureObject.add("texture_data", textureDataObject);
        writeJsonToFile(itemTextureObject, textureDir.resolve("item_texture.json").toFile());
        markResourcesProvided();
    }

    @Override
    public void register(EventBus<EventRegistrar> eventBus, Path packRoot) {
        eventBus.subscribe(this, GeyserDefineCustomItemsEvent.class, event -> handle(event, packRoot));
    }
}
