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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.mojang.blaze3d.platform.NativeImage;

import luna.map.LunaMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Verkleinerte Kacheln fuer die herausgezoomte Weltkarte, damit nicht hunderte Regionen geladen werden.
 * Stufe 1: 4x4 Regionen (1024 Bloecke) in einer 256er-Textur, Stufe 2: 4x4 Kacheln der Stufe 1 (4096 Bloecke).
 * Fertige Kacheln liegen unter lod/<stufe>/ auf der Platte und werden beim Speichern einer Region verworfen.
 */
public final class LodCache {
	public static final int TEX = 256;
	private static final int MAGIC = 0x4C4F4421; // "LOD!"
	private static final int MAX_TEXTURES = 96;
	private static final int MAX_PENDING = 8;
	private static final java.util.concurrent.atomic.AtomicInteger PENDING = new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Luna Map LOD");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private final MapStore store;
	private final Path dir;
	private final String owner;
	private final Map<Long, Tile> tiles = new HashMap<>();

	private static final class Tile {
		final int level;
		final int x;
		final int z;
		DynamicTexture texture;
		Identifier id;
		boolean empty;
		boolean dirty = true;
		boolean pending;
		int generation;
		long lastUsed;

		Tile(int level, int x, int z) {
			this.level = level;
			this.x = x;
			this.z = z;
		}
	}

	LodCache(MapStore store, Path dir, String owner) {
		this.store = store;
		this.dir = dir;
		this.owner = owner;
	}

	/** Kantenlaenge einer Kachel in Bloecken. */
	public static int span(int level) {
		return MapRegion.SIZE << (2 * level);
	}

	private static long key(int level, int x, int z) {
		return ((long) level << 60) ^ (((long) x & 0x3FFFFFFFL) << 30) ^ (z & 0x3FFFFFFFL);
	}

	/**
	 * Textur der Kachel oder null. Fehlende Kacheln werden im Hintergrund berechnet; solange zeigt die
	 * Karte die alte Textur (oder nichts) und ruckelt nicht.
	 */
	public Identifier texture(int level, int tx, int tz, long frame) {
		Tile tile = tiles.computeIfAbsent(key(level, tx, tz), k -> new Tile(level, tx, tz));
		tile.lastUsed = frame;
		if (tile.dirty && !tile.pending && PENDING.get() < MAX_PENDING) {
			tile.pending = true;
			int generation = tile.generation;
			PENDING.incrementAndGet();
			WORKER.execute(() -> {
				try {
					int[] colors = colors(level, tx, tz);
					MapSession.runOnMainThread(() -> {
						tile.pending = false;
						if (tile.generation != generation || tiles.get(key(level, tx, tz)) != tile) {
							// waehrend der Berechnung wurde eine Region gespeichert: Ergebnis verwerfen
							try {
								Files.deleteIfExists(file(level, tx, tz));
							} catch (IOException e) {
								// egal, wird ueberschrieben
							}
							return;
						}
						upload(tile, colors);
					});
				} catch (RuntimeException e) {
					MapSession.runOnMainThread(() -> tile.pending = false);
					LunaMap.LOGGER.debug("LOD-Kachel fehlgeschlagen", e);
				} finally {
					PENDING.decrementAndGet();
				}
			});
		}
		return tile.empty ? null : tile.id;
	}

	/** Farben einer Kachel (Hintergrund-Thread): von der Platte oder aus den Regionsdateien berechnet. */
	private int[] colors(int level, int tx, int tz) {
		Path file = file(level, tx, tz);
		int[] cached = read(file);
		if (cached != null) {
			return cached;
		}
		int[] colors = new int[TEX * TEX];
		int step = 4; // jede Stufe fasst 4x4 Kinder zusammen
		int childTex = TEX / 4;
		for (int cz = 0; cz < 4; cz++) {
			for (int cx = 0; cx < 4; cx++) {
				int[] child;
				int childSize;
				if (level == 1) {
					child = MapStore.readColors(store.regionFile(tx * 4 + cx, tz * 4 + cz));
					childSize = MapRegion.SIZE;
				} else {
					child = colors(level - 1, tx * 4 + cx, tz * 4 + cz);
					childSize = TEX;
				}
				if (child == null) {
					continue;
				}
				for (int pz = 0; pz < childTex; pz++) {
					for (int px = 0; px < childTex; px++) {
						colors[(cz * childTex + pz) * TEX + cx * childTex + px] = sample(child, childSize, px * step, pz * step, step);
					}
				}
			}
		}
		write(file, colors);
		return colors;
	}

