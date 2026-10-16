package luna.map.ui;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.network.chat.Component;

/** Liest den Iris-Status. Iris-Klassen werden nur angefasst, wenn Iris geladen ist. */
final class ShaderStatus {
	private ShaderStatus() {
	}

	static Component describe() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return Component.translatable("lunamap.shaders.no_iris");
		}
		return Iris.shaderPackInUse()
				? Component.translatable("lunamap.shaders.active", irisVersion())
				: Component.translatable("lunamap.shaders.inactive", irisVersion());
	}

	private static String irisVersion() {
		return FabricLoader.getInstance().getModContainer("iris")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	private static final class Iris {
		static boolean shaderPackInUse() {
			return IrisApi.getInstance().isShaderPackInUse();
		}
	}
}
