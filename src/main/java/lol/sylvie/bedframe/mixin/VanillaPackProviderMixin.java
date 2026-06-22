package lol.sylvie.bedframe.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import lol.sylvie.bedframe.geyser.TranslationManager;
import org.geysermc.pack.converter.util.VanillaPackProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/*
 * this code does nothing to better humanity
 * i don't want to deal with any stuff related to
 */
@Mixin(value = VanillaPackProvider.class, remap = false)
public class VanillaPackProviderMixin {
	@ModifyExpressionValue(method = "lambda$clean$0", at = @At(value = "INVOKE", target = "Ljava/lang/String;startsWith(Ljava/lang/String;)Z"))
	private static boolean bedframe$forceAddTextures(boolean original, @Local(name = "pathName") String name) {
		if (!TranslationManager.INCLUDE_TEXTURE_HACK) return original;
		// Keep textures (already required for icon copying) AND model/blockstate JSONs.
		// Without the JSONs, mod blocks that inherit from vanilla parents like
		// minecraft:block/button or minecraft:block/inner_stairs cannot be stitched —
		// resolveModel fails to find the parent and the converted model ends up empty,
		// causing the dirt+question-mark placeholder.
		return name.startsWith("/assets/minecraft/textures")
			|| name.startsWith("/assets/minecraft/models")
			|| name.startsWith("/assets/minecraft/blockstates")
			|| name.startsWith("/assets/minecraft/items");
	}
}