	/** Erste bekannte Farbe im step x step-Block (damit duenn erkundete Gebiete nicht verschwinden). */
	private static int sample(int[] src, int size, int x0, int z0, int step) {
		int center = src[(z0 + step / 2) * size + x0 + step / 2];
		if (center != 0) {
			return center;
		}
		for (int z = z0; z < z0 + step; z++) {
			for (int x = x0; x < x0 + step; x++) {
				int c = src[z * size + x];
				if (c != 0) {
					return c;
				}
			}
		}
		return 0;
	}

	private void upload(Tile tile, int[] colors) {
		tile.dirty = false;
		boolean any = false;
		for (int c : colors) {
			if (c != 0) {
				any = true;
				break;
			}
		}
		tile.empty = !any;
		if (!any) {
			release(tile);
			return;
		}
		if (tile.texture == null) {
			tile.id = LunaMap.id("lod/" + owner + "/" + tile.level + "_" + tile.x + "_" + tile.z);
			tile.texture = new DynamicTexture(() -> "Luna Map LOD " + tile.level, TEX, TEX, false);
			Minecraft.getInstance().getTextureManager().register(tile.id, tile.texture);
		}
		NativeImage image = tile.texture.getPixels();
		for (int z = 0; z < TEX; z++) {
			for (int x = 0; x < TEX; x++) {
				image.setPixel(x, z, colors[z * TEX + x]);
			}
		}
		tile.texture.upload();
	}

	/** Eine Region wurde gespeichert: betroffene Kacheln neu bauen lassen. */
	void invalidate(int rx, int rz) {
		markDirty(1, rx >> 2, rz >> 2);
		markDirty(2, rx >> 4, rz >> 4);
	}

	private void markDirty(int level, int tx, int tz) {
		try {
			Files.deleteIfExists(file(level, tx, tz));
		} catch (IOException e) {
			// wird beim naechsten Mal ueberschrieben
		}
		Tile tile = tiles.get(key(level, tx, tz));
		if (tile != null) {
			tile.dirty = true;
			tile.generation++;
		}
	}

	/** Lange nicht gezeigte Texturen freigeben. */
	public void trim(long frame) {
		List<Tile> withTexture = new ArrayList<>();
		for (Tile tile : tiles.values()) {
			if (tile.texture != null) {
				withTexture.add(tile);
			}
		}
		if (withTexture.size() <= MAX_TEXTURES) {
			return;
		}
		withTexture.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
		for (int i = 0; i < withTexture.size() - MAX_TEXTURES; i++) {
			Tile tile = withTexture.get(i);
			if (tile.lastUsed >= frame - 2) {
				break; // gerade sichtbar: nie mitten im Bild freigeben
			}
			release(tile);
			tile.dirty = true;
		}
	}

	private void release(Tile tile) {
		if (tile.texture != null) {
			Minecraft.getInstance().getTextureManager().release(tile.id);
			tile.texture = null;
			tile.id = null;
		}
	}

	void close() {
		for (Tile tile : tiles.values()) {
			release(tile);
		}
		tiles.clear();
	}

	private Path file(int level, int tx, int tz) {
		return dir.resolve(Integer.toString(level)).resolve(tx + "." + tz + ".lod");
	}

	private static int[] read(Path file) {
		if (!Files.exists(file)) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
			if (in.readInt() != MAGIC) {
				return null;
			}
			int[] colors = new int[TEX * TEX];
			for (int i = 0; i < colors.length; i++) {
				colors[i] = in.readInt();
			}
			return colors;
		} catch (IOException e) {
			return null;
		}
	}

	private static void write(Path file, int[] colors) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)), 1 << 16))) {
				out.writeInt(MAGIC);
				for (int c : colors) {
					out.writeInt(c);
				}
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LunaMap.LOGGER.debug("LOD-Kachel {} nicht gespeichert", file, e);
		}
	}
}
