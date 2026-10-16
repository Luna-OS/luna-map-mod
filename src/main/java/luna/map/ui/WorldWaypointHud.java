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
 * und als HUD gezeichnet - dadurch unabhaengig von Shaderpacks (Iris) und deren Projektion.
 */
public final class WorldWaypointHud implements HudElement {
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

		for (Waypoint waypoint : MapSession.waypoints().in(MapSession.dimension())) {
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
			int distance = (int) Math.round(Math.sqrt(delta.lengthSquared()));
			MapDraw.waypoint(graphics, waypoint, sx, sy, 3);
			String label = waypoint.name + " (" + distance + " m)";
			int w = mc.font.width(label);
			graphics.fill(sx - w / 2 - 2, sy + 6, sx + w / 2 + 2, sy + 17, 0x99110D28);
			graphics.centeredText(mc.font, label, sx, sy + 8, LunaColors.WHITE);
		}
	}
}
