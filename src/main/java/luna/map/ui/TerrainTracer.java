package luna.map.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import luna.map.LunaColors;
import luna.map.data.HeightField;

/**
 * Zeichnet die 3D-Ansicht: pro Bildpunkt ein Sichtstrahl durch das Hoehenfeld (ueber leeren Raum in grossen Schritten
 * dank der Hoehen-Pyramide). Getroffen wird die Oberseite oder eine Seitenwand eines Blocks; dazu kommen Licht vom Mond
 * aus Nordwesten mit Schatten, Umgebungsverdeckung an Kanten, eine feine Blockstruktur beim Reinzoomen, spiegelndes
 * Wasser, Dunst in der Ferne und ein Sternenhimmel. Die Bildzeilen werden auf mehrere Threads verteilt.
 */
final class TerrainTracer {
	/** Kamera eines Bildes: Blickrichtung f, rechts r (immer waagrecht), oben u; focal in Bildpunkten. */
	record Camera(double x, double y, double z, double fX, double fY, double fZ, double rX, double rZ, double uX,
			double uY, double uZ, double focal, int width, int height, double maxDistance, int floorTop) {
		/** Normierte Blickrichtung durch einen Bildpunkt (Bildkoordinaten, auch Bruchteile). */
		double[] direction(double px, double py) {
			double xn = (px - width / 2.0) / focal;
			double yn = (py - height / 2.0) / focal;
			double dX = fX + rX * xn - uX * yn;
			double dY = fY - uY * yn;
			double dZ = fZ + rZ * xn - uZ * yn;
			double len = Math.sqrt(dX * dX + dY * dY + dZ * dZ);
			return new double[] {dX / len, dY / len, dZ / len};
		}
	}

	/** Neigung 0 = von oben (90 Grad), 1 = flach ueber dem Boden (15 Grad). */
	static double pitch(double tilt) {
		return Math.toRadians(90 - tilt * 75);
	}

	/**
	 * Kamera, die auf (centerX, targetY, centerZ) schaut. Der Abstand ist so gewaehlt, dass die Bildmitte etwa so gross
	 * ist wie in der 2D-Karte mit diesem Zoom (scale = GUI-Pixel pro Block). minY haelt die Kamera ueber dem Gelaende.
	 */
	static Camera camera(double centerX, double centerZ, double targetY, double scale, double yaw, double tilt, int width,
			int height, int guiHeight, double minY) {
		double pitch = pitch(tilt);
		double sin = Math.sin(pitch);
		double cos = Math.cos(pitch);
		double fhX = Math.sin(yaw);
		double fhZ = -Math.cos(yaw);
		double fX = fhX * cos, fY = -sin, fZ = fhZ * cos;
		double distance = guiHeight / Math.max(1e-3, scale);
		double maxDistance = Math.min(2500, distance * 2 + 400);
		double camY = Math.max(targetY - fY * distance, minY);
		return new Camera(centerX - fX * distance, camY, centerZ - fZ * distance, fX, fY, fZ, Math.cos(yaw), Math.sin(yaw),
				fhX * sin, cos, fhZ * sin, height, width, height, maxDistance, (int) Math.floor(targetY) + 1);
	}

	/** Unerkundeter Boden. */
	static final int UNKNOWN = 0xFF1A1438;
	static final int UNKNOWN_GRID = 0xFF2A2152;
	static final int HORIZON = 0xFF3A2E73;
	static final int ZENITH = LunaColors.NIGHT;
	static final int STAR = 0xFFFFF4D6;
	static final int GLINT = LunaColors.MOON;

