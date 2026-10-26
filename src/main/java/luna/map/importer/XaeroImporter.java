package luna.map.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import luna.map.LunaColors;
import luna.map.LunaMap;
import luna.map.data.Biomes;
import luna.map.data.MapRegion;
import luna.map.data.MapSession;
import luna.map.data.MapStore;
import luna.map.data.Waypoint;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

/**
 * Uebernimmt Karten und Wegpunkte von Xaero's World Map / Minimap aus dem Ordner .minecraft/xaero.
 * Xaero muss dafuer nicht installiert sein. Vorhandene Luna-Daten werden nicht ueberschrieben.
 */
public final class XaeroImporter {
	private static final Pattern REGION_FILE = Pattern.compile("(-?\\d+)_(-?\\d+)\\.zip");
	private static final AtomicBoolean RUNNING = new AtomicBoolean();
	private static volatile String status = "";

	/** Eine Regionsdatei und die Dimension, in die sie gehoert (z. B. "minecraft:overworld"). */
	private record Job(Path file, String dimension) {
	}

	/** Ein Welt- oder Server-Ordner von Xaero. */
	public record Source(String name, Path worldMap, Path minimap, int regions, int waypoints) {
	}

	private XaeroImporter() {
	}

	public static boolean running() {
		return RUNNING.get();
	}

	public static String status() {
		return status;
	}

	private static Path xaeroRoot() {
		return FabricLoader.getInstance().getGameDir().resolve("xaero");
	}

	/** Alle Xaero-Welten, die Karten oder Wegpunkte haben, die zur aktuellen Welt passende zuerst. */
	public static List<Source> sources(String currentWorld) {
		Map<String, Source> found = new HashMap<>();
		Path worldMapRoot = xaeroRoot().resolve("world-map");
		Path minimapRoot = xaeroRoot().resolve("minimap");
		for (Path dir : children(worldMapRoot)) {
			String name = dir.getFileName().toString();
			found.put(name, new Source(name, dir, minimapRoot.resolve(name), countRegions(dir), 0));
		}
		for (Path dir : children(minimapRoot)) {
			String name = dir.getFileName().toString();
			if (name.equals("backup")) {
				continue;
			}
			Source old = found.get(name);
			int waypoints = countWaypoints(dir);
			found.put(name, new Source(name, old != null ? old.worldMap() : worldMapRoot.resolve(name), dir,
					old != null ? old.regions() : 0, waypoints));
		}
		List<Source> list = new ArrayList<>();
		for (Source source : found.values()) {
			if (source.regions() > 0 || source.waypoints() > 0) {
				list.add(source);
			}
		}
		String hint = currentWorld == null ? "" : matchKey(currentWorld);
		list.sort((a, b) -> {
			boolean am = !hint.isEmpty() && matchKey(a.name()).contains(hint);
			boolean bm = !hint.isEmpty() && matchKey(b.name()).contains(hint);
			if (am != bm) {
				return am ? -1 : 1;
			}
			return Integer.compare(b.regions(), a.regions());
		});
		return list;
	}

	/** Vergleichsschluessel: "mp_play.example.net:25565" und "Multiplayer_play.example.net_25565" passen zusammen. */
	private static String matchKey(String name) {
		String key = name.toLowerCase(Locale.ROOT);
		for (String prefix : new String[] {"multiplayer_", "realms_", "mp_", "sp_"}) {
			if (key.startsWith(prefix)) {
				key = key.substring(prefix.length());
			}
		}
		return key.replaceAll("[^a-z0-9.]", "_").replaceAll("_25565$", "");
	}

	public static boolean matches(Source source, String currentWorld) {
		return currentWorld != null && matchKey(source.name()).equals(matchKey(currentWorld));
	}

	/** Startet den Import in die aktuelle Welt. Laeuft im Hintergrund. */
	public static void start(Source source, Registry<Biome> biomes) {
		if (MapSession.world() == null || !RUNNING.compareAndSet(false, true)) {
			return;
		}
		String world = MapSession.world();
		Thread thread = new Thread(() -> {
			try {
				int waypoints = importWaypoints(source, world);
				int regions = importMaps(source, world, biomes);
				status = "done:" + regions + ":" + waypoints;
				LunaMap.LOGGER.info("Xaero-Import aus {} fertig: {} Regionen, {} Wegpunkte", source.name(), regions, waypoints);
			} catch (RuntimeException e) {
				status = "error:" + e.getMessage();
				LunaMap.LOGGER.warn("Xaero-Import fehlgeschlagen", e);
			} finally {
				RUNNING.set(false);
			}
		}, "Luna Map Xaero-Import");
		thread.setDaemon(true);
		status = "running:0:0";
		thread.start();
	}

	// --- Karten -------------------------------------------------------------------------------

