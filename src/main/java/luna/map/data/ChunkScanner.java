package luna.map.data;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Liest geladene Chunks aus und schreibt pro Blocksaeule eine Kartenfarbe. Oberflaeche ueber die
 * Heightmap, Hoehlen (und Dimensionen mit Decke) ab Spielerhoehe nach unten bis zum ersten Boden.
 */
public final class ChunkScanner {
	private static final int CHUNKS_PER_TICK = 12;
	private static final int CAVE_CHUNKS_PER_TICK = 12;
	private static final int REFRESH_RADIUS = 2;
	/** So tief wird unter Glas, Barrieren usw. weitergesucht. */
	private static final int SEE_THROUGH_DEPTH = 32;
	/** So weit sucht der Hoehlenmodus nach unten. */
	private static final int CAVE_DEPTH = 48;

	private final Set<Long> queue = new LinkedHashSet<>();
	private final Set<Long> caveQueue = new LinkedHashSet<>();
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private int refreshTimer;
	private int caveTimer;

	public void enqueue(int chunkX, int chunkZ) {
		queue.add(ChunkPos.pack(chunkX, chunkZ));
	}

	public void clear() {
		queue.clear();
		caveQueue.clear();
	}

	public void clearCaves() {
		caveQueue.clear();
		caveTimer = 0;
	}

