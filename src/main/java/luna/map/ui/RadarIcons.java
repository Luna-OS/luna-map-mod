package luna.map.ui;

import com.mojang.blaze3d.platform.NativeImage;

import luna.map.LunaMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * Radar-Symbole fuer Minimap und Weltkarte: weich gezeichnete Punkte mit dunklem Rand und Glanz,
 * andere Spieler als Skin-Kopf.
 * Sie werden passend zur GUI-Skalierung erzeugt und 1:1 in Bildschirmpixeln gezeichnet, damit sie scharf bleiben.
 */
final class RadarIcons {
	/** Reihenfolge = Zeichenreihenfolge (Spieler zuletzt, also oben). */
	enum Kind {
		ITEM(0xFF5555, 2.3f), FRIENDLY(0x6EE07A, 2.3f), MOB(0xFFE04A, 2.7f), PLAYER(0xFFFFFF, 3.1f);

		final int rgb;
		final float radius;

		Kind(int rgb, float radius) {
			this.rgb = rgb;
			this.radius = radius;
		}
	}

	private static final Identifier ID = LunaMap.id("radar_icons");
	/** Kantenlaenge einer Zelle in GUI-Pixeln. */
	private static final int CELL = 8;
	private static final int OUTLINE = 0x110D28;
	private static final int RING = 0xA896E8;
	private static final int SAMPLES = 4;

	private static DynamicTexture texture;
	private static int textureScale;

	private RadarIcons() {
	}

	/** Welche Art Punkt bekommt das Entity? null = keiner. */
	static Kind kind(Entity entity) {
		if (entity instanceof ItemEntity) {
			return Kind.ITEM;
		}
		// Spieler und Spielerfiguren (Mannequins, z. B. NPCs) bekommen ihren Kopf
		if (entity instanceof Player || entity instanceof ClientAvatarEntity) {
			return Kind.PLAYER;
		}
		if (entity instanceof ArmorStand || !(entity instanceof LivingEntity)) {
			return null;
		}
		return entity instanceof Enemy ? Kind.MOB : Kind.FRIENDLY;
	}

	/**
	 * Zeichnet ein Symbol mittig auf (x, y) in GUI-Koordinaten. dy = Hoehenunterschied zum Spieler:
	 * weit drueber oder drunter wird der Punkt blasser.
	 */
	static void draw(GuiGraphicsExtractor graphics, Kind kind, double x, double y, double dy) {
		int scale = ensureTexture();
		int cell = CELL * scale;
		float alpha = Math.abs(dy) > 12 ? 0.6f : Math.abs(dy) > 5 ? 0.85f : 1f;
		int color = ((int) (alpha * 255) << 24) | 0xFFFFFF;
		// auf ganze Bildschirmpixel runden, sonst flimmert das Symbol beim Bewegen
		float sx = Math.round(x * scale) / (float) scale;
		float sy = Math.round(y * scale) / (float) scale;
		graphics.pose().pushMatrix();
		graphics.pose().translate(sx, sy);
		graphics.pose().scale(1f / scale, 1f / scale);
		graphics.blit(RenderPipelines.GUI_TEXTURED, ID, -cell / 2, -cell / 2, kind.ordinal() * cell, 0, cell, cell,
				cell * Kind.values().length, cell, color);
		graphics.pose().popMatrix();
	}

