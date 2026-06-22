package lol.sylvie.bedframe.mixin.carrier;

import eu.pb4.polymer.core.impl.interfaces.EntityAttachedPacket;
import lol.sylvie.bedframe.geyser.carrier.CarrierRetype;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Generic carrier re-typing for EVERY Polymer entity - replaces the per-mod getPolymerEntityType
 * hooks (ChocoCraft / Enderscape / deer-mod-patch / ...). At spawn-packet write time (which Polymer
 * runs per recipient), if the REAL entity is a bedframe-registered carrier mob and the viewer is on
 * Bedrock, swap the sent type to the carrier. The decision is keyed on the real entity type via
 * BedframeEntities.byType(), so any mob the discovery registers - present or future - is covered with
 * zero per-mod code.
 *
 * This sits on the same encode() arg Polymer's own @ModifyArg targets; order is irrelevant:
 *  - if we run first, we set the carrier and Polymer's guard (value == entity.getType()) then fails,
 *    so it leaves our value alone;
 *  - if we run after, Polymer set its polymer type and we override it to the carrier.
 * Java viewers always get the value unchanged (forBedrock returns the original for non-Bedrock).
 *
 * EntityAttachedPacket is a Polymer internal, but it's exactly what Polymer's AddEntity mixin uses to
 * recover the entity from the packet. require = 0 so a mapping/injection miss degrades to "no re-type"
 * instead of a hard crash.
 */
@Mixin(EntitySpawnS2CPacket.class)
public class SpawnPacketCarrierMixin {

    @ModifyArg(
            method = "write",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/codec/PacketCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V"),
            index = 1,
            require = 0)
    private Object bedframe$carrierForBedrock(Object value) {
        if (!(value instanceof EntityType<?> type)) return value;
        Entity entity = EntityAttachedPacket.get(this);
        if (entity == null) return value;
        return CarrierRetype.forBedrock(entity, PacketContext.get(), type);
    }
}
