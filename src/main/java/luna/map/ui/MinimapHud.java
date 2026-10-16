package luna.map.ui;

import com.mojang.blaze3d.platform.NativeImage;

import luna.map.LunaColors;
import luna.map.LunaConfig;
import luna.map.LunaMap;
import luna.map.data.MapSession;
import luna.map.data.MapStore;
import luna.map.data.Waypoint;
import luna.map.online.BlueMapHires;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/** Die Minimap: Norden oben, Pfeil dreht sich mit dem Spieler, Position und Groesse frei waehlbar. */
public final class MinimapHud implements HudElement {
	public static final MinimapHud INSTANCE = new MinimapHud();

	private static final Identifier TEXTURE = LunaMap.id("minimap");
	static final int MARGIN = 9;

	private DynamicTexture texture;
	private int textureSize;
	private int lastX = Integer.MIN_VALUE;
	private int lastZ;
	private int lastVersion = -1;
	private MapStore lastStore;

	private MinimapHud() {
	}

	/** Linke obere Ecke der Karte (ohne Rahmen) fuer die aktuelle Fenstergroesse. */
	static int[] position(Minecraft mc, LunaConfig config, LocalPlayer player) {
		int size = config.mapSize;
		int width = mc.getWindow().getGuiScaledWidth();
		int height = mc.getWindow().getGuiScaledHeight();
		int x = MARGIN + (int) Math.round(config.posX * Math.max(0, width - size - 2 * MARGIN));
		int y = MARGIN + (int) Math.round(config.posY * Math.max(0, height - size - 2 * MARGIN - 12));
		// oben rechts zeichnet Minecraft die Effekt-Symbole: Platz lassen
		if (player != null && config.posY < 0.05 && config.posX > 0.6) {
			y += effectRows(player) * 26;
		}
		return new int[] {x, y};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (!LunaConfig.get().minimap || mc.gui.screen() instanceof MinimapEditScreen) {
			return;
		}
		draw(graphics);
	}

	void draw(GuiGraphicsExtractor graphics) {
		Minecraft mc = Minecraft.getInstance();
		LunaConfig config = LunaConfig.get();
		MapStore store = MapSession.viewStore();
		LocalPlayer player = mc.player;
		if (store == null || player == null) {
			return;
		}
		int radius = config.zoom.radius;
		int size = config.mapSize;
		int px = player.getBlockX();
		int pz = player.getBlockZ();
		updateTexture(store, radius, px, pz);

		int[] pos = position(mc, config, player);
		int x0 = pos[0];
		int y0 = pos[1];
		MapDraw.frame(graphics, x0, y0, x0 + size, y0 + size);
		graphics.fill(x0, y0, x0 + size, y0 + size, MapSession.underground() ? 0xFF0B0819 : LunaColors.NIGHT_2);
		graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x0, y0, 0, 0, size, size,
				textureSize, textureSize, textureSize, textureSize);

		float scale = size / (float) textureSize;
		double fx = player.getX() - (px - radius);
		double fz = player.getZ() - (pz - radius);
		graphics.enableScissor(x0, y0, x0 + size, y0 + size);
		if (scale >= BlueMapHires.MIN_PIXELS_PER_BLOCK && !MapSession.underground()) {
			// sehr nah: BlueMap-Details mit Block-Texturen
			int left = px - radius;
			int top = pz - radius;
			float s = scale;
			BlueMapHires.draw(graphics, left, top, left + textureSize, top + textureSize,
					bx -> x0 + (bx - left) * s, bz -> y0 + (bz - top) * s);
		}
		if (config.radar && mc.level != null) {
			// pro Art einmal durch, damit Spieler ueber Mobs und Mobs ueber Items liegen
			for (RadarIcons.Kind kind : RadarIcons.Kind.values()) {
				for (Entity entity : mc.level.entitiesForRendering()) {
					if (entity == player || RadarIcons.kind(entity) != kind) {
						continue;
					}
					double ex = entity.getX() - (px - radius);
					double ez = entity.getZ() - (pz - radius);
					if (ex < 0 || ez < 0 || ex >= textureSize || ez >= textureSize) {
						continue;
					}
					double dy = entity.getY() - player.getY();
					if (kind != RadarIcons.Kind.PLAYER || !RadarIcons.drawHead(graphics, entity, x0 + ex * scale, y0 + ez * scale, dy,
							Math.max(6, Math.min(10, size / 12)))) {
						RadarIcons.draw(graphics, kind, x0 + ex * scale, y0 + ez * scale, dy);
					}
				}
			}
		}
		for (Waypoint waypoint : MapSession.waypoints().in(MapSession.dimension())) {
			float wx = (waypoint.x - (px - radius) + 0.5f) * scale;
			float wz = (waypoint.z - (pz - radius) + 0.5f) * scale;
			boolean inside = wx >= 0 && wz >= 0 && wx < size && wz < size;
			// ausserhalb: kleiner Punkt am Rand in Richtung des Wegpunkts
			int cx = Math.round(Math.max(3, Math.min(size - 3, wx)));
			int cz = Math.round(Math.max(3, Math.min(size - 3, wz)));
			MapDraw.waypoint(graphics, waypoint, x0 + cx, y0 + cz, inside ? 3 : 2);
		}
		graphics.disableScissor();

		MapDraw.arrow(graphics, x0 + (float) (fx * scale), y0 + (float) (fz * scale), player.getYRot(), 12);
		graphics.centeredText(mc.font, "N", x0 + size / 2, y0 + 2, LunaColors.MOON);
		if (MapSession.underground()) {
			graphics.text(mc.font, "⛏", x0 + 2, y0 + 2, LunaColors.LAVENDER);
		}

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
