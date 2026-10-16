package luna.map;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LunaMap implements ClientModInitializer {
	public static final String MOD_ID = "lunamap";
	public static final Logger LOGGER = LoggerFactory.getLogger("Luna Map");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		ModContainer self = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow();
		// Als eingebautes Ressourcenpaket, damit es sicher ueber Xaeros eigenen Texturen liegt
		// und sich unter Optionen > Ressourcenpakete abschalten laesst.
		ResourceLoader.registerBuiltinPack(id("luna_theme"), self,
				Component.translatable("lunamap.pack.luna_theme"), PackActivationType.DEFAULT_ENABLED);

		LOGGER.info("Luna Map {} geladen: Minimap {}, Weltkarte {}, Iris {}",
				self.getMetadata().getVersion().getFriendlyString(),
				version("xaerominimap"), version("xaeroworldmap"), version("iris"));
	}

	static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("-");
	}
}
