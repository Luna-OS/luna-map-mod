package luna.map.ui;

import luna.map.LunaMap;
import luna.map.data.Waypoint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Gemeinsame Zeichenhilfen fuer Minimap und Weltkarte. */
final class MapDraw {
	static final Identifier ARROW = LunaMap.id("textures/gui/arrow.png");
	private static final int SHADOW = 0xB0110D28;

	private MapDraw() {
	}

	/** Spielerpfeil, zeigt bei yaw 0 (Blick nach Sueden) nach unten. */
	static void arrow(GuiGraphicsExtractor graphics, float x, float y, float yaw, int size) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().rotate((float) Math.toRadians(yaw + 180));
		graphics.blit(RenderPipelines.GUI_TEXTURED, ARROW, -size / 2, -size / 2, 0, 0, size, size, 16, 16, 16, 16);
		graphics.pose().popMatrix();
	}

	/** Totenkopf, 7x7 Pixel: X = Knochen, alles andere frei (Augen, Nase und Zahnluecken bleiben dunkel). */
	private static final String[] SKULL = {
			".XXXXX.",
			"XXXXXXX",
			"X..X..X",
			"X..X..X",
			"XXX.XXX",
			".XXXXX.",
			".X.X.X."};

	/** Todespunkt als Totenkopf; der vorherige Tod ist grau. size = Pixelgroesse in GUI-Pixeln. */
	static void skull(GuiGraphicsExtractor graphics, int x, int y, int size, boolean old) {
		int x0 = x - 3 * size - size / 2;
		int y0 = y - 3 * size - size / 2;
		int bone = old ? 0xFFA8A3B8 : 0xFFF4F1FF;
		int dark = 0xFF110D28;
		// dunkler Rand: das Muster in alle vier Richtungen versetzt, so werden auch Augen und Nase dunkel
		for (int[] d : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
			for (int row = 0; row < SKULL.length; row++) {
				for (int col = 0; col < SKULL[row].length(); col++) {
					if (SKULL[row].charAt(col) == 'X') {
						int px = x0 + (col + d[0]) * size;
						int py = y0 + (row + d[1]) * size;
						graphics.fill(px, py, px + size, py + size, dark);
					}
				}
			}
		}
		for (int row = 0; row < SKULL.length; row++) {
			for (int col = 0; col < SKULL[row].length(); col++) {
				if (SKULL[row].charAt(col) == 'X') {
					int px = x0 + col * size;
					int py = y0 + row * size;
					graphics.fill(px, py, px + size, py + size, bone);
				}
			}
		}
	}

	/**
	 * Wegpunkt als kleines Schild mit den ersten zwei Buchstaben (in der Welt, auf der Weltkarte, in der Liste);
	 * Todespunkte als Totenkopf.
	 */
	static void badge(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, Waypoint waypoint, int x, int y,
			boolean big) {
		if (waypoint.isDeath()) {
			skull(graphics, x, y, big ? 2 : 1, waypoint.isOldDeath());
			return;
		}
		String text = waypoint.initials();
		int color = 0xFF000000 | waypoint.color;
		int w = font.width(text) + 5;
		int h = 11;
		int x0 = x - w / 2;
		int y0 = y - h / 2;
		// dunkler, abgerundeter Rand
		graphics.fill(x0, y0 - 1, x0 + w, y0 + h + 1, SHADOW);
		graphics.fill(x0 - 1, y0, x0 + w + 1, y0 + h, SHADOW);
		graphics.fill(x0, y0, x0 + w, y0 + h, color);
		// oben ein Hauch heller, damit es nach Luna-Knopf aussieht
		graphics.fill(x0 + 1, y0 + 1, x0 + w - 1, y0 + 2, 0x40FFFFFF);
		if (big) {
			graphics.fill(x0 - 2, y0 - 2, x0 + w + 2, y0 - 1, 0xC0F4F1FF);
			graphics.fill(x0 - 2, y0 + h + 1, x0 + w + 2, y0 + h + 2, 0xC0F4F1FF);
		}
		// helle Farben bekommen dunkle Schrift
		int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
		boolean light = r * 299 + g * 587 + b * 114 > 150_000;
		graphics.text(font, text, x0 + 3, y0 + 2, light ? 0xFF1A1438 : 0xFFFFFFFF, !light);
	}

	/** Runder Wegpunkt-Punkt mit duennem dunklen Rand (radius 2 = klein, 3 = normal); Todespunkte als Totenkopf. */
	static void waypoint(GuiGraphicsExtractor graphics, Waypoint waypoint, int x, int y, int radius) {
		if (waypoint.isDeath()) {
			skull(graphics, x, y, radius >= 4 ? 2 : 1, waypoint.isOldDeath());
			return;
		}
		dot(graphics, x, y, radius + 1, SHADOW);
		dot(graphics, x, y, radius, 0xFF000000 | waypoint.color);
		// kleiner Glanzpunkt, damit auch dunkle Farben (z. B. Schwarz) sichtbar bleiben
		graphics.fill(x - 1, y - radius + 1, x, y - radius + 2, 0x80FFFFFF);
	}

	/** Gefuellter "Kreis" aus Rechtecken. */
	static void dot(GuiGraphicsExtractor graphics, int x, int y, int r, int color) {
		if (r <= 1) {
			graphics.fill(x - 1, y - 1, x + 1, y + 1, color);
			return;
		}
		graphics.fill(x - r + 1, y - r, x + r - 1, y + r, color);
		graphics.fill(x - r, y - r + 1, x + r, y + r - 1, color);
	}

	/** Rahmen im Luna-Look um ein Rechteck. */
	static void frame(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
		graphics.fill(x0 - 3, y0 - 3, x1 + 3, y1 + 3, luna.map.LunaColors.OUTLINE_DARK);
		graphics.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, luna.map.LunaColors.OUTLINE);
		graphics.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, luna.map.LunaColors.NIGHT);
	}
}