	// Licht von schraeg oben aus Nordwesten (wie die Schattierung der 2D-Karte), schwaches Gegenlicht aus Suedosten
	private static final double L_X = -0.459, L_Y = 0.819, L_Z = -0.344;
	private static final double FILL_X = 0.85, FILL_Z = 0.53;
	private static final float AMBIENT = 0.6f;
	private static final float SIDE_AMBIENT = 0.9f;
	private static final float SUN = 0.49f;
	private static final float FILL = 0.1f;
	/** Im Schatten bleibt etwas Licht, sonst wird jede Gelaendestufe zu einem harten schwarzen Strich. */
	private static final float SHADOW_LIGHT = 0.35f;
	private static final double SHADOW_DISTANCE = 192;

	private static final int SKY = -1, TOP = 0, WEST = 1, EAST = 2, NORTH = 3, SOUTH = 4;
	private static final int LIT = 0, SHADOWED = 1, NOT_TESTED = 2;
	private static final int ROWS_PER_TASK = 8;
	private static final int THREADS = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));
	private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r -> {
		Thread thread = new Thread(r, "Luna Map 3D");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1); // das Spiel selbst geht vor
		return thread;
	});

	private final Camera cam;
	private final HeightField field;
	private final boolean shadows;

	/** Ergebnis eines Strahls (pro Thread wiederverwendet). */
	private static final class Hit {
		double t;
		int x;
		int z;
		int face;
		int top;
		boolean known;
		int shadowState;
		/** Fuer den Schattenstrahl. */
		Hit shadow;
	}

	TerrainTracer(Camera cam, HeightField field, boolean shadows) {
		this.cam = cam;
		this.field = field;
		this.shadows = shadows;
	}

	Camera camera() {
		return cam;
	}

	int width() {
		return cam.width();
	}

	int height() {
		return cam.height();
	}

	/**
	 * Ganzes Bild (ARGB, Zeile fuer Zeile); der aufrufende Thread rechnet mit. Bei Abbruch null. Zuerst wird jeder zweite
	 * Bildpunkt in jeder zweiten Zeile verfolgt; liegen die Nachbarn eines Zwischenpunkts alle auf derselben Blockseite,
	 * reicht fuer ihn ein Schnitt mit dieser Flaeche statt eines ganzen Strahls.
	 */
	int[] render(BooleanSupplier cancelled) {
		int w = cam.width();
		int h = cam.height();
		int[] pixels = new int[w * h];
		int gw = (w + 1) / 2;
		int gh = (h + 1) / 2;
		Samples samples = new Samples(gw * gh);
		if (!parallel((gh + ROWS_PER_TASK - 1) / ROWS_PER_TASK, cancelled, (task, hit) -> {
			for (int gy = task * ROWS_PER_TASK; gy < Math.min(gh, (task + 1) * ROWS_PER_TASK); gy++) {
				for (int gx = 0; gx < gw; gx++) {
					int px = gx * 2;
					int py = gy * 2;
					pixels[py * w + px] = pixel(px, py, hit, -1);
					samples.store(gy * gw + gx, hit);
				}
			}
		})) {
			return null;
		}
		if (!parallel((h + ROWS_PER_TASK - 1) / ROWS_PER_TASK, cancelled, (task, hit) -> {
			for (int py = task * ROWS_PER_TASK; py < Math.min(h, (task + 1) * ROWS_PER_TASK); py++) {
				for (int px = (py & 1) == 0 ? 1 : 0; px < w; px += (py & 1) == 0 ? 2 : 1) {
					pixels[py * w + px] = between(px, py, gw, gh, samples, hit);
				}
			}
		})) {
			return null;
		}
		return pixels;
	}

	/** Treffer der verfolgten Bildpunkte: Seite (SKY = Himmel), Lage ihrer Ebene und ob dort Schatten lag. */
	private static final class Samples {
		final int[] plane;
		final byte[] face;
		final byte[] shadow;

		Samples(int size) {
			plane = new int[size];
			face = new byte[size];
			shadow = new byte[size];
		}

		void store(int i, Hit hit) {
			face[i] = (byte) hit.face;
			shadow[i] = (byte) hit.shadowState;
			plane[i] = switch (hit.face) {
				case TOP -> hit.top;
				case WEST -> hit.x;
				case EAST -> hit.x + 1;
				case NORTH -> hit.z;
				case SOUTH -> hit.z + 1;
				default -> 0;
			};
		}
	}

	private interface Task {
		void run(int task, Hit hit);
	}

	/** Verteilt tasks Aufgaben auf den Pool und den aufrufenden Thread; false bei Abbruch. */
	private static boolean parallel(int tasks, BooleanSupplier cancelled, Task body) {
		AtomicInteger next = new AtomicInteger();
		CountDownLatch done = new CountDownLatch(tasks);
		Runnable worker = () -> {
			Hit hit = new Hit();
			hit.shadow = new Hit();
			int task;
			while ((task = next.getAndIncrement()) < tasks) {
				try {
					if (!cancelled.getAsBoolean()) {
						body.run(task, hit);
					}
				} finally {
					done.countDown();
				}
			}
		};
		for (int i = Math.min(tasks - 1, THREADS); i > 0; i--) {
			POOL.execute(worker);
		}
		worker.run();
		try {
			done.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
		return !cancelled.getAsBoolean();
	}

	/**
	 * Bildpunkt zwischen verfolgten Punkten: liegen alle Nachbarn auf derselben Ebene (gleich hohe Oberseiten oder
	 * dieselbe Wandflucht), reicht der Schnitt mit dieser Ebene.
	 */
	private int between(int px, int py, int gw, int gh, Samples samples, Hit hit) {
		int gx0 = px >> 1, gy0 = py >> 1;
		int gx1 = (px & 1) == 0 ? gx0 : gx0 + 1;
		int gy1 = (py & 1) == 0 ? gy0 : gy0 + 1;
		if (gx1 >= gw || gy1 >= gh) {
			return pixel(px, py, hit, -1);
		}
		int first = gy0 * gw + gx0;
		int face = samples.face[first];
		int plane = samples.plane[first];
		int shadow = samples.shadow[first];
		for (int gy = gy0; gy <= gy1; gy++) {
			for (int gx = gx0; gx <= gx1; gx++) {
				int i = gy * gw + gx;
				if (samples.face[i] != face || samples.plane[i] != plane) {
					return pixel(px, py, hit, -1);
				}
				if (samples.shadow[i] != shadow) {
					shadow = -1;
				}
			}
		}
		double[] d = direction(px, py);
		if (face == SKY) {
			return stars(d[0], d[1], d[2], sky(d[1]));
		}
		if (!onPlane(hit, d[0], d[1], d[2], face, plane)) {
			return pixel(px, py, hit, -1);
		}
		return shade(hit, d[0], d[1], d[2], shadow == NOT_TESTED ? -1 : shadow);
	}

	/** Schnitt des Strahls mit einer Oberseiten- oder Wandebene; false, wenn dort keine passende Blockseite liegt. */
	private boolean onPlane(Hit hit, double dX, double dY, double dZ, int face, int plane) {
		double t;
		switch (face) {
			case TOP -> t = dY < 0 ? (plane - cam.y()) / dY : -1;
			case WEST -> t = dX > 0 ? (plane - cam.x()) / dX : -1;
			case EAST -> t = dX < 0 ? (plane - cam.x()) / dX : -1;
			case NORTH -> t = dZ > 0 ? (plane - cam.z()) / dZ : -1;
			default -> t = dZ < 0 ? (plane - cam.z()) / dZ : -1;
		}
		if (t <= 0 || t > cam.maxDistance()) {
			return false;
		}
		double hx = cam.x() + dX * t;
		double hy = cam.y() + dY * t;
		double hz = cam.z() + dZ * t;
		int bx = face == WEST ? plane : face == EAST ? plane - 1 : (int) Math.floor(hx);
		int bz = face == NORTH ? plane : face == SOUTH ? plane - 1 : (int) Math.floor(hz);
		int node = field.node(0, bx, bz);
		boolean known = (node & 1) == 0;
		int top = known ? (node >> 1) + 1 : cam.floorTop();
		if (face == TOP) {
			if (top != plane) {
				return false;
			}
		} else {
			// die Wand muss an dieser Stelle frei liegen: Block reicht bis hy, der Nachbar davor nicht
			int fx = face == WEST ? -1 : face == EAST ? 1 : 0;
			int fz = face == NORTH ? -1 : face == SOUTH ? 1 : 0;
			int front = field.node(0, bx + fx, bz + fz);
			int frontTop = (front & 1) == 0 ? (front >> 1) + 1 : cam.floorTop();
			if (hy > top + 1e-6 || hy < frontTop - 1e-6) {
				return false;
			}
		}
		hit.t = t;
		hit.x = bx;
		hit.z = bz;
		hit.face = face;
		hit.top = top;
		hit.known = known;
		return true;
	}

	private double[] direction(int px, int py) {
		return cam.direction(px + 0.5, py + 0.5);
	}

	/** Erster getroffener Block entlang der Blickrichtung (normiert), oder null. Fuer Mausklicks. */
	double[] pick(double dX, double dY, double dZ) {
		Hit hit = new Hit();
		if (!trace(hit, cam.x(), cam.y(), cam.z(), dX, dY, dZ, cam.maxDistance(), false)) {
			return null;
		}
		return new double[] {cam.x() + dX * hit.t, cam.y() + dY * hit.t, cam.z() + dZ * hit.t};
	}

	/** Bildpunkt mit eigenem Strahl. shadowHint: -1 = Schatten pruefen, sonst LIT bzw. SHADOWED uebernehmen. */
	private int pixel(int px, int py, Hit hit, int shadowHint) {
		double[] d = direction(px, py);
		if (!trace(hit, cam.x(), cam.y(), cam.z(), d[0], d[1], d[2], cam.maxDistance(), false)) {
			hit.face = SKY;
			hit.shadowState = NOT_TESTED;
			return stars(d[0], d[1], d[2], sky(d[1]));
		}
		return shade(hit, d[0], d[1], d[2], shadowHint);
	}

	/** Farbe eines Treffers (hit.t, Block, Seite muessen stimmen); merkt sich in hit.shadowState, ob Schatten lag. */
	private int shade(Hit hit, double dX, double dY, double dZ, int shadowHint) {
		hit.shadowState = NOT_TESTED;
		double t = hit.t;
		double hx = cam.x() + dX * t;
		double hy = cam.y() + dY * t;
		double hz = cam.z() + dZ * t;
		int bx = hit.x;
		int bz = hit.z;
		int face = hit.face;
		int color = field.color(bx, bz);
		// Bildpunkte pro Block an dieser Stelle: bestimmt, wie viel Feinstruktur sichtbar ist
		double ppb = cam.focal() / Math.max(1e-3, t);

		int nX = face == WEST ? -1 : face == EAST ? 1 : 0;
		int nZ = face == NORTH ? -1 : face == SOUTH ? 1 : 0;
		int nY = face == TOP ? 1 : 0;
		if (color == 0) {
			int result = UNKNOWN;
			if (face == TOP && ppb > 0.6) {
				// Chunk-Raster im Unerkundeten, damit man trotzdem Boden und Neigung erkennt
				double line = Math.max(0.06, 1.0 / ppb);
				double gx = hx - Math.floor(hx / 16) * 16;
				double gz = hz - Math.floor(hz / 16) * 16;
				if (gx < line || gz < line) {
					result = mix(UNKNOWN, UNKNOWN_GRID, (float) Math.min(1, (ppb - 0.6) * 2));
				}
			}
			return fog(result, t);
		}

		float direct = (float) (nX * L_X + nY * L_Y + nZ * L_Z);
		boolean inShadow = false;
		if (direct > 0 && shadows) {
			inShadow = shadowHint >= 0 ? shadowHint == SHADOWED : shadowed(hit.shadow, hx, hy, hz, nX, nY, nZ);
			hit.shadowState = inShadow ? SHADOWED : LIT;
		}

		if (face == TOP && isWater(color)) {
			float light = inShadow ? 0.8f : 1f;
			int result = shade(color, light);
			// Wasser spiegelt den Himmel, je flacher man draufschaut, desto mehr
			float fresnel = (float) (0.03 + 0.55 * Math.pow(1 - Math.max(0, -dY), 3));
			result = mix(result, sky(-dY), fresnel);
			double spec = dX * L_X - dY * L_Y + dZ * L_Z;
			if (spec > 0.9 && !inShadow) {
				// Glitzern im Mondlicht, leicht unruhig wie Wellen
				float ripple = 0.6f + 0.4f * unit(hash((int) Math.floor(hx * 4), (int) Math.floor(hz * 4), 3));
				result = mix(result, GLINT, (float) Math.pow((spec - 0.9) / 0.1, 6) * 0.55f * ripple);
			}
			return fog(result, t);
		}

		float fill = (float) Math.max(0, nX * FILL_X + nZ * FILL_Z) * FILL;
		float ambient = AMBIENT * (face == TOP ? 1f : SIDE_AMBIENT) * occlusion(hit, hx, hy, hz);
		float light = ambient + fill + (direct > 0 ? SUN * direct * (inShadow ? SHADOW_LIGHT : 1f) : 0);

		// Feinstruktur: leichte Helligkeitsunterschiede pro Block und, ganz nah, pro "Texel" (16 pro Block)
		float blockAmp = smooth(1.5, 5, ppb) * 0.05f;
		float texAmp = smooth(9, 26, ppb) * 0.1f;
		if (blockAmp > 0) {
			light += blockAmp * (unit(hash(bx, bz, face == TOP ? 1 : 2)) * 2 - 1);
		}
		if (texAmp > 0) {
			int tu, tv;
			if (face == TOP) {
				tu = (int) Math.floor(hx * 16);
				tv = (int) Math.floor(hz * 16);
			} else if (nX != 0) {
				tu = (int) Math.floor(hz * 16);
				tv = (int) Math.floor(hy * 16);
			} else {
				tu = (int) Math.floor(hx * 16);
				tv = (int) Math.floor(hy * 16);
			}
			float n = unit(hash(tu, tv, face + 11)) * 0.7f + unit(hash(tu >> 1, tv >> 1, face + 23)) * 0.3f;
			light += texAmp * (n * 2 - 1);
		}
		return fog(shade(color, light), t);
	}

	/** Umgebungsverdeckung: Oberseiten neben hoeheren Bloecken und Wandfuesse werden dunkler. */
	private float occlusion(Hit hit, double hx, double hy, double hz) {
		int bx = hit.x;
		int bz = hit.z;
		if (!hit.known) {
			return 1f;
		}
		switch (hit.face) {
			case TOP -> {
				int h = hit.top;
				float w = occ(bx - 1, bz, h), e = occ(bx + 1, bz, h), n = occ(bx, bz - 1, h), s = occ(bx, bz + 1, h);
				float nw = corner(w, n, occ(bx - 1, bz - 1, h));
				float ne = corner(e, n, occ(bx + 1, bz - 1, h));
				float sw = corner(w, s, occ(bx - 1, bz + 1, h));
				float se = corner(e, s, occ(bx + 1, bz + 1, h));
				float fx = (float) (hx - bx);
				float fz = (float) (hz - bz);
				float occlusion = (nw * (1 - fx) + ne * fx) * (1 - fz) + (sw * (1 - fx) + se * fx) * fz;
				return 1f - 0.45f * occlusion;
			}
			default -> {
				int fx = hit.face == WEST ? -1 : hit.face == EAST ? 1 : 0;
				int fz = hit.face == NORTH ? -1 : hit.face == SOUTH ? 1 : 0;
				int front = field.height(bx + fx, bz + fz);
				double floor = front == HeightField.NO_HEIGHT ? cam.floorTop() : front + 1;
				float above = (float) Math.max(0, Math.min(1, (hy - floor) / 1.6));
				return 0.6f + 0.4f * above;
			}
		}
	}

	/** Wie stark ein Nachbar ueber die Oberseite (Hoehe top) ragt: 0 = gar nicht, 1 = zwei Bloecke oder mehr. */
	private float occ(int x, int z, int top) {
		int h = field.height(x, z);
		if (h == HeightField.NO_HEIGHT) {
			return 0;
		}
		return Math.max(0, Math.min(1, (h + 1 - top) * 0.5f));
	}

	private static float corner(float side1, float side2, float corner) {
		return (side1 + side2 + Math.max(corner, side1 * side2)) / 3f;
	}

	private boolean shadowed(Hit scratch, double hx, double hy, double hz, int nX, int nY, int nZ) {
		return trace(scratch, hx + nX * 0.01 + L_X * 0.01, hy + nY * 0.01 + 0.005, hz + nZ * 0.01 + L_Z * 0.01, L_X, L_Y, L_Z,
				SHADOW_DISTANCE, true);
	}

	/**
	 * Strahl ab (ox, oy, oz) in Richtung d (normiert) bis maxT. Steigt durch die Hoehen-Pyramide ab, wo der Strahl tief
	 * genug kommt, und springt sonst ganze Zellen weiter. shadow: unerkundeter Boden wirft keinen Schatten.
	 */
	private boolean trace(Hit hit, double ox, double oy, double oz, double dX, double dY, double dZ, double maxT,
			boolean shadow) {
		int floorTop = cam.floorTop();
		int highest = field.maxHeight == HeightField.NO_HEIGHT ? Integer.MIN_VALUE / 2 : field.maxHeight + 1;
		int ceiling = shadow ? highest : Math.max(highest, floorTop);
		if (ceiling == Integer.MIN_VALUE / 2) {
			return false;
		}
		double t = 0;
		if (oy > ceiling) {
			if (dY >= 0) {
				return false;
			}
			t = (ceiling - oy) / dY;
		}
		double invX = dX == 0 ? Double.POSITIVE_INFINITY : 1 / dX;
		double invZ = dZ == 0 ? Double.POSITIVE_INFINITY : 1 / dZ;
		int level = HeightField.TOP_LEVEL;
		int axis = -1;
		while (t < maxT) {
			double px = ox + dX * t;
			double pz = oz + dZ * t;
			int bx = (int) Math.floor(px);
			int bz = (int) Math.floor(pz);
			int cx = bx >> level;
			int cz = bz >> level;
			double tx = dX > 0 ? (((cx + 1) << level) - ox) * invX : dX < 0 ? ((cx << level) - ox) * invX : Double.POSITIVE_INFINITY;
			double tz = dZ > 0 ? (((cz + 1) << level) - oz) * invZ : dZ < 0 ? ((cz << level) - oz) * invZ : Double.POSITIVE_INFINITY;
			double exit = Math.min(tx, tz);
			int node = field.node(level, cx, cz);
			int top = node >> 1;
			boolean unknown = (node & 1) != 0;
			if (unknown && !shadow) {
				top = Math.max(top, floorTop - 1);
			}
			top++;
			double yIn = oy + dY * t;
			double yOut = oy + dY * Math.min(exit, maxT);
			if (Math.min(yIn, yOut) >= top) {
				if (dY >= 0 && yIn >= ceiling) {
					return false;
				}
				axis = tx < tz ? 0 : 1;
				t = exit + 1e-7;
				// eine Stufe groeber nur, wenn der Strahl dabei auch die groessere Zelle verlaesst
				int ncx = axis == 0 ? cx + (dX > 0 ? 1 : -1) : cx;
				int ncz = axis == 1 ? cz + (dZ > 0 ? 1 : -1) : cz;
				while (level < HeightField.TOP_LEVEL && ((ncx >> 1) != (cx >> 1) || (ncz >> 1) != (cz >> 1))) {
					level++;
					cx >>= 1;
					cz >>= 1;
					ncx >>= 1;
					ncz >>= 1;
				}
				continue;
			}
			if (level > 0) {
				level--;
				continue;
			}
			if (yIn > top - 1e-9 || axis < 0) {
				if (yIn < top - 1e-9) {
					// Kamera steckt in diesem Block: ueberspringen
					axis = tx < tz ? 0 : 1;
					t = exit + 1e-7;
					continue;
				}
				hit.face = TOP;
				hit.t = dY < 0 ? Math.max(t, (top - oy) / dY) : t;
			} else {
				hit.face = axis == 0 ? (dX > 0 ? WEST : EAST) : (dZ > 0 ? NORTH : SOUTH);
				hit.t = t;
			}
			hit.x = bx;
			hit.z = bz;
			hit.top = top;
			hit.known = !unknown;
			return true;
		}
		return false;
	}

	/** Himmelsfarbe nach Hoehe der Blickrichtung (dY normiert, positiv = nach oben). */
	static int sky(double dY) {
		float up = (float) Math.sqrt(Math.max(0, Math.min(1, dY * 2.2)));
		return mix(HORIZON, ZENITH, up);
	}

	private int stars(double dX, double dY, double dZ, int base) {
		if (dY < 0.03) {
			return base;
		}
		double cell = 0.012;
		double az = Math.atan2(dX, -dZ);
		double el = Math.asin(Math.min(1, dY));
		int ca = (int) Math.floor(az / cell);
		int ce = (int) Math.floor(el / cell);
		int h = hash(ca, ce, 7);
		if ((h & 0xFF) > 7) {
			return base;
		}
		double sa = (ca + 0.2 + 0.6 * ((h >>> 8) & 0xFF) / 255.0) * cell;
		double se = (ce + 0.2 + 0.6 * ((h >>> 16) & 0xFF) / 255.0) * cell;
		double radius = 0.9 / cam.focal();
		double dist = Math.hypot((az - sa) * Math.cos(el), el - se);
		if (dist > radius) {
			return base;
		}
		float bright = (0.35f + 0.65f * ((h >>> 24) & 0xFF) / 255f) * (float) (1 - dist / radius)
				* (float) Math.min(1, (dY - 0.03) * 12);
		return mix(base, STAR, bright);
	}

	private int fog(int argb, double t) {
		double start = cam.maxDistance() * 0.5;
		if (t <= start) {
			return argb;
		}
		float f = (float) Math.min(1, (t - start) / (cam.maxDistance() - start));
		return mix(argb, HORIZON, f * f * (3 - 2 * f));
	}

	/** Wasser erkennt man nur an der Farbe (die Karte speichert keine Blockart): deutlich blau. */
	private static boolean isWater(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		return b > r + 40 && b >= g;
	}

	private static float smooth(double from, double to, double value) {
		double f = Math.max(0, Math.min(1, (value - from) / (to - from)));
		return (float) (f * f * (3 - 2 * f));
	}

	private static int hash(int a, int b, int c) {
		int h = a * 0x27D4EB2D ^ b * 0x165667B1 ^ c * 0x9E3779B9;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 12;
		h *= 0x297A2D39;
		h ^= h >>> 15;
		return h;
	}

	private static float unit(int hash) {
		return (hash & 0xFFFF) / 65535f;
	}

	static int shade(int argb, float factor) {
		factor = Math.max(0, factor);
		int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * factor));
		int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * factor));
		int b = Math.min(255, (int) ((argb & 0xFF) * factor));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}
}
