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
	/** 1 = nur Farben, 2 = Farben + Hoehen, 3 = dazu Biome. */
	private static final int VERSION = 3;
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
		this.lod = new LodCache(this, dir.resolve("lod"), textureOwner);
	}

	private final LodCache lod;
	/** Wie viele Regionen pro Bild noch von der Platte geladen werden duerfen (die Weltkarte begrenzt das). */
	public int loadBudget = Integer.MAX_VALUE;

	public LodCache lod() {
		return lod;
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
			if (!create && loadBudget <= 0) {
				return null; // naechstes Bild weiter laden, damit es nicht ruckelt
			}
			loadBudget--;
			region = load(rx, rz);
			if (region == null && !create) {
				missing.add(key);
				loadBudget++; // keine Datei: kostet kaum Zeit, zaehlt nicht
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

	/** Farbe und Hoehe zusammen setzen (Scan, BlueMap). */
	void set(int blockX, int blockZ, int argb, int y) {
		MapRegion region = region(blockX >> MapRegion.SHIFT, blockZ >> MapRegion.SHIFT, true);
		region.set(blockX & (MapRegion.SIZE - 1), blockZ & (MapRegion.SIZE - 1), argb);
		region.setHeight(blockX & (MapRegion.SIZE - 1), blockZ & (MapRegion.SIZE - 1), argb == 0 ? MapRegion.NO_HEIGHT : y);
	}

	private void setHeight(int blockX, int blockZ, int y) {
		MapRegion region = region(blockX >> MapRegion.SHIFT, blockZ >> MapRegion.SHIFT, true);
		region.setHeight(blockX & (MapRegion.SIZE - 1), blockZ & (MapRegion.SIZE - 1), y);
	}

	/** Biom-Name ("minecraft:plains") an einer Stelle, oder null wenn unbekannt. */
	public String biome(int blockX, int blockZ) {
		MapRegion region = region(blockX >> MapRegion.SHIFT, blockZ >> MapRegion.SHIFT, false);
		return region == null ? null : Biomes.key(region.biome(blockX & (MapRegion.SIZE - 1), blockZ & (MapRegion.SIZE - 1)));
	}

	void setBiome(int blockX, int blockZ, short id) {
		MapRegion region = region(blockX >> MapRegion.SHIFT, blockZ >> MapRegion.SHIFT, true);
		region.setBiome(blockX & (MapRegion.SIZE - 1), blockZ & (MapRegion.SIZE - 1), id);
	}

	/** Hoehe an einer Stelle oder MapRegion.NO_HEIGHT. */
	public int height(int blockX, int blockZ) {
		MapRegion region = region(blockX >> MapRegion.SHIFT, blockZ >> MapRegion.SHIFT, false);
		return region == null ? MapRegion.NO_HEIGHT : region.height(blockX & (MapRegion.SIZE - 1), blockZ & (MapRegion.SIZE - 1));
	}

	/**
	 * Uebernimmt Farben aus einer anderen Quelle (Xaero-Import, BlueMap).
	 * overwrite=false fuellt nur noch unbekannte Stellen, keep entscheidet pro Block, ob er bleibt.
	 */
	public void merge(int blockX0, int blockZ0, int width, int height, int[] colors, boolean overwrite, BlockFilter keep) {
		merge(blockX0, blockZ0, width, height, colors, null, overwrite, keep);
	}

	/** Wie oben, mit Hoehen (gleiche Anordnung wie colors, MapRegion.NO_HEIGHT = unbekannt). */
	public void merge(int blockX0, int blockZ0, int width, int height, int[] colors, short[] heights, boolean overwrite,
			BlockFilter keep) {
		merge(blockX0, blockZ0, width, height, colors, heights, null, overwrite, keep);
	}

	/** Wie oben, mit Biomen pro Block (Nummern aus Biomes, UNKNOWN = unbekannt; gespeichert wird je 4x4-Feld). */
	public void merge(int blockX0, int blockZ0, int width, int height, int[] colors, short[] heights, short[] biomes,
			boolean overwrite, BlockFilter keep) {
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
				short biome = biomes == null ? Biomes.UNKNOWN : biomes[dz * width + dx];
				if (!overwrite && color(x, z) != 0) {
					// schon bekannt: hoechstens fehlende Hoehe und Biom nachtragen (aeltere Importe hatten keine)
					if (heights != null && heights[dz * width + dx] != MapRegion.NO_HEIGHT && height(x, z) == MapRegion.NO_HEIGHT) {
						setHeight(x, z, heights[dz * width + dx]);
						changed = true;
					}
					if (biome != Biomes.UNKNOWN && biome(x, z) == null) {
						setBiome(x, z, biome);
					}
					continue;
				}
				if (biome != Biomes.UNKNOWN) {
					setBiome(x, z, biome);
				}
				if (heights != null && heights[dz * width + dx] != MapRegion.NO_HEIGHT) {
					set(x, z, color, heights[dz * width + dx]);
				} else {
					set(x, z, color);
				}
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
		lod.invalidate(rx, rz);
	}

	/** Region wieder aus dem Speicher werfen, wenn sie nur kurz zum Lesen geladen war. */
	void unloadIfClean(int rx, int rz) {
		long key = key(rx, rz);
		MapRegion region = regions.get(key);
		if (region != null && !region.unsaved && !region.hasTexture()) {
			regions.remove(key);
			if (last == region) {
				last = null;
			}
		}
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

	/** Haelt die Zahl der GPU-Texturen klein; was in diesem Bild gezeichnet wird, bleibt immer. */
	public void trimTextures(long frame) {
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
			if (withTexture.get(i).lastUsed >= frame - 2) {
				break;
			}
			withTexture.get(i).releaseTexture();
		}
	}

	private Path file(int rx, int rz) {
		return dir.resolve("r." + rx + "." + rz + ".lmap");
	}

	private MapRegion load(int rx, int rz) {
		MapRegion region = new MapRegion(rx, rz);
		return read(file(rx, rz), region.colors, region.heights, region.biomes) ? region : null;
	}

	/** Farben einer Regionsdatei, oder null. Darf auch aus Hintergrund-Threads aufgerufen werden. */
	static int[] readColors(Path file) {
		int[] colors = new int[MapRegion.SIZE * MapRegion.SIZE];
		return read(file, colors, null, null) ? colors : null;
	}

	/** Liest Farben, (ab Version 2) Hoehen und (ab Version 3) Biome; heights und biomes duerfen null sein. */
	private static boolean read(Path file, int[] colors, short[] heights, short[] biomes) {
		if (!Files.exists(file)) {
			return false;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
			int version;
			if (in.readInt() != MAGIC || (version = in.readInt()) < 1 || version > VERSION) {
				return false;
			}
			for (int i = 0; i < colors.length; i++) {
				colors[i] = in.readInt();
			}
			if (version >= 2 && heights != null) {
				for (int i = 0; i < heights.length; i++) {
					heights[i] = in.readShort();
				}
				if (version >= 3 && biomes != null) {
					// eigene Namensliste pro Datei, damit die Nummern nicht von der Reihenfolge im Spiel abhaengen
					short[] palette = new short[in.readUnsignedShort() + 1];
					for (int i = 1; i < palette.length; i++) {
						palette[i] = Biomes.id(in.readUTF());
					}
					for (int i = 0; i < biomes.length; i++) {
						int local = in.readUnsignedShort();
						biomes[i] = local < palette.length ? palette[local] : Biomes.UNKNOWN;
					}
				}
			}
			return true;
		} catch (IOException e) {
			LunaMap.LOGGER.warn("Kartenregion {} ist beschaedigt, wird neu erkundet", file, e);
			return false;
		}
	}

	Path regionFile(int rx, int rz) {
		return file(rx, rz);
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
				for (short h : region.heights) {
					out.writeShort(h);
				}
				Map<Short, Integer> local = new HashMap<>();
				List<String> names = new ArrayList<>();
				int[] indices = new int[region.biomes.length];
				for (int i = 0; i < indices.length; i++) {
					String name = Biomes.key(region.biomes[i]);
					if (name != null) {
						indices[i] = local.computeIfAbsent(region.biomes[i], id -> {
							names.add(name);
							return names.size();
						});
					}
				}
				out.writeShort(names.size());
				for (String name : names) {
					out.writeUTF(name);
				}
				for (int index : indices) {
					out.writeShort(index);
				}
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			region.unsaved = false;
			lod.invalidate(region.x, region.z);
		} catch (IOException e) {
			LunaMap.LOGGER.warn("Konnte Kartenregion {} nicht speichern", file, e);
		}
	}

	void close() {
		save();
		lod.close();
		for (MapRegion region : regions.values()) {
			region.releaseTexture();
		}
		regions.clear();
		last = null;
	}
}
