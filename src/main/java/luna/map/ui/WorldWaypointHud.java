package luna.map.ui;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import luna.map.LunaColors;
import luna.map.LunaConfig;
import luna.map.data.MapSession;
import luna.map.data.Waypoint;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

/**
 * Wegpunkte in der Welt. Die Position wird selbst aus Kamera-Vektoren und Sichtfeld berechnet
 * und als HUD gezeichnet - dadurch unabhaengig von Shaderpacks (Iris) und deren Projektion. Name und Entfernung
 * erscheinen nur beim Wegpunkt, auf den man schaut, damit sich nichts ueberlappt.
 */
public final class WorldWaypointHud implements HudElement {
	/** So nah (GUI-Pixel) am Fadenkreuz muss ein Wegpunkt sein, damit sein Name erscheint. */
	private static final double FOCUS_RADIUS = 28;
	private final Vector3f delta = new Vector3f();

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (!LunaConfig.get().worldWaypoints || MapSession.waypoints() == null) {
			return;
		}
		Camera camera = mc.gameRenderer.mainCamera();
		if (!camera.isInitialized()) {
			return;
		}
		Vec3 cam = camera.position();
		Vector3fc forward = camera.forwardVector();
		Vector3fc up = camera.upVector();
		Vector3fc left = camera.leftVector();
		int width = mc.getWindow().getGuiScaledWidth();
		int height = mc.getWindow().getGuiScaledHeight();
		float focal = (float) (height / 2.0 / Math.tan(Math.toRadians(camera.getFov()) / 2.0));

		// erst alle Punkte zeichnen; Name und Entfernung nur fuer den, auf den man gerade schaut (naechster zum Fadenkreuz)
		Waypoint focused = null;
		int focusX = 0, focusY = 0, focusDistance = 0;
		double best = FOCUS_RADIUS * FOCUS_RADIUS;
		for (Waypoint waypoint : MapSession.waypoints().visible(MapSession.dimension())) {
			delta.set((float) (waypoint.x + 0.5 - cam.x), (float) (waypoint.y + 1.0 - cam.y), (float) (waypoint.z + 0.5 - cam.z));
			float depth = delta.dot(forward);
			if (depth < 0.1f) {
				continue;
			}
			int sx = Math.round(width / 2f - delta.dot(left) / depth * focal);
			int sy = Math.round(height / 2f - delta.dot(up) / depth * focal);
			if (sx < -50 || sx > width + 50 || sy < -50 || sy > height + 50) {
				continue;
			}
			MapDraw.badge(graphics, mc.font, waypoint, sx, sy, false);
			double cx = sx - width / 2.0;
			double cy = sy - height / 2.0;
			double centerDistance = cx * cx + cy * cy;
			if (centerDistance < best) {
				best = centerDistance;
				focused = waypoint;
				focusX = sx;
				focusY = sy;
				focusDistance = (int) Math.round(Math.sqrt(delta.lengthSquared()));
			}
		}
		if (focused != null) {
			// angeschauter Punkt etwas groesser, darunter Name und Entfernung
			MapDraw.badge(graphics, mc.font, focused, focusX, focusY, true);
			String label = focused.name + " (" + focusDistance + " m)";
			int w = mc.font.width(label);
			graphics.fill(focusX - w / 2 - 3, focusY + 7, focusX + w / 2 + 3, focusY + 19, 0xB0110D28);
			graphics.centeredText(mc.font, label, focusX, focusY + 9, LunaColors.WHITE);
		}
	}
}
