package lol.sylvie.bedframe.mixin.factorytools;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Only applies the FactoryTools mixin when FactoryTools' SimpleEntityModel is actually on the
 * classpath, so servers without any emuvanilla mod load cleanly.
 */
public class FactoryToolsMixinPlugin implements IMixinConfigPlugin {

    private boolean present;

    @Override
    public void onLoad(String mixinPackage) {
        this.present = classExists("eu.pb4.factorytools.api.virtualentity.emuvanilla2.poly.SimpleEntityModel")
                    || classExists("eu.pb4.factorytools.api.virtualentity.emuvanilla.poly.SimpleEntityModel");
    }

    private static boolean classExists(String name) {
        return FactoryToolsMixinPlugin.class.getClassLoader()
                .getResource(name.replace('.', '/') + ".class") != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return present;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode n, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode n, String m, IMixinInfo i) {}
}
