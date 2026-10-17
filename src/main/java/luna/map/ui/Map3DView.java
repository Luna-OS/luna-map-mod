package luna.map.ui;

import com.mojang.blaze3d.platform.NativeImage;

import luna.map.LunaColors;
import luna.map.LunaMap;
import luna.map.data.MapRegion;
import luna.map.data.MapStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * 3D-Ansicht der Weltkarte: das Hoehenrelief wird pro Bildspalte von vorne nach hinten abgetastet (wie bei alten
 * Flugsimulatoren) und in eine einzige Textur gezeichnet. Neu gerechnet wird nur, wenn sich Kamera oder Kartendaten
 * aendern, waehrend des Drehens in halber Aufloesung – so kostet die Ansicht kaum FPS.
 */
final class Map3DView {
	private static final Identifier ID = LunaMap.id("worldmap_3d");
	/** Unerkundeter Boden. */
	private static final int UNKNOWN = 0xFF1A1438;
	private static final int FOG = 0xFF3A2E73;
	private static final long DATA_REFRESH_MS = 400;

	private DynamicTexture texture;
	private int texW;
	private int texH;

	// Kamera des zuletzt gezeichneten Bildes (auch fuer die Projektion von Wegpunkten)
	private double camX, camY, camZ;
	private double fX, fY, fZ;
	private double rX, rZ;
	private double uX, uY, uZ;
	private double focal;
	private int outW, outH, guiW, guiH;
	private double targetY = Double.NaN;
	/** Sichtweite in Bloecken, waechst mit dem Abstand der Kamera. */
	private double maxDistance = 900;

	private String lastKey = "";
	private long lastRender;
	private int lastVersion = -1;
	private boolean regionsMissing;

	/** Kamera einstellen und bei Bedarf neu zeichnen; danach die Textur ueber den ganzen Bildschirm legen. */
	void draw(GuiGraphicsExtractor graphics, MapStore store, double centerX, double centerZ, double scale, double yaw,
			double tilt, int width, int height, boolean moving) {
		guiW = width;
		guiH = height;
		int div = moving ? 2 : 1;
		int w = Math.max(64, Math.min(640, width / div));
		int h = Math.max(36, (int) Math.round(w * (double) height / width));

		// Hoehe in der Bildmitte sanft nachfuehren, damit die Kamera nicht springt
		int center = store.height((int) Math.floor(centerX), (int) Math.floor(centerZ));
		double wanted = center == MapRegion.NO_HEIGHT ? (Double.isNaN(targetY) ? 64 : targetY) : center;
		targetY = Double.isNaN(targetY) ? wanted : targetY + (wanted - targetY) * 0.25;

		setupCamera(centerX, centerZ, scale, yaw, tilt, w, h);
		String key = String.format("%.2f|%.2f|%.4f|%.4f|%.4f|%d|%d|%.1f", centerX, centerZ, scale, yaw, tilt, w, h, targetY);
		long now = System.currentTimeMillis();
		boolean dataChanged = (store.version != lastVersion || regionsMissing) && now - lastRender > DATA_REFRESH_MS;
		if (!key.equals(lastKey) || dataChanged || texture == null) {
			lastKey = key;
			lastVersion = store.version;
			lastRender = now;
			long t0 = System.nanoTime();
			render(store, w, h);
			if (Boolean.getBoolean("lunamap.debug")) {
				LunaMap.LOGGER.info("3D-Bild {}x{} in {} ms", w, h, (System.nanoTime() - t0) / 100_000 / 10.0);
			}
		}
		graphics.blit(RenderPipelines.GUI_TEXTURED, ID, 0, 0, 0, 0, width, height, texW, texH, texW, texH);
	}

	private void setupCamera(double centerX, double centerZ, double scale, double yaw, double tilt, int w, int h) {
		outW = w;
		outH = h;
		// tilt 0 = von oben, 1 = flach ueber dem Boden (25 Grad)
		double pitch = Math.toRadians(90 - tilt * 65);
		double sin = Math.sin(pitch);
		double cos = Math.cos(pitch);
		double fhX = Math.sin(yaw);
		double fhZ = -Math.cos(yaw);
		rX = Math.cos(yaw);
		rZ = Math.sin(yaw);
		fX = fhX * cos;
		fY = -sin;
		fZ = fhZ * cos;
		uX = fhX * sin;
		uY = cos;
		uZ = fhZ * sin;
		focal = h * 1.0;
		// Abstand so, dass die Mitte etwa so gross ist wie in der 2D-Karte mit diesem Zoom
		double distance = guiH / Math.max(1e-3, scale);
		maxDistance = Math.min(2500, distance * 2 + 400);
		camX = centerX - fX * distance;
		camY = targetY - fY * distance;
		camZ = centerZ - fZ * distance;
	}

