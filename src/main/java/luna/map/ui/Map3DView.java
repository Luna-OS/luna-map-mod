package luna.map.ui;

import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;

import luna.map.LunaMap;
import luna.map.data.HeightField;
import luna.map.data.MapRegion;
import luna.map.data.MapStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * 3D-Ansicht der Weltkarte. Die Bilder rechnet TerrainTracer im Hintergrund, damit das Spiel nie wartet: waehrend man
 * dreht oder schiebt in kleinerer Aufloesung (so gross, wie der Rechner fluessig schafft), danach in voller
 * Bildschirmaufloesung. Neu gerechnet wird nur, wenn sich Kamera oder die sichtbaren Kartendaten aendern.
 */
final class Map3DView {
	private static final Identifier ID_FAST = LunaMap.id("worldmap_3d_fast");
	private static final Identifier ID_FULL = LunaMap.id("worldmap_3d");
	/** So oft wird hoechstens nachgesehen, ob sich die sichtbaren Kartendaten geaendert haben. */
	private static final long DATA_REFRESH_MS = 1000;
	/** Solange noch Regionen von der Platte kommen, oefter. */
	private static final long LOADING_REFRESH_MS = 250;
	/** Ziel fuer ein schnelles Bild beim Drehen. */
	private static final long FAST_TARGET_NANOS = 30_000_000L;
	/** Mehr Bildpunkte rechnet auch die volle Aufloesung nicht (4K wird hochskaliert). */
	private static final int MAX_PIXELS = 1920 * 1200;
	private static final int REGION_LOADS_PER_IMAGE = 6;
	private static final ExecutorService JOBS = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Luna Map 3D-Bild");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	/** Ein Bild in Arbeit. */
	private static final class Job {
		final TerrainTracer tracer;
		final HeightField field;
		final String key;
		final boolean full;
		final int storeVersion;
		volatile boolean cancelled;
		volatile boolean done;
		int[] pixels;
		long nanos;

		Job(TerrainTracer tracer, HeightField field, String key, boolean full, int storeVersion) {
			this.tracer = tracer;
			this.field = field;
			this.key = key;
			this.full = full;
			this.storeVersion = storeVersion;
		}
	}

	private final Canvas fast = new Canvas(ID_FAST, "Luna Map 3D (schnell)");
	private final Canvas full = new Canvas(ID_FULL, "Luna Map 3D");
	private Canvas shownCanvas;
	/** Strahlverfolger (Kamera + Daten) des Bildes auf dem Schirm, fuer Wegpunkte und Mausklicks. */
	private TerrainTracer shown;
	private HeightField shownField;
	private String shownKey = "";
	private boolean shownFull;
	private int shownVersion;
	private Job job;
	private long lastCheck;
	/** Bildpunkte fuer schnelle Bilder, passt sich der Rechenzeit an. */
	private int fastPixels = 160_000;
	private double targetY = Double.NaN;
	private int guiW;
	private int guiH;

