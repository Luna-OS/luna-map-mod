package luna.map.ui;

import com.mojang.blaze3d.platform.NativeImage;

import luna.map.LunaColors;
import luna.map.LunaConfig;
import luna.map.LunaMap;
import luna.map.data.MapSession;
import luna.map.data.MapStore;
import luna.map.data.Waypoint;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Die Minimap oben links: Norden ist oben, der Pfeil dreht sich mit dem Spieler. */
public final class MinimapHud implements HudElement {
	private static final Identifier TEXTURE = LunaMap.id("minimap");
	private static final int MARGIN = 6;

	private DynamicTexture texture;
	private int textureSize;
	private int lastX = Integer.MIN_VALUE;
	private int lastZ;
	private int lastVersion = -1;
	private MapStore lastStore;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		LunaConfig config = LunaConfig.get();
		MapStore store = MapSession.store();
		LocalPlayer player = mc.player;
		if (!config.minimap || store == null || player == null) {
			return;
		}
		int radius = config.zoom.radius;
		int size = config.size.pixels;
		int px = player.getBlockX();
		int pz = player.getBlockZ();
		updateTexture(store, radius, px, pz);

		int x0 = MARGIN + 3;
		int y0 = MARGIN + 3;
		if (config.corner == LunaConfig.Corner.TOP_RIGHT) {
			x0 = mc.getWindow().getGuiScaledWidth() - MARGIN - 3 - size;
			y0 += effectRows(player) * 26;
		}
		MapDraw.frame(graphics, x0, y0, x0 + size, y0 + size);
		graphics.fill(x0, y0, x0 + size, y0 + size, LunaColors.NIGHT_2);
		graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x0, y0, 0, 0, size, size,
				textureSize, textureSize, textureSize, textureSize);

		float scale = size / (float) textureSize;
		graphics.enableScissor(x0, y0, x0 + size, y0 + size);
		for (Waypoint waypoint : MapSession.waypoints().in(MapSession.dimension())) {
			float wx = (waypoint.x - (px - radius) + 0.5f) * scale;
			float wz = (waypoint.z - (pz - radius) + 0.5f) * scale;
			int cx = Math.round(Math.max(4, Math.min(size - 4, wx)));
			int cz = Math.round(Math.max(4, Math.min(size - 4, wz)));
			MapDraw.waypoint(graphics, mc.font, waypoint, x0 + cx, y0 + cz);
		}
		graphics.disableScissor();

		double fx = player.getX() - Math.floor(player.getX());
		double fz = player.getZ() - Math.floor(player.getZ());
		MapDraw.arrow(graphics, x0 + (float) ((radius + fx) * scale), y0 + (float) ((radius + fz) * scale),
				player.getYRot(), 12);
		graphics.centeredText(mc.font, "N", x0 + size / 2, y0 + 2, LunaColors.MOON);

		if (config.coordinates) {
			String coords = px + ", " + player.getBlockY() + ", " + pz;
			graphics.centeredText(mc.font, coords, x0 + size / 2, y0 + size + 5, LunaColors.WHITE);
		}
	}

	/** Oben rechts zeichnet Minecraft die Effekt-Symbole: so viele Reihen Platz lassen. */
	private static int effectRows(LocalPlayer player) {
		boolean good = false;
		boolean bad = false;
		for (net.minecraft.world.effect.MobEffectInstance effect : player.getActiveEffects()) {
			if (!effect.showIcon()) {
				continue;
			}
			if (effect.getEffect().value().isBeneficial()) {
				good = true;
			} else {
				bad = true;
			}
		}
		return (good ? 1 : 0) + (bad ? 1 : 0);
	}

	private void updateTexture(MapStore store, int radius, int px, int pz) {
		int needed = radius * 2;
		if (texture == null || textureSize != needed) {
			if (texture != null) {
				Minecraft.getInstance().getTextureManager().release(TEXTURE);
			}
			texture = new DynamicTexture(() -> "Luna Map minimap", needed, needed, false);
			textureSize = needed;
			Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
			lastX = Integer.MIN_VALUE;
		}
		if (px == lastX && pz == lastZ && store.version == lastVersion && store == lastStore) {
			return;
		}
		NativeImage image = texture.getPixels();
		for (int z = 0; z < needed; z++) {
			for (int x = 0; x < needed; x++) {
				image.setPixel(x, z, store.color(px - radius + x, pz - radius + z));
			}
		}
		texture.upload();
		lastX = px;
		lastZ = pz;
		lastVersion = store.version;
		lastStore = store;
	}
}