	private void render(MapStore store, int w, int h) {
		if (texture == null || texW != w || texH != h) {
			if (texture != null) {
				Minecraft.getInstance().getTextureManager().release(ID);
			}
			texture = new DynamicTexture(() -> "Luna Map 3D", w, h, false);
			Minecraft.getInstance().getTextureManager().register(ID, texture);
			texW = w;
			texH = h;
		}
		NativeImage image = texture.getPixels();
		int[] pixels = new int[w * h];
		// Himmel: Luna-Nachtverlauf
		for (int y = 0; y < h; y++) {
			int sky = mix(0xFF2B2160, LunaColors.NIGHT, y / (float) h);
			java.util.Arrays.fill(pixels, y * w, y * w + w, sky);
		}
		store.loadBudget = 3;
		regionsMissing = false;
		try {
			for (int x = 0; x < w; x++) {
				column(store, pixels, x, w, h);
			}
		} finally {
			regionsMissing = store.loadBudget <= 0;
			store.loadBudget = Integer.MAX_VALUE;
		}
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setPixel(x, y, pixels[y * w + x]);
			}
		}
		texture.upload();
	}

	/** Eine Bildspalte von unten (nah) nach oben (fern). */
	private void column(MapStore store, int[] pixels, int px, int w, int h) {
		double xn = (px + 0.5 - w / 2.0) / focal;
		int ybuf = h;
		double lastX = Double.NaN, lastZ = 0;
		double stepX = 0, stepZ = 0;
		int lastBlockX = Integer.MIN_VALUE, lastBlockZ = Integer.MIN_VALUE;
		for (int py = h - 1; py >= -h && ybuf > 0; py--) {
			double gx, gz;
			double yn = (py + 0.5 - h / 2.0) / focal;
			double dX = fX + rX * xn - uX * yn;
			double dY = fY - uY * yn;
			double dZ = fZ + rZ * xn - uZ * yn;
			if (dY < -1e-4 && py >= 0) {
				// Schnittpunkt dieses Bildpunkts mit der Bodenebene auf Hoehe der Bildmitte
				double t = (targetY - camY) / dY;
				gx = camX + dX * t;
				gz = camZ + dZ * t;
				if (!Double.isNaN(lastX)) {
					stepX = gx - lastX;
					stepZ = gz - lastZ;
				}
			} else {
				// ueber dem Horizont der Ebene: in gleicher Richtung mit wachsenden Schritten weiter (Berge am Horizont)
				if (Double.isNaN(lastX)) {
					return;
				}
				stepX *= 1.06;
				stepZ *= 1.06;
				if (Math.abs(stepX) + Math.abs(stepZ) < 0.5) {
					double len = Math.max(1e-6, Math.hypot(stepX, stepZ));
					stepX = stepX / len * 0.5;
					stepZ = stepZ / len * 0.5;
				}
				gx = lastX + stepX;
				gz = lastZ + stepZ;
			}
			lastX = gx;
			lastZ = gz;
			double dist = Math.hypot(gx - camX, gz - camZ);
			if (dist > maxDistance) {
				return;
			}
			int bx = (int) Math.floor(gx);
			int bz = (int) Math.floor(gz);
			if (bx == lastBlockX && bz == lastBlockZ && py < h - 1) {
				continue; // gleicher Block wie eben, nichts Neues
			}
			lastBlockX = bx;
			lastBlockZ = bz;
			int color = store.color(bx, bz);
			int height = color == 0 ? MapRegion.NO_HEIGHT : store.height(bx, bz);
			if (color == 0) {
				color = UNKNOWN;
			}
			double y = height == MapRegion.NO_HEIGHT ? targetY : height + 1;
			// den Block oben in der Mitte auf den Bildschirm projizieren
			double vx = bx + 0.5 - camX, vy = y - camY, vz = bz + 0.5 - camZ;
			double depth = vx * fX + vy * fY + vz * fZ;
			if (depth <= 0.1) {
				continue;
			}
			double up = vx * uX + vy * uY + vz * uZ;
			int sy = (int) Math.floor(h / 2.0 - focal * up / depth);
			if (sy >= ybuf) {
				continue;
			}
			// Licht von Nordwesten: Hangneigung macht das Relief sichtbar
			int west = store.height(bx - 1, bz - 1);
			float light = west == MapRegion.NO_HEIGHT || height == MapRegion.NO_HEIGHT ? 1f
					: Math.max(0.7f, Math.min(1.3f, 1f + (height - west) * 0.07f));
			int top = shade(color, light);
			int wall = shade(color, light * 0.72f);
			float fog = (float) Math.max(0, (dist - maxDistance * 0.45) / (maxDistance * 0.55));
			if (fog > 0) {
				top = mix(top, FOG, fog);
				wall = mix(wall, FOG, fog);
			}
			int from = Math.max(0, sy);
			for (int y2 = from; y2 < ybuf; y2++) {
				// die obersten zwei Pixel sind die Oberseite, darunter die Seitenwand (Klippen)
				pixels[y2 * w + px] = y2 - sy < 2 ? top : wall;
			}
			ybuf = from;
		}
	}

	/** Bildschirmposition (GUI) eines Punktes, oder null wenn er hinter der Kamera liegt. */
	float[] project(double x, double y, double z) {
		double vx = x - camX, vy = y - camY, vz = z - camZ;
		double depth = vx * fX + vy * fY + vz * fZ;
		if (depth <= 0.5) {
			return null;
		}
		double right = vx * rX + vz * rZ;
		double up = vx * uX + vy * uY + vz * uZ;
		float sx = (float) ((outW / 2.0 + focal * right / depth) * guiW / outW);
		float sy = (float) ((outH / 2.0 - focal * up / depth) * guiH / outH);
		return new float[] {sx, sy};
	}

	/** Bodenpunkt (Hoehe der Bildmitte) unter einer Bildschirmposition, oder null (Himmel). */
	double[] ground(double guiX, double guiY) {
		double xn = (guiX * outW / guiW - outW / 2.0) / focal;
		double yn = (guiY * outH / guiH - outH / 2.0) / focal;
		double dX = fX + rX * xn - uX * yn;
		double dY = fY - uY * yn;
		double dZ = fZ + rZ * xn - uZ * yn;
		if (dY > -1e-4) {
			return null;
		}
		double t = (targetY - camY) / dY;
		return new double[] {camX + dX * t, camZ + dZ * t};
	}

	double targetY() {
		return Double.isNaN(targetY) ? 64 : targetY;
	}

	void release() {
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(ID);
			texture = null;
		}
		lastKey = "";
	}

	private static int shade(int argb, float factor) {
		int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * factor));
		int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * factor));
		int b = Math.min(255, (int) ((argb & 0xFF) * factor));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}
}
