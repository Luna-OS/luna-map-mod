package luna.map.data;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;

/** Haelt Karte und Wegpunkte der Welt und Dimension, in der man gerade ist. */
public final class MapSession {
	private static final int SAVE_INTERVAL = 20 * 60;

	private static String world;
	private static String dimension;
	private static MapStore store;
	/** Hoehlenkarte des aktuellen 16er-Hoehenbands, solange der Spieler unter der Erde ist. */
	private static MapStore caveStore;
	private static int caveBand = Integer.MIN_VALUE;
	private static boolean underground;
	private static WaypointStore waypoints;
	private static final ChunkScanner SCANNER = new ChunkScanner();
	private static int saveTimer;
	private static final Queue<Runnable> MAIN_THREAD = new ConcurrentLinkedQueue<>();

	private MapSession() {
	}

	/** Karte der aktuellen Dimension, oder null ausserhalb einer Welt. */
	public static MapStore store() {
		return store;
	}

	/** Was Minimap und Weltkarte zeigen: unter der Erde die Hoehlenkarte, sonst die Oberflaeche. */
	public static MapStore viewStore() {
		return underground && caveStore != null ? caveStore : store;
	}

	public static boolean underground() {
		return underground && caveStore != null;
	}

	public static WaypointStore waypoints() {
		return waypoints;
	}

	public static String dimension() {
		return dimension;
	}

	/** Kennung der aktuellen Welt bzw. des Servers, oder null. */
	public static String world() {
		return world;
	}

	/** Ordner der aktuellen Welt unter lunamap/, oder null. */
	public static Path worldFolder() {
		return world == null ? null : root();
	}

	/** Ordner fuer eine Dimension der aktuellen Welt. */
	public static Path dimensionFolder(String dimensionId) {
		return root().resolve(sanitize(dimensionId));
	}

	public static String textureOwner(String dimensionId) {
		return sanitize(world) + "/" + sanitize(dimensionId);
	}

	/** Fuehrt Arbeit aus Hintergrund-Threads im naechsten Tick auf dem Haupt-Thread aus. */
	public static void runOnMainThread(Runnable task) {
		MAIN_THREAD.add(task);
	}

	/** Wie viele Aufgaben noch warten (Hintergrund-Importe bremsen sich damit selbst). */
	public static int pendingMainThreadTasks() {
		return MAIN_THREAD.size();
	}

	public static void onChunkLoaded(int chunkX, int chunkZ) {
		SCANNER.enqueue(chunkX, chunkZ);
	}

	public static void tick(Minecraft mc) {
		// Hoechstens ein paar Aufgaben pro Tick, damit grosse Importe nicht ruckeln
		Runnable task;
		for (int i = 0; i < 4 && (task = MAIN_THREAD.poll()) != null; i++) {
			task.run();
		}
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			leave();
			return;
		}
		String newWorld = worldId(mc);
		String newDimension = level.dimension().identifier().toString();
		if (!newWorld.equals(world)) {
			leave();
			world = newWorld;
			waypoints = new WaypointStore(root().resolve("waypoints.json"));
		}
		if (!newDimension.equals(dimension) || store == null) {
			if (store != null) {
				store.close();
			}
			closeCaves();
			dimension = newDimension;
			store = new MapStore(dimensionFolder(newDimension), textureOwner(newDimension));
			// Chunks, die schon vor dem Start der Karte geladen wurden, auch einlesen
			SCANNER.clear();
			int radius = mc.options.renderDistance().get() + 1;
			int cx = mc.player.getBlockX() >> 4;
			int cz = mc.player.getBlockZ() >> 4;
			for (int r = 0; r <= radius; r++) {
				for (int dz = -r; dz <= r; dz++) {
					for (int dx = -r; dx <= r; dx++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
							SCANNER.enqueue(cx + dx, cz + dz);
						}
					}
				}
			}
		}
		updateUnderground(level, mc);
		int caveRadius = luna.map.LunaConfig.get().zoom.radius / 16 + 1;
		SCANNER.tick(level, mc.player, store, underground ? caveStore : null, caveRadius);
		store.tick(20 * 60);
		if (caveStore != null) {
			caveStore.tick(20 * 60);
		}
		if (++saveTimer >= SAVE_INTERVAL) {
			saveTimer = 0;
			store.save();
			if (caveStore != null) {
				caveStore.save();
			}
		}
	}

	/** Unter der Erde (kein Himmel ueber dem Kopf) wechselt die Karte in den Hoehlenmodus. */
	private static void updateUnderground(ClientLevel level, Minecraft mc) {
		if (level.dimensionType().hasCeiling() || !luna.map.LunaConfig.get().caveMode) {
			underground = false;
			return;
		}
		int px = mc.player.getBlockX();
		int py = mc.player.getBlockY();
		int pz = mc.player.getBlockZ();
		net.minecraft.world.level.chunk.LevelChunk chunk = ChunkScanner.loadedChunk(level, px >> 4, pz >> 4);
		if (chunk == null) {
			return;
		}
		int surface = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px & 15, pz & 15);
		// etwas Abstand beim Wechsel, damit es an Hoehleneingaengen nicht flackert
		boolean now = underground ? py < surface - 2 : py < surface - 5;
		if (now) {
			int band = Math.floorDiv(py, 16);
			if (band != caveBand || caveStore == null) {
				closeCaves();
				caveBand = band;
				String folder = "caves/" + band;
				caveStore = new MapStore(dimensionFolder(dimension).resolve(folder), textureOwner(dimension) + "/caves_" + (band < 0 ? "m" + -band : band));
				SCANNER.clearCaves();
			}
		}
		underground = now;
	}

	private static void closeCaves() {
		if (caveStore != null) {
			caveStore.close();
			caveStore = null;
		}
		caveBand = Integer.MIN_VALUE;
		underground = false;
	}

	public static void leave() {
		if (store != null) {
			store.close();
			store = null;
		}
		closeCaves();
		if (waypoints != null) {
			waypoints.save();
			waypoints = null;
		}
		world = null;
		dimension = null;
		SCANNER.clear();
	}

	private static Path root() {
		return FabricLoader.getInstance().getGameDir().resolve("lunamap").resolve(sanitize(world));
	}

	private static String worldId(Minecraft mc) {
		if (mc.getSingleplayerServer() != null) {
			Path path = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			return "sp_" + path.getFileName();
		}
		ServerData server = mc.getCurrentServer();
		return server != null ? "mp_" + server.ip : "unknown";
	}

	private static String sanitize(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
	}
}
