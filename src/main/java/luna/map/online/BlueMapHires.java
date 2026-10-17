package luna.map.online;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleUnaryOperator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;

import luna.map.LunaMap;
import luna.map.data.MapSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Detailansicht beim Reinzoomen: BlueMaps Hires-Kacheln (3D-Modelle, 32x32 Bloecke) werden von oben mit echten
 * Block-Texturen gezeichnet (16 Pixel pro Block), so sieht man nah dran Wege, Haeuser und Felder statt
 * einfarbiger Quadrate.
 */
public final class BlueMapHires {
	/** Ab so vielen GUI-Pixeln pro Block lohnt sich die Detailansicht. */
	public static final double MIN_PIXELS_PER_BLOCK = 3;
	private static final long REFRESH_MS = 2 * 60 * 1000L;
	private static final long RETRY_MS = 60 * 1000L;
	/** Texturen, die so lange nicht gezeichnet wurden, duerfen freigegeben werden. */
	private static final long KEEP_MS = 1500;
	private static final int MAX_TEXTURES = 96;
	private static final int MAX_PENDING = 4;

	// ein Thread reicht und laesst dem Spiel mehr Luft
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(1, r -> {
		Thread thread = new Thread(r, "Luna Map BlueMap-Details");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private static final AtomicInteger PENDING = new AtomicInteger();
	private static final Map<String, Tile> TILES = new HashMap<>();
	private static String owner;

	/** Block-Texturen der Karte (textures.json), einmal pro Karte geladen. */
	private static volatile List<HiresRenderer.Texture> textures;
	private static volatile String texturesFor;
	private static volatile boolean texturesLoading;
	private static long texturesFailed;

	private static final class Tile {
		final String key;
		DynamicTexture texture;
		Identifier id;
		long requested;
		boolean failed;
		long lastDrawn;
		volatile int hash;
		volatile String etag;
		volatile String lastModified;

		Tile(String key) {
			this.key = key;
		}
	}

	private BlueMapHires() {
	}

	/**
	 * Zeichnet die Detailkacheln fuer den Ausschnitt (Blockkoordinaten). screenX/Y rechnen Bloecke in GUI-Pixel um.
	 * Fehlende Kacheln werden im Hintergrund geholt, bis dahin bleibt sichtbar, was darunter liegt.
	 */
	public static void draw(GuiGraphicsExtractor graphics, double minX, double minZ, double maxX, double maxZ,
			DoubleUnaryOperator screenX, DoubleUnaryOperator screenY) {
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
		if (!newOwner.equals(texturesFor) || textures == null) {
			loadTextures(link.url, mapId, newOwner);
			return;
		}
		int[] grid = link.hires.getOrDefault(mapId, new int[] {32, 2, 2});
		int size = grid[0];
		int tx0 = Math.floorDiv((int) Math.floor(minX) - grid[1], size);
		int tx1 = Math.floorDiv((int) Math.floor(maxX) - grid[1], size);
		int tz0 = Math.floorDiv((int) Math.floor(minZ) - grid[2], size);
		int tz1 = Math.floorDiv((int) Math.floor(maxZ) - grid[2], size);
		long now = System.currentTimeMillis();
		double centerX = (minX + maxX) / 2;
		double centerZ = (minZ + maxZ) / 2;
		List<int[]> wanted = new ArrayList<>();
		for (int tz = tz0; tz <= tz1; tz++) {
			for (int tx = tx0; tx <= tx1; tx++) {
				Tile tile = TILES.computeIfAbsent(tx + "_" + tz, Tile::new);
				tile.lastDrawn = now;
				if (now - tile.requested > (tile.failed ? RETRY_MS : REFRESH_MS)) {
					wanted.add(new int[] {tx, tz});
				}
				if (tile.id != null) {
					int bx = tx * size + grid[1];
					int bz = tz * size + grid[2];
					int x0 = (int) Math.round(screenX.applyAsDouble(bx));
					int x1 = (int) Math.round(screenX.applyAsDouble(bx + size));
					int y0 = (int) Math.round(screenY.applyAsDouble(bz));
					int y1 = (int) Math.round(screenY.applyAsDouble(bz + size));
					int texSize = size * HiresRenderer.PX_PER_BLOCK;
					graphics.blit(RenderPipelines.GUI_TEXTURED, tile.id, x0, y0, 0, 0, x1 - x0, y1 - y0,
							texSize, texSize, texSize, texSize);
				}
			}
		}
		// die Mitte zuerst
		wanted.sort((a, b) -> Double.compare(
				Math.abs(a[0] * size - centerX) + Math.abs(a[1] * size - centerZ),
				Math.abs(b[0] * size - centerX) + Math.abs(b[1] * size - centerZ)));
		for (int[] t : wanted) {
			if (PENDING.get() >= MAX_PENDING) {
				break;
			}
			Tile tile = TILES.get(t[0] + "_" + t[1]);
			tile.requested = now;
			load(link.url, mapId, t[0], t[1], size, tile, newOwner);
		}
		trim(now);
	}

	private static void loadTextures(String baseUrl, String mapId, String forOwner) {
		if (texturesLoading || System.currentTimeMillis() - texturesFailed < RETRY_MS) {
			return;
		}
		texturesLoading = true;
		textures = null;
		EXECUTOR.execute(() -> {
			try {
				BlueMapClient.Fetched fetched = BlueMapClient.getIfChanged(baseUrl + "maps/" + mapId + "/textures.json", null, null);
				if (fetched.body() == null) {
					throw new java.io.IOException("keine textures.json");
				}
				JsonArray array = JsonParser.parseString(new String(fetched.body(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
				List<HiresRenderer.Texture> list = new ArrayList<>(array.size());
				for (JsonElement element : array) {
					list.add(decode(element.getAsJsonObject()));
				}
				textures = list;
				texturesFor = forOwner;
				LunaMap.LOGGER.info("BlueMap-Details: {} Block-Texturen geladen", list.size());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				texturesFailed = System.currentTimeMillis();
				LunaMap.LOGGER.warn("BlueMap-Texturen konnten nicht geladen werden: {}", e.toString());
			} finally {
				texturesLoading = false;
			}
		});
	}

	private static HiresRenderer.Texture decode(JsonObject object) {
		boolean translucent = object.has("halfTransparent") && object.get("halfTransparent").getAsBoolean();
		try {
			String uri = object.get("texture").getAsString();
			byte[] png = Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1));
			try (NativeImage image = NativeImage.read(png)) {
				int w = image.getWidth();
				int h = Math.min(image.getHeight(), w); // Animationen: nur das erste Bild
				int[] argb = new int[w * h];
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						argb[y * w + x] = image.getPixel(x, y);
					}
				}
				return new HiresRenderer.Texture(argb, w, h, translucent);
			}
		} catch (Exception e) {
			// kaputte Textur: Durchschnittsfarbe aus der JSON nehmen
			int argb = 0xFFFF00FF;
			if (object.has("color")) {
				JsonArray c = object.getAsJsonArray("color");
				argb = (int) (c.get(3).getAsFloat() * 255) << 24 | (int) (c.get(0).getAsFloat() * 255) << 16
						| (int) (c.get(1).getAsFloat() * 255) << 8 | (int) (c.get(2).getAsFloat() * 255);
			}
			return new HiresRenderer.Texture(new int[] {argb}, 1, 1, translucent);
		}
	}

	private static void load(String baseUrl, String mapId, int tx, int tz, int size, Tile tile, String forOwner) {
		List<HiresRenderer.Texture> tex = textures;
		PENDING.incrementAndGet();
		EXECUTOR.execute(() -> {
			try {
				String url = baseUrl + "maps/" + mapId + "/tiles/0/" + BlueMapClient.tilePath(tx, tz) + ".prbm";
				BlueMapClient.Fetched fetched = BlueMapClient.getIfChanged(url, tile.etag, tile.lastModified);
				if (fetched.body() == null && !fetched.unchanged()) {
					// manche Webserver liefern nur die gepackte Datei aus
					fetched = BlueMapClient.getIfChanged(url + ".gz", null, null);
				}
				tile.etag = fetched.etag();
				tile.lastModified = fetched.lastModified();
				byte[] data = fetched.body();
				if (fetched.unchanged() || data == null) {
					MapSession.runOnMainThread(() -> tile.failed = false);
					return;
				}
				int hash = java.util.Arrays.hashCode(data);
				if (hash == tile.hash && tile.texture != null) {
					return; // BlueMap hat hier nichts neu gerendert
				}
				int[] pixels = HiresRenderer.render(data, size, tex);
				int texSize = size * HiresRenderer.PX_PER_BLOCK;
				NativeImage image = new NativeImage(texSize, texSize, false);
				for (int y = 0; y < texSize; y++) {
					for (int x = 0; x < texSize; x++) {
						image.setPixel(x, y, pixels[y * texSize + x]);
					}
				}
				MapSession.runOnMainThread(() -> {
					if (!forOwner.equals(owner) || TILES.get(tile.key) != tile) {
						image.close();
						return;
					}
					// passiert im Tick, nicht waehrend gezeichnet wird
					release(tile);
					tile.id = LunaMap.id("bluemap_hires/" + Integer.toHexString(forOwner.hashCode()) + "/" + tile.key.replace('-', 'm'));
					tile.texture = new DynamicTexture(() -> "Luna Map BlueMap-Details " + tile.key, image);
					Minecraft.getInstance().getTextureManager().register(tile.id, tile.texture);
					tile.hash = hash;
					tile.failed = false;
				});
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				MapSession.runOnMainThread(() -> tile.failed = true);
				LunaMap.LOGGER.debug("BlueMap-Details {} fehlgeschlagen", tile.key, e);
			} finally {
				PENDING.decrementAndGet();
			}
		});
	}

	private static void trim(long now) {
		// lange nicht gesehene Kacheln ohne Textur vergessen
		TILES.values().removeIf(tile -> tile.texture == null && now - tile.lastDrawn > 5 * 60 * 1000L);
		List<Tile> withTexture = new ArrayList<>();
		for (Tile tile : TILES.values()) {
			if (tile.texture != null && now - tile.lastDrawn > KEEP_MS) {
				withTexture.add(tile);
			}
		}
		int total = 0;
		for (Tile tile : TILES.values()) {
			if (tile.texture != null) {
				total++;
			}
		}
		if (total <= MAX_TEXTURES) {
			return;
		}
		withTexture.sort((a, b) -> Long.compare(a.lastDrawn, b.lastDrawn));
		for (int i = 0; i < withTexture.size() && total > MAX_TEXTURES; i++, total--) {
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
