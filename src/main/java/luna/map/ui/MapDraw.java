package luna.map.ui;

import luna.map.LunaColors;
import luna.map.LunaMap;
import luna.map.data.Waypoint;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Gemeinsame Zeichenhilfen fuer Minimap und Weltkarte. */
final class MapDraw {
	static final Identifier ARROW = LunaMap.id("textures/gui/arrow.png");

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

	/** Kleines Wegpunkt-Schild mit Anfangsbuchstaben. */
	static void waypoint(GuiGraphicsExtractor graphics, Font font, Waypoint waypoint, int x, int y) {
		graphics.fill(x - 5, y - 5, x + 5, y + 5, LunaColors.NIGHT);
		graphics.fill(x - 4, y - 4, x + 4, y + 4, waypoint.color);
		graphics.centeredText(font, waypoint.initial(), x + 1, y - 3, LunaColors.NIGHT);
	}

	/** Rahmen im Luna-Look um ein Rechteck. */
	static void frame(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
		graphics.fill(x0 - 3, y0 - 3, x1 + 3, y1 + 3, LunaColors.OUTLINE_DARK);
		graphics.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, LunaColors.OUTLINE);
		graphics.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, LunaColors.NIGHT);
	}
}
