package luna.map.importer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/**
 * Liest eine Regionsdatei von Xaero's World Map (x_z.zip mit "region.xaero", 512x512 Bloecke).
 * Nur die Werte, die Luna Map braucht: Block-ID, Hoehe, oberste Hoehe, Wasser-Overlay und Biom.
 * Unterstuetzt die Formatversionen bis 8.8 (Xaero's World Map 1.47).
 */
final class XaeroRegionReader {
	static final int SIZE = 512;

	/** Daten einer Region, Index = z * 512 + x. blocks[i] == null heisst: nicht erkundet. */
	static final class Region {
		final String[] blocks = new String[SIZE * SIZE];
		final short[] heights = new short[SIZE * SIZE];
		final short[] tops = new short[SIZE * SIZE];
		final boolean[] water = new boolean[SIZE * SIZE];
		final String[] biomes = new String[SIZE * SIZE];
		int pixels;
	}

	private XaeroRegionReader() {
	}

	static Region read(Path file) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			ZipEntry entry = zip.getNextEntry();
			if (entry == null) {
				throw new IOException("leere Zip-Datei");
			}
			return read(new DataInputStream(new BufferedInputStream(zip)));
		}
	}

	private static Region read(DataInputStream in) throws IOException {
		Region region = new Region();
		List<String> palette = new ArrayList<>();
		List<String> biomePalette = new ArrayList<>();
		int minor = 0;
		int major = 0;
		boolean newColors = false;
		int pending = in.read();
		if (pending == 255) {
			int version = in.readInt();
			minor = version & 0xFFFF;
			major = (version >> 16) & 0xFFFF;
			if (major == 2 && minor >= 5) {
				newColors = in.read() == 1;
			}
			if (minor > 8 || major > 8) {
				throw new IOException("unbekannte Xaero-Formatversion " + major + "." + minor);
			}
			pending = -1;
		}
		boolean legacyColors = minor < 5 || (major <= 2 && !newColors);

		while (true) {
			int chunkByte = pending != -1 ? pending : in.read();
			pending = -1;
			if (chunkByte == -1) {
				break;
			}
			int tileChunkX = chunkByte >> 4;
			int tileChunkZ = chunkByte & 15;
			for (int tx = 0; tx < 4; tx++) {
				for (int tz = 0; tz < 4; tz++) {
					int params = in.readInt();
					if (params == -1) {
						continue;
					}
					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							if (x != 0 || z != 0) {
								params = in.readInt();
							}
							int bx = (tileChunkX * 4 + tx) * 16 + x;
							int bz = (tileChunkZ * 4 + tz) * 16 + z;
							readPixel(in, params, minor, major, legacyColors, palette, biomePalette, region, bz * SIZE + bx);
						}
					}
					if (minor >= 4) {
						in.read();
					}
					if (minor >= 6) {
						in.readInt();
						if (minor >= 7) {
							in.read();
						}
					}
				}
			}
		}
		return region;
	}

	private static void readPixel(DataInputStream in, int p, int minor, int major, boolean legacyColors,
			List<String> palette, List<String> biomePalette, Region region, int index) throws IOException {
		String block = "minecraft:grass_block";
		if ((p & 1) != 0) {
			if (major == 0) {
				in.readInt();
				block = null;
			} else if ((p & 0x200000) != 0) {
				block = blockId(NbtIo.read(in));
				palette.add(block);
			} else {
				block = get(palette, in.readInt());
			}
		}
		int height;
		if ((p & 64) != 0) {
			height = in.read();
		} else {
			int shift = minor >= 4 ? 25 : 24;
			int h = ((p >> 12) & 255) | (((p >> shift) & 15) << 8);
			height = (h << 20) >> 20;
		}
		int top = height;
		if (minor >= 4 && (p & 0x1000000) != 0) {
			top = in.read();
		}

		boolean water = false;
		if ((p & 2) != 0) {
			int count = in.read();
			for (int i = 0; i < count; i++) {
				int o = in.readInt();
				String overlay = "minecraft:water";
				if ((o & 1) != 0) {
					if (major == 0) {
						in.readInt();
						overlay = null;
					} else if ((o & 1024) != 0) {
						overlay = blockId(NbtIo.read(in));
						palette.add(overlay);
					} else {
						overlay = get(palette, in.readInt());
					}
				}
				if (minor < 1 && (o & 2) != 0) {
					in.readInt();
				}
				int overlayColorType = legacyColors ? (o >> 8) & 3 : 0;
				if (overlayColorType == 2 || (o & 4) != 0) {
					in.readInt();
				}
				if (minor < 8 && (o & 8) != 0) {
					in.readInt();
				}
				if ("minecraft:water".equals(overlay)) {
					water = true;
				}
			}
		}

		int colorType = legacyColors ? (p >> 2) & 3 : 0;
		if (colorType == 3) {
			in.readInt();
		}
		String biome = null;
		if (colorType == 1 || colorType == 2 || (p & 0x100000) != 0) {
			if (major < 4) {
				int b = in.read();
				if (minor >= 3 && b >= 255) {
					in.readInt();
				}
			} else if ((p & 0x400000) != 0) {
				if ((p & 0x800000) != 0) {
					in.readInt();
					biome = null;
				} else {
					biome = in.readUTF();
				}
				biomePalette.add(biome);
			} else {
				biome = get(biomePalette, in.readInt());
			}
		}
		if (minor == 2 && (p & 16) != 0) {
			in.read();
		}
		if (block == null) {
			return;
		}
		if (region.blocks[index] == null) {
			region.pixels++;
		}
		region.blocks[index] = block;
		region.heights[index] = (short) height;
		region.tops[index] = (short) top;
		region.water[index] = water;
		region.biomes[index] = biome;
	}

	private static String blockId(CompoundTag tag) {
		return tag.getString("id").or(() -> tag.getString("Name")).orElse("minecraft:air");
	}

	private static String get(List<String> palette, int index) throws IOException {
		if (index < 0 || index >= palette.size()) {
			throw new EOFException("Palette-Index ausserhalb: " + index);
		}
		return palette.get(index);
	}
}