	/**
	 * @param caves Hoehlenkarte, wenn der Spieler gerade unter der Erde ist, sonst null
	 * @param caveRadius Radius (Chunks), der im Hoehlenmodus laufend neu gelesen wird
	 */
	public void tick(ClientLevel level, LocalPlayer player, MapStore store, MapStore caves, int caveRadius) {
		int cx = player.getBlockX() >> 4;
		int cz = player.getBlockZ() >> 4;
		int caveTop = Math.min(player.getBlockY() + 2, level.getMaxY());
		// Chunks direkt um den Spieler regelmaessig neu lesen, damit Aenderungen auftauchen
		if (++refreshTimer >= 40) {
			refreshTimer = 0;
			for (int dz = -REFRESH_RADIUS; dz <= REFRESH_RADIUS; dz++) {
				for (int dx = -REFRESH_RADIUS; dx <= REFRESH_RADIUS; dx++) {
					enqueue(cx + dx, cz + dz);
				}
			}
		}
		process(queue, CHUNKS_PER_TICK, 2_000_000L, level, store, level.dimensionType().hasCeiling(), caveTop);

		if (caves != null) {
			if (caveTimer-- <= 0) {
				caveTimer = 40;
				for (int r = 0; r <= caveRadius; r++) {
					for (int dz = -r; dz <= r; dz++) {
						for (int dx = -r; dx <= r; dx++) {
							if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
								caveQueue.add(ChunkPos.pack(cx + dx, cz + dz));
							}
						}
					}
				}
			}
			process(caveQueue, CAVE_CHUNKS_PER_TICK, 1_000_000L, level, caves, true, caveTop);
		}
	}

	/** Liest Chunks, bis budget Stueck oder maxNanos Rechenzeit erreicht sind (mindestens einer), damit es nicht ruckelt. */
	private void process(Set<Long> pending, int budget, long maxNanos, ClientLevel level, MapStore store, boolean fromTop,
			int caveTop) {
		int done = 0;
		long start = System.nanoTime();
		Iterator<Long> it = pending.iterator();
		while (it.hasNext() && done < budget && (done == 0 || System.nanoTime() - start < maxNanos)) {
			long packed = it.next();
			it.remove();
			LevelChunk chunk = loadedChunk(level, ChunkPos.getX(packed), ChunkPos.getZ(packed));
			if (chunk == null) {
				continue;
			}
			scan(level, chunk, store, fromTop, caveTop);
			done++;
		}
	}

	/**
	 * Wirklich geladener Chunk oder null. ClientLevel.hasChunk() meldet in 26.3 immer true und
	 * getChunk() liefert fuer fehlende Chunks einen leeren Platzhalter, deshalb direkt den Cache fragen.
	 */
	public static LevelChunk loadedChunk(ClientLevel level, int chunkX, int chunkZ) {
		return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
	}

	private void scan(ClientLevel level, LevelChunk chunk, MapStore store, boolean cave, int caveTop) {
		ChunkPos chunkPos = chunk.getPos();
		int minX = chunkPos.getMinBlockX();
		int minZ = chunkPos.getMinBlockZ();
		LevelChunk north = loadedChunk(level, chunkPos.x(), chunkPos.z() - 1);
		boolean changed = false;

		for (int lx = 0; lx < 16; lx++) {
			int northY = north != null
					? surfaceY(level, north, minX + lx, minZ - 1, cave, caveTop)
					: Integer.MIN_VALUE;
			for (int lz = 0; lz < 16; lz++) {
				int x = minX + lx;
				int z = minZ + lz;
				int y = surfaceY(level, chunk, x, z, cave, caveTop);
				int color = y == Integer.MIN_VALUE ? 0 : color(level, chunk, x, y, z, northY == Integer.MIN_VALUE ? y : northY);
				if (store.color(x, z) != color || color != 0 && store.height(x, z) != y) {
					store.set(x, z, color, y);
					changed = true;
				}
				northY = y;
			}
		}
		if (changed) {
			store.version++;
		}
	}

	/** Hoehe des obersten sichtbaren Blocks; im Hoehlenmodus der Boden unter dem Spieler. */
	private int surfaceY(ClientLevel level, LevelChunk chunk, int x, int z, boolean cave, int caveTop) {
		int minY = level.getMinY();
		if (!cave) {
			// getHeight liefert in 26.3 schon den obersten Block (erster freier Platz minus 1)
			int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
			return y < minY ? Integer.MIN_VALUE : y;
		}
		int limit = Math.max(minY, caveTop - CAVE_DEPTH);
		int y = caveTop;
		// steckt der Startpunkt im Stein (Hoehlendecke), erst bis zur Luft hinunter
		while (y >= limit && !chunk.getBlockState(pos.set(x, y, z)).isAir()) {
			y--;
		}
		while (y >= limit && chunk.getBlockState(pos.set(x, y, z)).isAir()) {
			y--;
		}
		return y >= limit ? y : Integer.MIN_VALUE;
	}

	private int color(ClientLevel level, LevelChunk chunk, int x, int y, int z, int northY) {
		BlockState state = chunk.getBlockState(pos.set(x, y, z));
		int minY = level.getMinY();

		if (state.getFluidState().is(FluidTags.WATER)) {
			int depth = 0;
			while (depth < 24 && y - depth - 1 >= minY
					&& chunk.getBlockState(pos.set(x, y - depth - 1, z)).getFluidState().is(FluidTags.WATER)) {
				depth++;
			}
			int water = BiomeColors.getAverageWaterColor(level, pos.set(x, y, z));
			return shade(water, 1.05f - Math.min(depth, 12) * 0.045f);
		}

		MapColor mapColor = state.getMapColor(level, pos);
		// Glas, Barrieren & Co. haben keine Kartenfarbe: darunter weitersuchen
		int tries = 0;
		while (mapColor == MapColor.NONE && y > minY && tries++ < SEE_THROUGH_DEPTH) {
			y--;
			state = chunk.getBlockState(pos.set(x, y, z));
			mapColor = state.getMapColor(level, pos);
		}
		if (mapColor == MapColor.NONE) {
			return 0;
		}
		int rgb;
		if (mapColor == MapColor.GRASS) {
			rgb = BiomeColors.getAverageGrassColor(level, pos);
		} else if (mapColor == MapColor.PLANT) {
			rgb = shade(BiomeColors.getAverageFoliageColor(level, pos), 0.85f);
		} else if (mapColor == MapColor.WATER) {
			rgb = BiomeColors.getAverageWaterColor(level, pos);
		} else {
			rgb = mapColor.col;
		}
		float light = y > northY ? 1.12f : y < northY ? 0.84f : 1f;
		return shade(rgb, light);
	}

	private static int shade(int rgb, float factor) {
		int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * factor));
		int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * factor));
		int b = Math.min(255, Math.round((rgb & 0xFF) * factor));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}
}
