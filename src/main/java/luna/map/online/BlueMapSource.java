package luna.map.online;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.blaze3d.platform.NativeImage;

import luna.map.LunaMap;
import luna.map.data.ChunkScanner;
import luna.map.data.MapSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Laedt Lowres-Kacheln einer BlueMap und uebernimmt sie in die Karte. Bloecke in geladenen
 * Chunks bleiben aus dem eigenen Scan, alles andere kommt aktuell vom Server.
 */
public final class BlueMapSource {
	/** So oft wird nachgesehen, ob BlueMap die Kachel neu gerendert hat. */
	private static final long REFRESH_MS = 2 * 60 * 1000L;
	private static final long RETRY_MS = 2 * 60 * 1000L;
	private static final int MAX_PENDING = 6;

	static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "Luna Map BlueMap");
		thread.setDaemon(true);
		return thread;
	});
	private static final AtomicInteger PENDING = new AtomicInteger();
	private static final AtomicInteger LOADED = new AtomicInteger();
	private static final AtomicInteger FAILED = new AtomicInteger();
	private static final Map<Long, Long> FETCHED = new HashMap<>();
	/** Pro Kachel: Inhalt-Pruefsumme und ETag/Last-Modified der zuletzt uebernommenen Version. */
	private static final Map<Long, Version> VERSIONS = new ConcurrentHashMap<>();

	private record Version(int hash, String etag, String lastModified) {
	}

	private static String linkWorld;
	private static String fetchedDimension;
	private static BlueMapLink link;
	private static volatile String lastError;

	private BlueMapSource() {
	}

	/** Aktueller Link der Welt (neu geladen, wenn die Welt wechselt). */
	public static BlueMapLink link() {
		String world = MapSession.world();
		if (world == null) {
			return null;
		}
		if (!world.equals(linkWorld)) {
			linkWorld = world;
			link = BlueMapLink.load(MapSession.worldFolder());
			clearFetched();
			lastError = null;
		}
		return link;
	}

	private static void clearFetched() {
		FETCHED.clear();
		VERSIONS.clear();
	}

	/** Zuordnung wurde automatisch geaendert: speichern (geholte Kacheln gelten pro Dimension+Karte). */
	static void saveLink() {
		if (link != null && MapSession.world() != null) {
			link.save(MapSession.worldFolder());
		}
	}

	public static void setLink(BlueMapLink newLink) {
		link();
		link = newLink;
		BlueMapOverview.releaseAll();
		BlueMapHires.releaseAll();
		clearFetched();
		lastError = null;
		if (newLink == null) {
			BlueMapLink.delete(MapSession.worldFolder());
		} else {
			newLink.save(MapSession.worldFolder());
		}
	}

	public static String lastError() {
		return lastError;
	}

	public static int loadedTiles() {
		return LOADED.get();
	}

	public static int failedTiles() {
		return FAILED.get();
	}

	/** Holt fehlende oder alte Kacheln fuer einen Bereich (Bloecke). Nur auf dem Haupt-Thread aufrufen. */
	public static void request(int minX, int minZ, int maxX, int maxZ) {
		BlueMapLink current = link();
		String dimension = MapSession.dimension();
		if (current == null || dimension == null) {
			return;
		}
		String mapId = current.maps.get(dimension);
		if (mapId == null) {
			return;
		}
		// geladene Kacheln gelten nur fuer die Dimension, fuer die sie geholt wurden
		String fetchKey = dimension + "|" + mapId;
		if (!fetchKey.equals(fetchedDimension)) {
			fetchedDimension = fetchKey;
			clearFetched();
		}
		int tileSize = current.tileSizes.getOrDefault(mapId, 500);
		long now = System.currentTimeMillis();
		int tx0 = Math.floorDiv(minX, tileSize);
		int tx1 = Math.floorDiv(maxX, tileSize);
		int tz0 = Math.floorDiv(minZ, tileSize);
		int tz1 = Math.floorDiv(maxZ, tileSize);
		for (int tz = tz0; tz <= tz1; tz++) {
			for (int tx = tx0; tx <= tx1; tx++) {
				long key = ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
				Long last = FETCHED.get(key);
				if (last != null && now - Math.abs(last) < (last < 0 ? RETRY_MS : REFRESH_MS)) {
					continue;
				}
				if (PENDING.get() >= MAX_PENDING) {
					return;
				}
				FETCHED.put(key, now);
				fetch(current.url, mapId, dimension, MapSession.world(), tx, tz, tileSize, key);
			}
		}
	}

	private static void fetch(String baseUrl, String mapId, String dimension, String world, int tx, int tz, int tileSize, long key) {
		PENDING.incrementAndGet();
		EXECUTOR.execute(() -> {
			try {
				String url = baseUrl + "maps/" + mapId + "/tiles/1/" + BlueMapClient.tilePath(tx, tz) + ".png";
				Version old = VERSIONS.get(key);
				BlueMapClient.Fetched fetched = BlueMapClient.getIfChanged(url, old == null ? null : old.etag(),
						old == null ? null : old.lastModified());
				byte[] png = fetched.body();
				if (fetched.unchanged() || png == null) {
					return;
				}
				int hash = java.util.Arrays.hashCode(png);
				if (old != null && old.hash() == hash) {
					// BlueMap hat seit dem letzten Mal nichts neu gerendert
					return;
				}
				VERSIONS.put(key, new Version(hash, fetched.etag(), fetched.lastModified()));
				int size = tileSize + 1;
				int[] colors = new int[size * size];
				try (NativeImage image = NativeImage.read(png)) {
					if (image.getWidth() < size || image.getHeight() < size) {
						throw new java.io.IOException("unerwartete Kachelgroesse " + image.getWidth() + "x" + image.getHeight());
					}
					for (int z = 0; z < size; z++) {
						for (int x = 0; x < size; x++) {
							int argb = image.getPixel(x, z);
							colors[z * size + x] = (argb >>> 24) == 0 ? 0 : argb | 0xFF000000;
						}
					}
				}
				MapSession.runOnMainThread(() -> apply(world, dimension, tx * tileSize, tz * tileSize, size, colors));
				if (LOADED.incrementAndGet() == 1) {
					LunaMap.LOGGER.info("BlueMap verbunden: erste Kachel von {} geladen", baseUrl);
				}
				lastError = null;
			} catch (Exception e) {
				lastError = String.valueOf(e.getMessage());
				VERSIONS.remove(key);
				MapSession.runOnMainThread(() -> FETCHED.put(key, -System.currentTimeMillis()));
				if (FAILED.incrementAndGet() <= 3) {
					LunaMap.LOGGER.warn("BlueMap-Kachel {},{} konnte nicht geladen werden: {}", tx, tz, e.toString());
				}
			} finally {
				PENDING.decrementAndGet();
			}
		});
	}

	private static void apply(String world, String dimension, int x0, int z0, int size, int[] colors) {
		if (!world.equals(MapSession.world()) || !dimension.equals(MapSession.dimension()) || MapSession.store() == null) {
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		MapSession.store().merge(x0, z0, size, size, colors, true,
				(x, z) -> level != null && ChunkScanner.loadedChunk(level, x >> 4, z >> 4) != null);
	}
}
