package luna.map;

import com.mojang.blaze3d.platform.InputConstants;

import luna.map.data.MapSession;
import luna.map.online.BlueMapSource;
import luna.map.update.Updater;
import net.minecraft.network.chat.Component;
import luna.map.ui.MinimapHud;
import luna.map.ui.WaypointEditScreen;
import luna.map.ui.WorldMapScreen;
import luna.map.ui.WorldWaypointHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LunaMap implements ClientModInitializer {
	public static final String MOD_ID = "lunamap";
	public static final Logger LOGGER = LoggerFactory.getLogger("Luna Map");

	private static KeyMapping worldMapKey;
	private static KeyMapping waypointKey;
	private static KeyMapping minimapKey;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(id("main"));
		worldMapKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.lunamap.world_map", InputConstants.KEY_J, category));
		waypointKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.lunamap.new_waypoint", InputConstants.KEY_B, category));
		minimapKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.lunamap.toggle_minimap", InputConstants.KEY_N, category));

		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
				MapSession.onChunkLoaded(chunk.getPos().x(), chunk.getPos().z()));
		ClientTickEvents.END_CLIENT_TICK.register(LunaMap::tick);
		luna.map.share.WaypointShare.register();
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> MapSession.leave());
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> Updater.start());

		// Wegpunkte in der Welt unter allem anderen HUD, die Minimap darueber
		HudElementRegistry.attachElementBefore(VanillaHudElements.MISC_OVERLAYS, id("world_waypoints"), new WorldWaypointHud());
		HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, id("minimap"), MinimapHud.INSTANCE);

		LOGGER.info("Luna Map {} geladen", FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
	}

	private static int onlineTimer;

	private static void tick(Minecraft mc) {
		MapSession.tick(mc);
		if (mc.player != null) {
			String update = Updater.takeChatMessage();
			if (update != null) {
				mc.player.sendSystemMessage(Component.translatable("lunamap.update.chat", update));
			}
		}
		if (MapSession.store() == null) {
			return;
		}
		luna.map.online.BlueMapAuto.tick(mc);
		if (++onlineTimer >= 100 && mc.player != null) {
			onlineTimer = 0;
			int x = mc.player.getBlockX();
			int z = mc.player.getBlockZ();
			BlueMapSource.request(x - 600, z - 600, x + 600, z + 600);
		}
		while (worldMapKey.consumeClick()) {
			if (mc.gui.screen() == null) {
				mc.setScreenAndShow(new WorldMapScreen());
			}
		}
		while (waypointKey.consumeClick()) {
			if (mc.gui.screen() == null && mc.player != null) {
				mc.setScreenAndShow(WaypointEditScreen.create(null, mc.player.getBlockX(),
						mc.player.getBlockY(), mc.player.getBlockZ()));
			}
		}
		while (minimapKey.consumeClick()) {
			LunaConfig config = LunaConfig.get();
			config.minimap = !config.minimap;
			config.save();
		}
	}
}