	/**
	 * Andere Spieler: ihr Skin-Kopf (mit Hut-Ebene) in einem kleinen Luna-Rahmen. size = Kopfgroesse in GUI-Pixeln.
	 * Gibt false zurueck, wenn es keinen Skin gibt (dann den normalen Punkt nehmen).
	 */
	static boolean drawHead(GuiGraphicsExtractor graphics, Entity entity, double x, double y, double dy, int size) {
		if (!(entity instanceof ClientAvatarEntity player)) {
			return false;
		}
		float alpha = Math.abs(dy) > 12 ? 0.6f : Math.abs(dy) > 5 ? 0.85f : 1f;
		int a = (int) (alpha * 255) << 24;
		int x0 = (int) Math.round(x - size / 2.0);
		int y0 = (int) Math.round(y - size / 2.0);
		// abgerundeter dunkler Rand, darin ein feiner Lavendel-Ring
		graphics.fill(x0 - 1, y0 - 2, x0 + size + 1, y0 + size + 2, a | OUTLINE);
		graphics.fill(x0 - 2, y0 - 1, x0 + size + 2, y0 + size + 1, a | OUTLINE);
		graphics.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, a | RING);
		PlayerFaceExtractor.extractRenderState(graphics, player.getSkin(), x0, y0, size, a | 0xFFFFFF);
		return true;
	}

	/** Mobs als Kopf mit Rahmen in ihrer Radarfarbe (gelb feindlich, gruen friedlich), sonst der normale Punkt. */
	static void drawMob(GuiGraphicsExtractor graphics, Kind kind, Entity entity, double x, double y, double dy, int size) {
		float alpha = Math.abs(dy) > 12 ? 0.6f : Math.abs(dy) > 5 ? 0.85f : 1f;
		if ((kind == Kind.MOB || kind == Kind.FRIENDLY)
				&& MobHeads.draw(graphics, entity, x, y, size + 2, alpha)) {
			return;
		}
		draw(graphics, kind, x, y, dy);
	}

	private static int ensureTexture() {
		int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
		if (texture != null && textureScale == scale) {
			return scale;
		}
		int cell = CELL * scale;
		NativeImage image = new NativeImage(cell * Kind.values().length, cell, true);
		for (Kind kind : Kind.values()) {
			paint(image, kind, kind.ordinal() * cell, cell, scale);
		}
		// GUI-Skalierung geaendert: neue Groesse, also neue Textur (passiert vor dem ersten Symbol des Bildes)
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(ID);
		}
		texture = new DynamicTexture(() -> "Luna Map radar", image);
		Minecraft.getInstance().getTextureManager().register(ID, texture);
		textureScale = scale;
		return scale;
	}

	/** Zeichnet ein Symbol mit Kantenglaettung (mehrere Abtastpunkte pro Pixel). */
	private static void paint(NativeImage image, Kind kind, int offsetX, int cell, int scale) {
		float center = cell / 2f;
		float r = kind.radius * scale;
		float outline = Math.max(1f, 0.8f * scale);
		float ring = kind == Kind.PLAYER ? Math.max(1f, 0.7f * scale) : 0;
		for (int py = 0; py < cell; py++) {
			for (int px = 0; px < cell; px++) {
				float a = 0, red = 0, green = 0, blue = 0;
				for (int sy = 0; sy < SAMPLES; sy++) {
					for (int sx = 0; sx < SAMPLES; sx++) {
						float dx = px + (sx + 0.5f) / SAMPLES - center;
						float dy = py + (sy + 0.5f) / SAMPLES - center;
						// Abstand zum Rand: Kreis, beim Item eine Raute
						float d = kind == Kind.ITEM ? (Math.abs(dx) + Math.abs(dy)) * 0.80f - r : (float) Math.sqrt(dx * dx + dy * dy) - r;
						if (d > 0) {
							continue;
						}
						int rgb;
						if (d > -outline) {
							rgb = OUTLINE;
						} else if (d > -outline - ring) {
							rgb = RING;
						} else {
							// Glanz oben links, unten etwas dunkler -> wirkt wie eine kleine Kugel
							float light = Math.max(-1, Math.min(1, -(dx + dy) / (r * 1.4f)));
							rgb = shade(kind.rgb, light);
						}
						a += 1;
						red += (rgb >> 16) & 0xFF;
						green += (rgb >> 8) & 0xFF;
						blue += rgb & 0xFF;
					}
				}
				if (a == 0) {
					image.setPixel(offsetX + px, py, 0);
					continue;
				}
				int alpha = Math.round(a * 255 / (SAMPLES * SAMPLES));
				image.setPixel(offsetX + px, py, alpha << 24 | Math.round(red / a) << 16 | Math.round(green / a) << 8 | Math.round(blue / a));
			}
		}
	}

	/** light > 0 heller (Richtung Weiss), < 0 dunkler. */
	private static int shade(int rgb, float light) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		if (light > 0) {
			float t = light * 0.55f;
			r += (int) ((255 - r) * t);
			g += (int) ((255 - g) * t);
			b += (int) ((255 - b) * t);
		} else {
			float t = 1 + light * 0.25f;
			r = (int) (r * t);
			g = (int) (g * t);
			b = (int) (b * t);
		}
		return r << 16 | g << 8 | b;
	}
}
