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

/** Liest geladene Chunks aus und schreibt pro Blocksaeule eine Kartenfarbe in den MapStore. */
public final class ChunkScanner {
	private static final int CHUNKS_PER_TICK = 12;
	private static final int REFRESH_RADIUS = 2;

	private final Set<Long> queue = new LinkedHashSet<>();
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private int refreshTimer;

	public void enqueue(int chunkX, int chunkZ) {
		queue.add(ChunkPos.pack(chunkX, chunkZ));
	}

	public void clear() {
		queue.clear();
	}

	public void tick(ClientLevel level, LocalPlayer player, MapStore store) {
		// Chunks direkt um den Spieler regelmaessig neu lesen, damit Aenderungen auftauchen
		if (++refreshTimer >= 20) {
			refreshTimer = 0;
			int cx = player.getBlockX() >> 4;
			int cz = player.getBlockZ() >> 4;
			for (int dz = -REFRESH_RADIUS; dz <= REFRESH_RADIUS; dz++) {
				for (int dx = -REFRESH_RADIUS; dx <= REFRESH_RADIUS; dx++) {
					enqueue(cx + dx, cz + dz);
				}
			}
		}
		int done = 0;
		Iterator<Long> it = queue.iterator();
		while (it.hasNext() && done < CHUNKS_PER_TICK) {
			long packed = it.next();
			it.remove();
			int cx = ChunkPos.getX(packed);
			int cz = ChunkPos.getZ(packed);
			LevelChunk chunk = loadedChunk(level, cx, cz);
			if (chunk == null) {
				continue;
			}
			scan(level, chunk, player, store);
			done++;
		}
	}

	private void scan(ClientLevel level, LevelChunk chunk, LocalPlayer player, MapStore store) {
		ChunkPos chunkPos = chunk.getPos();
		int minX = chunkPos.getMinBlockX();
		int minZ = chunkPos.getMinBlockZ();
		boolean ceiling = level.dimensionType().hasCeiling();
		int caveTop = Math.min(player.getBlockY() + 1, level.getMaxY());
		LevelChunk north = loadedChunk(level, chunkPos.x(), chunkPos.z() - 1);
		boolean changed = false;

		for (int lx = 0; lx < 16; lx++) {
			int northY = north != null
					? surfaceY(level, north, minX + lx, minZ - 1, ceiling, caveTop)
					: Integer.MIN_VALUE;
			for (int lz = 0; lz < 16; lz++) {
				int x = minX + lx;
				int z = minZ + lz;
				int y = surfaceY(level, chunk, x, z, ceiling, caveTop);
				int color = y == Integer.MIN_VALUE ? 0 : color(level, chunk, x, y, z, northY == Integer.MIN_VALUE ? y : northY);
				int before = store.color(x, z);
				if (before != color) {
					store.set(x, z, color);
					changed = true;
				}
				northY = y;
			}
		}
		if (changed) {
			store.version++;
		}
	}

	/**
	 * Wirklich geladener Chunk oder null. ClientLevel.hasChunk() meldet in 26.3 immer true und
	 * getChunk() liefert fuer fehlende Chunks einen leeren Platzhalter, deshalb direkt den Cache fragen.
	 */
	public static LevelChunk loadedChunk(ClientLevel level, int chunkX, int chunkZ) {
		return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
	}

	/** Hoehe des obersten sichtbaren Blocks, in Dimensionen mit Decke ab Spielerhoehe nach unten. */
	private int surfaceY(ClientLevel level, LevelChunk chunk, int x, int z, boolean ceiling, int caveTop) {
		int minY = level.getMinY();
		if (!ceiling) {
			int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) - 1;
			return y < minY ? Integer.MIN_VALUE : y;
		}
		for (int y = caveTop; y >= minY; y--) {
			if (!chunk.getBlockState(pos.set(x, y, z)).isAir()) {
				return y;
			}
		}
		return Integer.MIN_VALUE;
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
		int tries = 0;
		while (mapColor == MapColor.NONE && y > minY && tries++ < 4) {
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
