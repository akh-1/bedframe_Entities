package lol.sylvie.bedframe.mixin.factorytools;

import lol.sylvie.bedframe.geyser.VariantTracker;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures, per live entity, the FactoryTools model instance it is currently displaying.
 *
 * Targets BOTH emuvanilla (v1) and emuvanilla2 (v2) {@code SimpleEntityModel}; their shape is
 * identical (field {@code entity}, ctor {@code (LivingEntity, PolyModelInstance)}, method
 * {@code setModel(PolyModelInstance)}). The model arg is taken via {@link Coerce} as Object so we
 * need no compile dependency on FactoryTools, and {@code remap = false} keeps the literal names.
 *
 * Every ChocoCraft / Enderscape / emuvanilla mob ultimately runs through a SimpleEntityModel, so
 * this single hook feeds {@link VariantTracker} for all of them.
 */
@Mixin(targets = {
        "eu.pb4.factorytools.api.virtualentity.emuvanilla.poly.SimpleEntityModel",
        "eu.pb4.factorytools.api.virtualentity.emuvanilla2.poly.SimpleEntityModel"
}, remap = false)
public abstract class SimpleEntityModelMixin {

    @Shadow(remap = false)
    net.minecraft.entity.LivingEntity entity;

    // Initial look, set in the constructor (e.g. ChocoboModelHandler's super(entity, forChocobo(entity))).
    @Inject(method = "<init>", at = @At("TAIL"))
    private void bedframe$onInit(LivingEntity entity, @Coerce Object model, CallbackInfo ci) {
        VariantTracker.record(entity, model);
    }

    // Every later look change the mod makes (colour, saddle, baby, jelly, rock variant, ...).
    @Inject(method = "setModel", at = @At("TAIL"))
    private void bedframe$onSetModel(@Coerce Object model, CallbackInfo ci) {
        VariantTracker.record(this.entity, model);
    }
}
