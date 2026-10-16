package luna.map.data;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import luna.map.LunaMap;

/** Alle Kartenregionen einer Dimension einer Welt, mit Speicherung auf der Platte. */
public final class MapStore {
	private static final int MAGIC = 0x4C554E41; // "LUNA"
	private static final int VERSION = 1;
	private static final int MAX_TEXTURES = 160;

	private final Path dir;
	private final String textureOwner;
	private final Map<Long, MapRegion> regions = new HashMap<>();
	/** Regionen ohne Datei, damit die Weltkarte nicht jedes Bild erneut auf der Platte nachsieht. */
	private final java.util.Set<Long> missing = new java.util.HashSet<>();
	private MapRegion last;
	/** Wird bei jeder Aenderung erhoeht, damit die Minimap weiss, wann sie neu zeichnen muss. */
	public int version;
	/** Laufender Zaehler (Ticks), um lange nicht benutzte Regionen aus dem Speicher zu werfen. */
	private int clock;

	/** Eigenstaendige Karte einer Dimension, z. B. fuer Importe in eine Dimension, in der man gerade nicht ist. */
	public static MapStore open(Path dir, String textureOwner) {
		return new MapStore(dir, textureOwner);
	}

	MapStore(Path dir, String textureOwner) {
		this.dir = dir;
		this.textureOwner = textureOwner;
	}

	private static long key(int rx, int rz) {
		return ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
	}

	/** Region holen, falls noetig von der Platte laden. Mit create=false ggf. null. */
	public MapRegion region(int rx, int rz, boolean create) {
		if (last != null && last.x == rx && last.z == rz) {
			last.touched = clock;
			return last;
		}
		long key = key(rx, rz);
		MapRegion region = regions.get(key);
		if (region == null) {
			if (!create && missing.contains(key)) {
				return null;
			}
			region = load(rx, rz);
			if (region == null && !create) {
				missing.add(key);
			}
			if (region == null && create) {
				region = new MapRegion(rx, rz);
			}
			if (region != null) {
				regions.put(key, region);
				missing.remove(key);
			}
		}
		if (region != null) {
			region.touched = clock;
			last = region;
		}
		return region;
	}

	public int color(int blockX, int blockZ) {
		MapRegion region = region(blockX >> MapRegion.SHIFT, blockZ >> MapRegion.SHIFT, false);
		return region == null ? 0 : region.get(blockX & (MapRegion.SIZE - 1), blockZ & (MapRegion.SIZE - 1));
	}

	void set(int blockX, int blockZ, int argb) {
		MapRegion region = region(blockX >> MapRegion.SHIFT, blockZ >> MapRegion.SHIFT, true);
		region.set(blockX & (MapRegion.SIZE - 1), blockZ & (MapRegion.SIZE - 1), argb);
	}

	/**
	 * Uebernimmt Farben aus einer anderen Quelle (Xaero-Import, BlueMap).
	 * overwrite=false fuellt nur noch unbekannte Stellen, keep entscheidet pro Block, ob er bleibt.
	 */
	public void merge(int blockX0, int blockZ0, int width, int height, int[] colors, boolean overwrite, BlockFilter keep) {
		boolean changed = false;
		for (int dz = 0; dz < height; dz++) {
			for (int dx = 0; dx < width; dx++) {
				int color = colors[dz * width + dx];
				if (color == 0) {
					continue;
				}
				int x = blockX0 + dx;
				int z = blockZ0 + dz;
				if (keep != null && keep.test(x, z)) {
					continue;
				}
				if (!overwrite && color(x, z) != 0) {
					continue;
				}
				set(x, z, color);
				changed = true;
			}
		}
		if (changed) {
			version++;
		}
	}

	/** Eine andere MapStore-Instanz hat die Datei dieser Region geschrieben: beim naechsten Zugriff neu lesen. */
	public void forget(int rx, int rz) {
		missing.remove(key(rx, rz));
	}

	/** Ist die Region gerade im Speicher (dann muss in diese Instanz geschrieben werden)? */
	public boolean isLoaded(int rx, int rz) {
		return regions.containsKey(key(rx, rz));
	}

	@FunctionalInterface
	public interface BlockFilter {
		boolean test(int x, int z);
	}

	public String textureOwner() {
		return textureOwner;
	}

	/** Haelt die Zahl der GPU-Texturen klein, indem lange nicht gezeigte freigegeben werden. */
	public void trimTextures() {
		List<MapRegion> withTexture = new ArrayList<>();
		for (MapRegion region : regions.values()) {
			if (region.hasTexture()) {
				withTexture.add(region);
			}
		}
		if (withTexture.size() <= MAX_TEXTURES) {
			return;
		}
		withTexture.sort(Comparator.comparingLong(r -> r.lastUsed));
		for (int i = 0; i < withTexture.size() - MAX_TEXTURES; i++) {
			withTexture.get(i).releaseTexture();
		}
	}

	private Path file(int rx, int rz) {
		return dir.resolve("r." + rx + "." + rz + ".lmap");
	}

	private MapRegion load(int rx, int rz) {
		Path file = file(rx, rz);
		if (!Files.exists(file)) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
			if (in.readInt() != MAGIC || in.readInt() != VERSION) {
				return null;
			}
			MapRegion region = new MapRegion(rx, rz);
			for (int i = 0; i < region.colors.length; i++) {
				region.colors[i] = in.readInt();
			}
			return region;
		} catch (IOException e) {
			LunaMap.LOGGER.warn("Kartenregion {} ist beschaedigt, wird neu erkundet", file, e);
			return null;
		}
	}

	public void save() {
		for (MapRegion region : regions.values()) {
			save(region);
		}
	}

	/** Speichert alles und gibt den Speicher frei (fuer Karten von Dimensionen, in denen man nicht ist). */
	public void flush() {
		save();
		regions.clear();
		missing.clear();
		last = null;
	}

	/** Wirft Regionen aus dem Speicher, die laenger als maxAge Ticks nicht gebraucht wurden. */
	public void tick(int maxAge) {
		clock++;
		if (clock % 100 != 0) {
			return;
		}
		java.util.Iterator<MapRegion> it = regions.values().iterator();
		while (it.hasNext()) {
			MapRegion region = it.next();
			if (clock - region.touched > maxAge) {
				save(region);
				region.releaseTexture();
				it.remove();
				if (last == region) {
					last = null;
				}
			}
		}
	}

	private void save(MapRegion region) {
		if (!region.unsaved) {
			return;
		}
		Path file = file(region.x, region.z);
		try {
			Files.createDirectories(dir);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
					new GZIPOutputStream(Files.newOutputStream(tmp)), 1 << 16))) {
				out.writeInt(MAGIC);
				out.writeInt(VERSION);
				for (int c : region.colors) {
					out.writeInt(c);
				}
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			region.unsaved = false;
		} catch (IOException e) {
			LunaMap.LOGGER.warn("Konnte Kartenregion {} nicht speichern", file, e);
		}
	}

	void close() {
		save();
		for (MapRegion region : regions.values()) {
			region.releaseTexture();
		}
		regions.clear();
		last = null;
	}
}
