package luna.map.online;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.blaze3d.platform.NativeImage;

import luna.map.LunaMap;
import luna.map.data.MapSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Uebersicht der ganzen BlueMap fuer die herausgezoomte Weltkarte. Nutzt die groeberen Lowres-Stufen
 * (Stufe 2 = 5, Stufe 3 = 25 Bloecke pro Pixel), zeichnet die Kacheln direkt als Textur und legt eine
 * Kopie unter lunamap/<server>/bluemap/ ab, damit die Karte auch offline da ist.
 */
public final class BlueMapOverview {
	private static final long REFRESH_MS = 30 * 60 * 1000L;
	private static final long RETRY_MS = 2 * 60 * 1000L;
	private static final int MAX_TEXTURES = 48;
	private static final int MAX_PENDING = 4;

	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "Luna Map BlueMap-Uebersicht");
		thread.setDaemon(true);
		return thread;
	});
	private static final AtomicInteger PENDING = new AtomicInteger();
	private static final Map<String, Tile> TILES = new HashMap<>();
	private static String owner;

	private static final class Tile {
		final String key;
		DynamicTexture texture;
		Identifier id;
		long requested;
		boolean failed;
		long lastDrawn;

		Tile(String key) {
			this.key = key;
		}
	}

	private BlueMapOverview() {
	}

	/** Gibt es fuer die aktuelle Dimension eine BlueMap? */
	public static boolean available() {
		BlueMapLink link = BlueMapSource.link();
		return link != null && MapSession.dimension() != null && link.maps.containsKey(MapSession.dimension());
	}

	/** Welche Stufe passt zu "Bloecke pro Bildschirmpixel"? 1 = blockgenau (das macht BlueMapSource). */
	public static int lodFor(double blocksPerPixel) {
		if (blocksPerPixel >= 12) {
			return 3;
		}
		if (blocksPerPixel >= 2.5) {
			return 2;
		}
		return 1;
	}

	/** Zeichnet die Uebersicht fuer den Ausschnitt. screenX/Y rechnen Blockkoordinaten in Bildschirmpixel um. */
	public static void draw(GuiGraphicsExtractor graphics, double minX, double minZ, double maxX, double maxZ,
			double blocksPerPixel, java.util.function.DoubleUnaryOperator screenX, java.util.function.DoubleUnaryOperator screenY) {
		BlueMapLink link = BlueMapSource.link();
		String dimension = MapSession.dimension();
		if (link == null || dimension == null) {
			return;
		}
		String mapId = link.maps.get(dimension);
		if (mapId == null) {
			return;
		}
		String newOwner = MapSession.world() + "|" + mapId;
		if (!newOwner.equals(owner)) {
			releaseAll();
			owner = newOwner;
		}
		int lod = Math.max(2, lodFor(blocksPerPixel));
		int tileSize = link.tileSizes.getOrDefault(mapId, 500);
		int span = tileSize * (lod == 2 ? 5 : 25);
		long now = System.currentTimeMillis();
		for (int tz = Math.floorDiv((int) minZ, span); tz <= Math.floorDiv((int) maxZ, span); tz++) {
			for (int tx = Math.floorDiv((int) minX, span); tx <= Math.floorDiv((int) maxX, span); tx++) {
				String key = lod + "/" + tx + "_" + tz;
				Tile tile = TILES.computeIfAbsent(key, Tile::new);
				tile.lastDrawn = now;
				if (tile.texture == null && now - tile.requested > (tile.failed ? RETRY_MS : REFRESH_MS)
						&& PENDING.get() < MAX_PENDING) {
					tile.requested = now;
					load(link.url, mapId, lod, tx, tz, tile, newOwner);
				}
				if (tile.id != null) {
					int x0 = (int) Math.round(screenX.applyAsDouble((double) tx * span));
					int x1 = (int) Math.round(screenX.applyAsDouble((double) (tx + 1) * span));
					int y0 = (int) Math.round(screenY.applyAsDouble((double) tz * span));
					int y1 = (int) Math.round(screenY.applyAsDouble((double) (tz + 1) * span));
					graphics.blit(RenderPipelines.GUI_TEXTURED, tile.id, x0, y0, 0, 0, x1 - x0, y1 - y0,
							tileSize, tileSize, tileSize + 1, tileSize + 1);
				}
			}
		}
		trim();
	}

	private static Path cacheFile(String mapId, int lod, int tx, int tz) {
		return MapSession.worldFolder().resolve("bluemap").resolve(sanitize(mapId)).resolve(lod + "")
				.resolve(tx + "_" + tz + ".png");
	}

	private static String sanitize(String name) {
		return name.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static void load(String baseUrl, String mapId, int lod, int tx, int tz, Tile tile, String forOwner) {
		Path cache = cacheFile(mapId, lod, tx, tz);
		PENDING.incrementAndGet();
		EXECUTOR.execute(() -> {
			byte[] png = null;
			try {
				boolean fresh = Files.exists(cache)
						&& System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < REFRESH_MS;
				if (!fresh) {
					try {
						png = BlueMapClient.getBytes(baseUrl + "maps/" + mapId + "/tiles/" + lod + "/"
								+ BlueMapClient.tilePath(tx, tz) + ".png");
						if (png != null) {
							Files.createDirectories(cache.getParent());
							Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp");
							Files.write(tmp, png);
							Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING);
						}
					} catch (IOException e) {
						// offline? dann die gespeicherte Kopie nehmen
						LunaMap.LOGGER.debug("BlueMap-Uebersicht {} nicht geladen: {}", tile.key, e.toString());
					}
				}
				if (png == null && Files.exists(cache)) {
					png = Files.readAllBytes(cache);
				}
				if (png == null) {
					// Kachel gibt es auf dem Server nicht (ausserhalb der Karte)
					MapSession.runOnMainThread(() -> tile.failed = false);
					return;
				}
				NativeImage image = NativeImage.read(png);
				int size = Math.min(image.getWidth(), image.getHeight() / 2);
				NativeImage top = new NativeImage(size, size, false);
				for (int z = 0; z < size; z++) {
					for (int x = 0; x < size; x++) {
						int argb = image.getPixel(x, z);
						top.setPixel(x, z, (argb >>> 24) == 0 ? 0 : argb | 0xFF000000);
					}
				}
				image.close();
				MapSession.runOnMainThread(() -> {
					if (!forOwner.equals(owner) || TILES.get(tile.key) != tile) {
						top.close();
						return;
					}
					tile.id = LunaMap.id("bluemap/" + Integer.toHexString(forOwner.hashCode()) + "/" + tile.key.replace('/', '_'));
					tile.texture = new DynamicTexture(() -> "Luna Map BlueMap " + tile.key, top);
					Minecraft.getInstance().getTextureManager().register(tile.id, tile.texture);
					tile.failed = false;
				});
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				MapSession.runOnMainThread(() -> tile.failed = true);
				LunaMap.LOGGER.debug("BlueMap-Uebersicht {} fehlgeschlagen", tile.key, e);
			} finally {
				PENDING.decrementAndGet();
			}
		});
	}

	private static void trim() {
		List<Tile> withTexture = new ArrayList<>();
		for (Tile tile : TILES.values()) {
			if (tile.texture != null) {
				withTexture.add(tile);
			}
		}
		if (withTexture.size() <= MAX_TEXTURES) {
			return;
		}
		withTexture.sort((a, b) -> Long.compare(a.lastDrawn, b.lastDrawn));
		for (int i = 0; i < withTexture.size() - MAX_TEXTURES; i++) {
			release(withTexture.get(i));
			TILES.remove(withTexture.get(i).key);
		}
	}

	private static void release(Tile tile) {
		if (tile.texture != null) {
			Minecraft.getInstance().getTextureManager().release(tile.id);
			tile.texture = null;
			tile.id = null;
		}
	}

	/** Beim Wechsel der Welt oder des Links alles freigeben. */
	public static void releaseAll() {
		for (Tile tile : TILES.values()) {
			release(tile);
		}
		TILES.clear();
		owner = null;
	}
}