	/** Kamera einstellen, fertige Bilder uebernehmen, bei Bedarf ein neues anstossen und das aktuelle Bild zeichnen. */
	void draw(GuiGraphicsExtractor graphics, MapStore store, double centerX, double centerZ, double scale, double yaw,
			double tilt, int width, int height, boolean moving) {
		guiW = width;
		guiH = height;
		// Hoehe in der Bildmitte sanft nachfuehren, damit die Kamera nicht springt
		int center = store.height((int) Math.floor(centerX), (int) Math.floor(centerZ));
		double wanted = center == MapRegion.NO_HEIGHT ? (Double.isNaN(targetY) ? 64 : targetY) : center;
		targetY = Double.isNaN(targetY) || Math.abs(wanted - targetY) < 0.05 ? wanted : targetY + (wanted - targetY) * 0.25;
		String key = String.format("%.2f|%.2f|%.4f|%.4f|%.4f|%d|%d|%.1f", centerX, centerZ, scale, yaw, tilt, width, height,
				targetY);

		finishJob();
		if (job != null && job.full && !job.key.equals(key)) {
			job.cancelled = true; // ein grosses Bild fuer eine alte Kamera lohnt nicht mehr
		}
		if (job == null) {
			long now = System.currentTimeMillis();
			if (!key.equals(shownKey)) {
				start(store, key, centerX, centerZ, scale, yaw, tilt, false, null);
				lastCheck = now;
			} else if (!shownFull && !moving) {
				start(store, key, centerX, centerZ, scale, yaw, tilt, true, null);
				lastCheck = now;
			} else if (shownField != null && (shownField.incomplete || store.version != shownVersion)
					&& now - lastCheck > (shownField.incomplete ? LOADING_REFRESH_MS : DATA_REFRESH_MS)) {
				// Kartendaten haben sich geaendert: nur neu zeichnen, wenn es den sichtbaren Ausschnitt betrifft
				lastCheck = now;
				TerrainTracer.Camera cam = camera(store, centerX, centerZ, scale, yaw, tilt, false);
				HeightField field = capture(store, cam);
				if (!field.sameAs(shownField)) {
					start(store, key, centerX, centerZ, scale, yaw, tilt, !moving, field);
				} else {
					shownVersion = store.version;
				}
			}
		}
		if (shown == null && job != null) {
			// allererstes Bild: kurz warten, damit nicht ein leerer Schirm aufblitzt
			waitFor(job);
			finishJob();
		}
		if (shownCanvas != null && shownCanvas.texture != null) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, shownCanvas.id, 0, 0, 0, 0, width, height, shownCanvas.width,
					shownCanvas.height, shownCanvas.width, shownCanvas.height);
		}
	}

	private TerrainTracer.Camera camera(MapStore store, double centerX, double centerZ, double scale, double yaw, double tilt,
			boolean fullSize) {
		Window window = Minecraft.getInstance().getWindow();
		double aspect = guiW / (double) Math.max(1, guiH);
		int w;
		if (fullSize) {
			w = Math.max(64, window.getWidth());
			w = (int) Math.min(w, Math.sqrt(MAX_PIXELS * aspect));
		} else {
			w = (int) Math.max(64, Math.min(window.getWidth(), Math.sqrt(fastPixels * aspect)));
			w = Math.max(64, w / 16 * 16); // nicht bei jeder kleinen Anpassung eine neue Textur
		}
		int h = Math.max(36, (int) Math.round(w / aspect));
		TerrainTracer.Camera cam = TerrainTracer.camera(centerX, centerZ, targetY, scale, yaw, tilt, w, h, guiH, -1e9);
		// nicht im Berg stecken: Kamera mindestens ein paar Bloecke ueber dem Boden unter ihr
		int below = store.height((int) Math.floor(cam.x()), (int) Math.floor(cam.z()));
		if (below != MapRegion.NO_HEIGHT && cam.y() < below + 4) {
			cam = TerrainTracer.camera(centerX, centerZ, targetY, scale, yaw, tilt, w, h, guiH, below + 4);
		}
		return cam;
	}

	private static HeightField capture(MapStore store, TerrainTracer.Camera cam) {
		double reach = cam.maxDistance();
		return HeightField.capture(store, cam.x() - reach, cam.z() - reach, cam.x() + reach, cam.z() + reach, cam.x(), cam.z(),
				REGION_LOADS_PER_IMAGE);
	}

	private void start(MapStore store, String key, double centerX, double centerZ, double scale, double yaw, double tilt,
			boolean fullSize, HeightField field) {
		TerrainTracer.Camera cam = camera(store, centerX, centerZ, scale, yaw, tilt, fullSize);
		if (field == null) {
			field = capture(store, cam);
		}
		Job next = new Job(new TerrainTracer(cam, field, true), field, key, fullSize, store.version);
		job = next;
		JOBS.execute(() -> {
			long t0 = System.nanoTime();
			try {
				next.pixels = next.tracer.render(() -> next.cancelled);
			} catch (RuntimeException e) {
				LunaMap.LOGGER.warn("3D-Ansicht konnte nicht gezeichnet werden", e);
			} finally {
				next.nanos = System.nanoTime() - t0;
				next.done = true;
			}
		});
	}

	private static void waitFor(Job job) {
		long until = System.currentTimeMillis() + 500;
		while (!job.done && System.currentTimeMillis() < until) {
			java.util.concurrent.locks.LockSupport.parkNanos(200_000);
		}
	}

	/** Fertiges Bild hochladen und anzeigen. */
	private void finishJob() {
		Job done = job;
		if (done == null || !done.done) {
			return;
		}
		job = null;
		if (done.cancelled || done.pixels == null) {
			return;
		}
		if (!done.full) {
			// Aufloesung fuer schnelle Bilder an die Rechenzeit anpassen
			double factor = Math.max(0.6, Math.min(1.5, FAST_TARGET_NANOS / (double) Math.max(1, done.nanos)));
			fastPixels = (int) Math.max(40_000, Math.min(MAX_PIXELS, fastPixels * factor));
		}
		if (Boolean.getBoolean("lunamap.debug")) {
			LunaMap.LOGGER.info("3D-Bild {}x{} in {} ms", done.tracer.width(), done.tracer.height(), done.nanos / 1_000_000);
		}
		Canvas canvas = done.full ? full : fast;
		canvas.upload(done.pixels, done.tracer.width(), done.tracer.height());
		shownCanvas = canvas;
		shown = done.tracer;
		shownField = done.field;
		shownKey = done.key;
		shownFull = done.full;
		shownVersion = done.storeVersion;
	}

	/** Bildschirmposition (GUI) eines Punktes, oder null wenn er hinter der Kamera liegt. */
	float[] project(double x, double y, double z) {
		if (shown == null) {
			return null;
		}
		TerrainTracer.Camera cam = shown.camera();
		double vx = x - cam.x(), vy = y - cam.y(), vz = z - cam.z();
		double depth = vx * cam.fX() + vy * cam.fY() + vz * cam.fZ();
		if (depth <= 0.5) {
			return null;
		}
		double right = vx * cam.rX() + vz * cam.rZ();
		double up = vx * cam.uX() + vy * cam.uY() + vz * cam.uZ();
		float sx = (float) ((cam.width() / 2.0 + cam.focal() * right / depth) * guiW / cam.width());
		float sy = (float) ((cam.height() / 2.0 - cam.focal() * up / depth) * guiH / cam.height());
		return new float[] {sx, sy};
	}

	/** Blockposition {x, z, y} unter einer Bildschirmposition (GUI), oder null (Himmel). */
	double[] ground(double guiX, double guiY) {
		if (shown == null) {
			return null;
		}
		TerrainTracer.Camera cam = shown.camera();
		double[] d = cam.direction(guiX * cam.width() / guiW, guiY * cam.height() / guiH);
		double[] hit = shown.pick(d[0], d[1], d[2]);
		return hit == null ? null : new double[] {hit[0], hit[2], hit[1]};
	}

	void release() {
		if (job != null) {
			job.cancelled = true;
			job = null;
		}
		fast.release();
		full.release();
		shownCanvas = null;
		shown = null;
		shownField = null;
		shownKey = "";
	}

	/** Eine GPU-Textur fuer die fertigen Bilder. */
	private static final class Canvas {
		final Identifier id;
		final String name;
		DynamicTexture texture;
		int width;
		int height;

		Canvas(Identifier id, String name) {
			this.id = id;
			this.name = name;
		}

		/** Uebernimmt das Bild (ARGB, wird dabei umgewandelt). */
		void upload(int[] argb, int w, int h) {
			if (texture == null || width != w || height != h) {
				release();
				texture = new DynamicTexture(() -> name, w, h, false);
				Minecraft.getInstance().getTextureManager().register(id, texture);
				width = w;
				height = h;
			}
			NativeImage image = texture.getPixels();
			// NativeImage liegt als RGBA im Speicher, als Zahl in nativer Reihenfolge also ABGR (wie setPixelABGR)
			for (int i = 0; i < argb.length; i++) {
				int c = argb[i];
				argb[i] = c & 0xFF00FF00 | (c & 0xFF) << 16 | (c >> 16) & 0xFF;
			}
			IntBuffer buffer = image.getPixelBytes().order(ByteOrder.nativeOrder()).asIntBuffer();
			buffer.put(0, argb, 0, Math.min(argb.length, buffer.capacity()));
			texture.upload();
		}

		void release() {
			if (texture != null) {
				Minecraft.getInstance().getTextureManager().release(id);
				texture = null;
			}
		}
	}
}