	private static int importMaps(Source source, String world, Registry<Biome> biomes) {
		BlockColors colors = new BlockColors(biomes);
		List<Job> jobs = new ArrayList<>();
		for (Path dimDir : children(source.worldMap())) {
			String dimension = dimensionFromWorldMap(dimDir.getFileName().toString());
			if (dimension == null) {
				continue;
			}
			Path regions = bestRegionFolder(dimDir);
			if (regions == null) {
				continue;
			}
			for (Path file : children(regions)) {
				if (REGION_FILE.matcher(file.getFileName().toString()).matches()) {
					// Dimension als Text behalten: ":" ist unter Windows in Pfaden verboten
					jobs.add(new Job(file, dimension));
				}
			}
		}
		int done = 0;
		Map<String, MapStore> offline = new HashMap<>();
		for (Job job : jobs) {
			Path file = job.file();
			String dimension = job.dimension();
			Matcher m = REGION_FILE.matcher(file.getFileName().toString());
			if (!m.matches()) {
				continue;
			}
			int rx = Integer.parseInt(m.group(1));
			int rz = Integer.parseInt(m.group(2));
			try {
				XaeroRegionReader.Region region = XaeroRegionReader.read(file);
				int[] argb = toColors(region, colors, rx, rz);
				short[] heights = toHeights(region);
				short[] biomeIds = toBiomes(region);
				int x0 = rx * XaeroRegionReader.SIZE;
				int z0 = rz * XaeroRegionReader.SIZE;
				while (MapSession.pendingMainThreadTasks() > 32) {
					Thread.sleep(50);
				}
				MapSession.runOnMainThread(() -> merge(world, dimension, x0, z0, argb, heights, biomeIds, offline));
			} catch (IOException | RuntimeException e) {
				LunaMap.LOGGER.warn("Xaero-Region {} uebersprungen: {}", file, e.toString());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			done++;
			status = "running:" + done + ":" + jobs.size();
		}
		MapSession.runOnMainThread(() -> {
			for (MapStore store : offline.values()) {
				store.flush();
			}
		});
		return done;
	}

	/**
	 * Auf dem Haupt-Thread. Teile, die die offene Karte gerade im Speicher hat, gehen dorthin,
	 * alles andere direkt in die Dateien - so bleibt der Speicherbedarf auch bei riesigen Karten klein.
	 */
	private static void merge(String world, String dimension, int x0, int z0, int[] argb, short[] heights, short[] biomes,
			Map<String, MapStore> offline) {
		if (!world.equals(MapSession.world())) {
			return;
		}
		int size = XaeroRegionReader.SIZE;
		int part = MapRegion.SIZE;
		MapStore live = dimension.equals(MapSession.dimension()) ? MapSession.store() : null;
		MapStore files = offline.computeIfAbsent(dimension, d -> MapStore.open(MapSession.dimensionFolder(d), MapSession.textureOwner(d)));
		int[] piece = new int[part * part];
		short[] pieceHeights = new short[part * part];
		short[] pieceBiomes = new short[part * part];
		for (int pz = 0; pz < size / part; pz++) {
			for (int px = 0; px < size / part; px++) {
				for (int z = 0; z < part; z++) {
					System.arraycopy(argb, (pz * part + z) * size + px * part, piece, z * part, part);
					System.arraycopy(heights, (pz * part + z) * size + px * part, pieceHeights, z * part, part);
					System.arraycopy(biomes, (pz * part + z) * size + px * part, pieceBiomes, z * part, part);
				}
				int bx = x0 + px * part;
				int bz = z0 + pz * part;
				boolean inMemory = live != null && live.isLoaded(bx >> MapRegion.SHIFT, bz >> MapRegion.SHIFT);
				(inMemory ? live : files).merge(bx, bz, part, part, piece, pieceHeights, pieceBiomes, false, null);
				if (live != null && !inMemory) {
					live.forget(bx >> MapRegion.SHIFT, bz >> MapRegion.SHIFT);
				}
			}
		}
		files.flush();
	}

	private static int[] toColors(XaeroRegionReader.Region region, BlockColors colors, int rx, int rz) {
		int size = XaeroRegionReader.SIZE;
		int[] argb = new int[size * size];
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int i = z * size + x;
				String block = region.blocks[i];
				if (block == null) {
					continue;
				}
				int north = z > 0 && region.blocks[i - size] != null ? region.heights[i - size] : region.heights[i];
				argb[i] = colors.color(block, region.heights[i], region.tops[i], region.water[i], region.biomes[i],
						rx * size + x, rz * size + z, north);
			}
		}
		return argb;
	}

	/** Hoehe des obersten Blocks fuer die 3D-Ansicht; bei Wasser die Oberflaeche, nicht der Grund. */
	private static short[] toHeights(XaeroRegionReader.Region region) {
		short[] heights = new short[region.heights.length];
		for (int i = 0; i < heights.length; i++) {
			if (region.blocks[i] == null) {
				heights[i] = MapRegion.NO_HEIGHT;
			} else {
				heights[i] = region.water[i] && region.tops[i] > region.heights[i] ? region.tops[i] : region.heights[i];
			}
		}
		return heights;
	}

	/** Biom pro Block, wie Xaero es gespeichert hat ("minecraft:plains"), als Nummer aus Biomes. */
	private static short[] toBiomes(XaeroRegionReader.Region region) {
		short[] biomes = new short[region.biomes.length];
		for (int i = 0; i < biomes.length; i++) {
			biomes[i] = region.blocks[i] == null ? Biomes.UNKNOWN : Biomes.id(region.biomes[i]);
		}
		return biomes;
	}

	/** Xaero legt Karten in Unterordnern wie "mw$default" ab, im Einzelspieler auch direkt. */
	private static Path bestRegionFolder(Path dimDir) {
		Path best = null;
		int bestCount = 0;
		List<Path> candidates = new ArrayList<>(children(dimDir));
		candidates.add(dimDir);
		for (Path dir : candidates) {
			if (!Files.isDirectory(dir)) {
				continue;
			}
			int count = 0;
			for (Path file : children(dir)) {
				if (REGION_FILE.matcher(file.getFileName().toString()).matches()) {
					count++;
				}
			}
			if (count > bestCount) {
				best = dir;
				bestCount = count;
			}
		}
		return best;
	}

	private static int countRegions(Path worldDir) {
		int count = 0;
		for (Path dimDir : children(worldDir)) {
			Path best = bestRegionFolder(dimDir);
			if (best != null) {
				for (Path file : children(best)) {
					if (REGION_FILE.matcher(file.getFileName().toString()).matches()) {
						count++;
					}
				}
			}
		}
		return count;
	}

	static String dimensionFromWorldMap(String folder) {
		return switch (folder) {
			case "null" -> "minecraft:overworld";
			case "DIM-1" -> "minecraft:the_nether";
			case "DIM1" -> "minecraft:the_end";
			default -> folder.contains("$") ? folder.replace('$', ':') : null;
		};
	}

	// --- Wegpunkte ----------------------------------------------------------------------------

	static String dimensionFromMinimap(String folder) {
		if (!folder.startsWith("dim%")) {
			return null;
		}
		String id = folder.substring(4);
		return switch (id) {
			case "0" -> "minecraft:overworld";
			case "-1" -> "minecraft:the_nether";
			case "1" -> "minecraft:the_end";
			default -> id.contains("$") ? id.replace('$', ':') : null;
		};
	}

	private static int countWaypoints(Path minimapWorld) {
		int count = 0;
		for (Path dimDir : children(minimapWorld)) {
			if (dimensionFromMinimap(dimDir.getFileName().toString()) == null) {
				continue;
			}
			for (Path file : children(dimDir)) {
				if (file.getFileName().toString().endsWith(".txt")) {
					count += parseWaypoints(file, "").size();
				}
			}
		}
		return count;
	}

	private static int importWaypoints(Source source, String world) {
		List<Waypoint> all = new ArrayList<>();
		for (Path dimDir : children(source.minimap())) {
			String dimension = dimensionFromMinimap(dimDir.getFileName().toString());
			if (dimension == null) {
				continue;
			}
			for (Path file : children(dimDir)) {
				if (file.getFileName().toString().endsWith(".txt")) {
					all.addAll(parseWaypoints(file, dimension));
				}
			}
		}
		MapSession.runOnMainThread(() -> {
			if (world.equals(MapSession.world()) && MapSession.waypoints() != null) {
				MapSession.waypoints().addAllNew(all);
			}
		});
		return all.size();
	}

	/** Zeilen: waypoint:name:initials:x:y:z:color:disabled:type:set:... */
	static List<Waypoint> parseWaypoints(Path file, String dimension) {
		List<Waypoint> result = new ArrayList<>();
		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return result;
		}
		for (String line : lines) {
			if (!line.startsWith("waypoint:")) {
				continue;
			}
			String[] parts = line.split(":", -1);
			if (parts.length < 7) {
				continue;
			}
			try {
				String name = parts[1].replace("§§", ":");
				int x = Integer.parseInt(parts[3]);
				int y = parts[4].equals("~") ? 64 : Integer.parseInt(parts[4]);
				int z = Integer.parseInt(parts[5]);
				int color = xaeroColor(Integer.parseInt(parts[6]));
				String type = null;
				if (name.startsWith("gui.xaero_deathpoint_old")) {
					name = "Vorheriger Tod";
					type = Waypoint.OLD_DEATH;
				} else if (name.startsWith("gui.xaero_deathpoint")) {
					name = "Tod";
					type = Waypoint.DEATH;
				}
				Waypoint waypoint = new Waypoint(name, x, y, z, color, dimension);
				waypoint.type = type;
				result.add(waypoint);
			} catch (NumberFormatException e) {
				// kaputte Zeile ueberspringen
			}
		}
		return result;
	}

	/** Xaero nutzt die 16 Minecraft-Chatfarben als Index, neuere Versionen auch mehr. */
	private static final int[] CHAT_COLORS = {
			0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

	private static int xaeroColor(int index) {
		if (index >= 0 && index < CHAT_COLORS.length) {
			return 0xFF000000 | CHAT_COLORS[index];
		}
		return LunaColors.WAYPOINT[Math.floorMod(index, LunaColors.WAYPOINT.length)];
	}

	private static List<Path> children(Path dir) {
		List<Path> list = new ArrayList<>();
		if (dir == null || !Files.isDirectory(dir)) {
			return list;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path path : stream) {
				list.add(path);
			}
		} catch (IOException e) {
			// Ordner nicht lesbar
		}
		return list;
	}
}
