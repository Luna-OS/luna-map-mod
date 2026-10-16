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

	/** Runder Wegpunkt-Punkt mit duennem dunklen Rand (radius 2 = klein, 3 = normal). */
	static void waypoint(GuiGraphicsExtractor graphics, Waypoint waypoint, int x, int y, int radius) {
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
