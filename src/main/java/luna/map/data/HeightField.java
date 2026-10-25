package luna.map.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hoehen und Farben eines Kartenausschnitts fuer die 3D-Ansicht. Wird auf dem Haupt-Thread aufgenommen und darf danach
 * aus mehreren Threads gelesen werden. Pro Region gibt es eine Hoehen-Pyramide (hoechster Block je 2x2, 4x4, ... 256x256
 * Bloecke), damit ein Sichtstrahl ueber leeren Raum grosse Schritte machen kann.
 */
public final class HeightField {
	/** Feinste Stufe = einzelner Block, groebste (8) = ganze Region. */
	public static final int TOP_LEVEL = MapRegion.SHIFT;
	public static final int NO_HEIGHT = MapRegion.NO_HEIGHT;
	/** Unter allen echten Hoehen (MapRegion speichert -2048 bis 4095), passt kodiert noch in ein short. */
	private static final int NOTHING = -4096;
	/** Knoten ohne bekannten Block, aber mit unbekannten Stellen. */
	public static final int UNKNOWN_NODE = NOTHING * 2 + 1;

	/** Pyramiden bleiben zwischen den Bildern erhalten, bis sich die Region aendert. */
	private static final Map<MapRegion, Pyramid> PYRAMIDS = new WeakHashMap<>();

	private static final class Pyramid {
		final int changes;
		/** [Stufe][z * Breite + x] = (hoechster bekannter Block << 1) | 1, falls Hoehen darin fehlen. Stufe 0 bleibt leer. */
		final short[][] levels = new short[TOP_LEVEL + 1][];

		Pyramid(MapRegion region) {
			changes = region.changes;
			int size = MapRegion.SIZE / 2;
			short[] level = new short[size * size];
			short[] heights = region.heights;
			for (int z = 0; z < size; z++) {
				for (int x = 0; x < size; x++) {
					int i = (z * 2) * MapRegion.SIZE + x * 2;
					level[z * size + x] = (short) combine(encode(heights[i]), encode(heights[i + 1]),
							encode(heights[i + MapRegion.SIZE]), encode(heights[i + MapRegion.SIZE + 1]));
				}
			}
			levels[1] = level;
			for (int l = 2; l <= TOP_LEVEL; l++) {
				short[] fine = levels[l - 1];
				int fineSize = size;
				size /= 2;
				level = new short[size * size];
				for (int z = 0; z < size; z++) {
					for (int x = 0; x < size; x++) {
						int i = (z * 2) * fineSize + x * 2;
						level[z * size + x] = (short) combine(fine[i], fine[i + 1], fine[i + fineSize], fine[i + fineSize + 1]);
					}
				}
				levels[l] = level;
			}
		}
	}

	private final int rx0;
	private final int rz0;
	private final int nrx;
	private final int nrz;
	private final short[][] heights;
	private final int[][] colors;
	private final short[][][] levels;
	private final MapRegion[] regions;
	private final int[] changes;
	/** Hoechster bekannter Block im Ausschnitt, oder NO_HEIGHT. */
	public final int maxHeight;
	/** Es gab Regionen, die noch nicht geladen werden konnten (im naechsten Bild weiter). */
	public final boolean incomplete;

	private HeightField(int rx0, int rz0, int nrx, int nrz, MapRegion[] regions, boolean incomplete) {
		this.rx0 = rx0;
		this.rz0 = rz0;
		this.nrx = nrx;
		this.nrz = nrz;
		this.incomplete = incomplete;
		this.regions = regions;
		changes = new int[regions.length];
		heights = new short[regions.length][];
		colors = new int[regions.length][];
		levels = new short[regions.length][][];
		int max = NO_HEIGHT;
		for (int i = 0; i < regions.length; i++) {
			MapRegion region = regions[i];
			if (region == null) {
				continue;
			}
			Pyramid pyramid = PYRAMIDS.get(region);
			if (pyramid == null || pyramid.changes != region.changes) {
				pyramid = new Pyramid(region);
				PYRAMIDS.put(region, pyramid);
			}
			changes[i] = region.changes;
			heights[i] = region.heights;
			colors[i] = region.colors;
			levels[i] = pyramid.levels;
			max = Math.max(max, pyramid.levels[TOP_LEVEL][0] >> 1);
		}
		maxHeight = max == NOTHING ? NO_HEIGHT : max;
	}

