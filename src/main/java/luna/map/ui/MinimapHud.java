package luna.map.ui;

import com.mojang.blaze3d.platform.NativeImage;

import luna.map.LunaColors;
import luna.map.LunaConfig;
import luna.map.LunaMap;
import luna.map.data.Biomes;
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
import net.minecraft.network.chat.Component;
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
	private long lastUpdate;

	private MinimapHud() {
	}

	/** Platz fuer die Zeilen unter der Karte (Koordinaten, Biom). */
	static int textBelow(LunaConfig config) {
		return Math.max(12, ((config.coordinates ? 1 : 0) + (config.biome ? 1 : 0)) * 10 + 2);
	}

	/** Linke obere Ecke der Karte (ohne Rahmen) fuer die aktuelle Fenstergroesse. */
	static int[] position(Minecraft mc, LunaConfig config, LocalPlayer player) {
		int size = config.mapSize;
		int width = mc.getWindow().getGuiScaledWidth();
		int height = mc.getWindow().getGuiScaledHeight();
		int x = MARGIN + (int) Math.round(config.posX * Math.max(0, width - size - 2 * MARGIN));
		int y = MARGIN + (int) Math.round(config.posY * Math.max(0, height - size - 2 * MARGIN - textBelow(config)));
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
		// beim Drehen braucht es Platz bis in die Ecken (Diagonale), sonst bleiben sie leer
		boolean rotate = config.rotateMinimap;
		int texRadius = rotate ? (int) Math.ceil(radius * 1.42) + 1 : radius;
		updateTexture(store, texRadius, px, pz);

		int[] pos = position(mc, config, player);
		int x0 = pos[0];
		int y0 = pos[1];
		float scale = size / (float) (radius * 2);
		float cx = x0 + size / 2f;
		float cy = y0 + size / 2f;
		double playerX = player.getX();
		double playerZ = player.getZ();
		// Blickrichtung zeigt nach oben: Karte um (180 - Blickwinkel) drehen
		float angle = rotate ? (float) Math.toRadians(180 - player.getYRot()) : 0f;
		float cos = (float) Math.cos(angle);
		float sin = (float) Math.sin(angle);

		MapDraw.frame(graphics, x0, y0, x0 + size, y0 + size);
		graphics.fill(x0, y0, x0 + size, y0 + size, MapSession.underground() ? 0xFF0B0819 : LunaColors.NIGHT_2);
		graphics.enableScissor(x0, y0, x0 + size, y0 + size);
		graphics.pose().pushMatrix();
		graphics.pose().translate(cx, cy);
		graphics.pose().rotate(angle);
		graphics.pose().pushMatrix();
		// Karte mittig auf der genauen Spielerposition (gleitet weich statt blockweise zu springen)
		graphics.pose().translate((float) ((px - texRadius - playerX) * scale), (float) ((pz - texRadius - playerZ) * scale));
		graphics.pose().scale(scale, scale);
		graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, 0, 0, textureSize, textureSize,
				textureSize, textureSize, textureSize, textureSize);
		graphics.pose().popMatrix();
		if (scale >= BlueMapHires.MIN_PIXELS_PER_BLOCK && !MapSession.underground()) {
			// sehr nah: BlueMap-Details mit Block-Texturen (dreht sich mit)
			float s = scale;
			BlueMapHires.draw(graphics, playerX - texRadius, playerZ - texRadius, playerX + texRadius, playerZ + texRadius,
					bx -> (bx - playerX) * s, bz -> (bz - playerZ) * s);
		}
		graphics.pose().popMatrix();

		float half = size / 2f;
		if (config.radar && mc.level != null) {
			// pro Art einmal durch, damit Spieler ueber Mobs und Mobs ueber Items liegen
			for (RadarIcons.Kind kind : RadarIcons.Kind.values()) {
				for (Entity entity : mc.level.entitiesForRendering()) {
					if (entity == player || RadarIcons.kind(entity) != kind) {
						continue;
					}
					float dx = (float) (entity.getX() - playerX) * scale;
					float dz = (float) (entity.getZ() - playerZ) * scale;
					float rx = dx * cos - dz * sin;
					float ry = dx * sin + dz * cos;
					if (Math.abs(rx) > half || Math.abs(ry) > half) {
						continue;
					}
					double dy = entity.getY() - player.getY();
					if (kind != RadarIcons.Kind.PLAYER || !RadarIcons.drawHead(graphics, entity, cx + rx, cy + ry, dy,
							Math.max(6, Math.min(10, size / 12)))) {
						RadarIcons.drawMob(graphics, kind, entity, cx + rx, cy + ry, dy, Math.max(6, Math.min(9, size / 13)));
					}
				}
			}
		}
		for (Waypoint waypoint : MapSession.waypoints().visible(MapSession.dimension())) {
			float dx = (float) (waypoint.x + 0.5 - playerX) * scale;
			float dz = (float) (waypoint.z + 0.5 - playerZ) * scale;
			float rx = dx * cos - dz * sin;
			float ry = dx * sin + dz * cos;
			float limit = half - 3;
			boolean inside = Math.abs(rx) <= limit && Math.abs(ry) <= limit;
			if (!inside) {
				// ausserhalb: kleiner Punkt am Rand in Richtung des Wegpunkts
				float f = limit / Math.max(Math.abs(rx), Math.abs(ry));
				rx *= f;
				ry *= f;
			}
			MapDraw.waypoint(graphics, waypoint, Math.round(cx + rx), Math.round(cy + ry), inside ? 3 : 2);
		}
		graphics.disableScissor();

		MapDraw.arrow(graphics, cx, cy, player.getYRot() + (float) Math.toDegrees(angle), 12);
		// Himmelsrichtungen am Rand, drehen sich mit
		String[] labels = {"lunamap.dir.n", "lunamap.dir.e", "lunamap.dir.s", "lunamap.dir.w"};
		float[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
		for (int i = 0; i < 4; i++) {
			float rx = dirs[i][0] * cos - dirs[i][1] * sin;
			float ry = dirs[i][0] * sin + dirs[i][1] * cos;
			float f = (half - 5) / Math.max(Math.abs(rx), Math.abs(ry));
			int lx = Math.round(cx + rx * f);
			int ly = Math.round(cy + ry * f);
			graphics.fill(lx - 4, ly - 5, lx + 4, ly + 4, 0xB0110D28);
			graphics.centeredText(mc.font, Component.translatable(labels[i]), lx, ly - 4, i == 0 ? LunaColors.PINK : LunaColors.MOON);
		}
		if (MapSession.underground()) {
			graphics.text(mc.font, "⛏", x0 + 2, y0 + 2, LunaColors.LAVENDER);
		}

		int textY = y0 + size + 5;
		if (config.coordinates) {
			String coords = px + ", " + player.getBlockY() + ", " + pz;
			graphics.centeredText(mc.font, coords, x0 + size / 2, textY, LunaColors.WHITE);
			textY += 10;
		}
		if (config.biome && mc.level != null) {
			Component biome = Biomes.name(Biomes.at(mc.level, px, player.getBlockY(), pz));
			if (biome != null) {
				// lange Namen nicht ueber den Bildschirmrand hinaus schieben
				int halfText = mc.font.width(biome) / 2;
				int screenW = mc.getWindow().getGuiScaledWidth();
				int center = Math.max(halfText + 2, Math.min(screenW - halfText - 2, x0 + size / 2));
				graphics.centeredText(mc.font, biome, center, textY, LunaColors.LAVENDER);
			}
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
		boolean moved = px != lastX || pz != lastZ || store != lastStore;
		if (!moved && (store.version == lastVersion || System.currentTimeMillis() - lastUpdate < 250)) {
			return; // neue Scan-Daten reichen 4x pro Sekunde, das spart viel Arbeit beim Erkunden
		}
		lastUpdate = System.currentTimeMillis();
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