	/**
	 * Nimmt alle Regionen im Rechteck auf (nur auf dem Haupt-Thread). Von der Platte werden hoechstens loadBudget Regionen
	 * geladen, die naechsten zu (nearX, nearZ) zuerst.
	 */
	public static HeightField capture(MapStore store, double minX, double minZ, double maxX, double maxZ, double nearX,
			double nearZ, int loadBudget) {
		int rx0 = (int) Math.floor(minX) >> MapRegion.SHIFT;
		int rz0 = (int) Math.floor(minZ) >> MapRegion.SHIFT;
		int nrx = ((int) Math.floor(maxX) >> MapRegion.SHIFT) - rx0 + 1;
		int nrz = ((int) Math.floor(maxZ) >> MapRegion.SHIFT) - rz0 + 1;
		List<int[]> order = new ArrayList<>(nrx * nrz);
		for (int z = 0; z < nrz; z++) {
			for (int x = 0; x < nrx; x++) {
				order.add(new int[] {x, z});
			}
		}
		double ncx = nearX / MapRegion.SIZE - rx0 - 0.5;
		double ncz = nearZ / MapRegion.SIZE - rz0 - 0.5;
		order.sort(Comparator.comparingDouble(s -> (s[0] - ncx) * (s[0] - ncx) + (s[1] - ncz) * (s[1] - ncz)));
		MapRegion[] regions = new MapRegion[nrx * nrz];
		store.loadBudget = loadBudget;
		try {
			for (int[] slot : order) {
				regions[slot[1] * nrx + slot[0]] = store.region(rx0 + slot[0], rz0 + slot[1], false);
			}
			return new HeightField(rx0, rz0, nrx, nrz, regions, store.loadBudget <= 0);
		} finally {
			store.loadBudget = Integer.MAX_VALUE;
		}
	}

	/** Gleicher Ausschnitt mit denselben, unveraenderten Regionen (dann muss nicht neu gezeichnet werden)? */
	public boolean sameAs(HeightField other) {
		return other != null && rx0 == other.rx0 && rz0 == other.rz0 && nrx == other.nrx && nrz == other.nrz
				&& java.util.Arrays.equals(regions, other.regions) && java.util.Arrays.equals(changes, other.changes);
	}

	/**
	 * Knoten der Pyramide auf einer Stufe (Zellkoordinaten in Bloecken >> level): (hoechster bekannter Block << 1) | 1,
	 * falls darin Hoehen fehlen. Ausserhalb des Ausschnitts UNKNOWN_NODE.
	 */
	public int node(int level, int cx, int cz) {
		int shift = TOP_LEVEL - level;
		int sx = (cx >> shift) - rx0;
		int sz = (cz >> shift) - rz0;
		if (sx < 0 || sz < 0 || sx >= nrx || sz >= nrz) {
			return UNKNOWN_NODE;
		}
		int slot = sz * nrx + sx;
		if (level == 0) {
			short[] h = heights[slot];
			if (h == null) {
				return UNKNOWN_NODE;
			}
			return encode(h[((cz & (MapRegion.SIZE - 1)) << MapRegion.SHIFT) | (cx & (MapRegion.SIZE - 1))]);
		}
		short[][] pyramid = levels[slot];
		if (pyramid == null) {
			return UNKNOWN_NODE;
		}
		int mask = (1 << shift) - 1;
		return pyramid[level][((cz & mask) << shift) | (cx & mask)];
	}

	/** Hoehe des obersten Blocks oder NO_HEIGHT. */
	public int height(int x, int z) {
		int node = node(0, x, z);
		return (node & 1) != 0 ? NO_HEIGHT : node >> 1;
	}

	/** Kartenfarbe (ARGB) oder 0, wenn unerkundet. */
	public int color(int x, int z) {
		int sx = (x >> MapRegion.SHIFT) - rx0;
		int sz = (z >> MapRegion.SHIFT) - rz0;
		if (sx < 0 || sz < 0 || sx >= nrx || sz >= nrz) {
			return 0;
		}
		int[] c = colors[sz * nrx + sx];
		return c == null ? 0 : c[((z & (MapRegion.SIZE - 1)) << MapRegion.SHIFT) | (x & (MapRegion.SIZE - 1))];
	}

	private static int encode(int height) {
		return height == NO_HEIGHT ? UNKNOWN_NODE : height << 1;
	}

	private static int combine(int a, int b, int c, int d) {
		int max = Math.max(Math.max(a >> 1, b >> 1), Math.max(c >> 1, d >> 1));
		return max << 1 | ((a | b | c | d) & 1);
	}
}
