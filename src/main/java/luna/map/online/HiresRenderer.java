package luna.map.online;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Zeichnet eine BlueMap-Hires-Kachel (3D-Modell im PRBM-Format, 32x32 Bloecke) von oben als Bild mit echten
 * Block-Texturen: 16 Pixel pro Block. Nur nach oben zeigende Flaechen zaehlen, die hoechste gewinnt; Wasser
 * und Glas werden halbdurchsichtig ueber den Boden gelegt.
 */
public final class HiresRenderer {
	public static final int PX_PER_BLOCK = 16;

	/** Eine Block-Textur aus BlueMaps textures.json (nur das erste Animationsbild). */
	public record Texture(int[] argb, int width, int height, boolean translucent) {
	}

	private HiresRenderer() {
	}

	/** PRBM-Daten (auch gzip) -> ARGB-Bild mit size x size Pixeln, size = tileSize * 16. Leer = Alpha 0. */
	public static int[] render(byte[] data, int tileSize, List<Texture> textures) throws IOException {
		ByteBuffer buf = ByteBuffer.wrap(gunzip(data)).order(ByteOrder.LITTLE_ENDIAN);
		int version = buf.get() & 0xFF;
		int header = buf.get() & 0xFF;
		if (version != 1 || (header & 0x1F) < 7) {
			throw new IOException("unbekanntes PRBM-Format " + version + "/" + header);
		}
		int count = read3(buf);
		read3(buf); // Indizes, BlueMap schreibt keine

		int position = -1, normal = -1, color = -1, uv = -1, ao = -1, blocklight = -1, sunlight = -1;
		for (int a = 0; a < 7; a++) {
			String name = readString(buf);
			int type = buf.get() & 0xFF;
			pad(buf);
			int encoding = type & 0x0F;
			int cardinality = ((type >> 4) & 0x3) + 1;
			int start = buf.position();
			switch (name) {
				case "position" -> position = start;
				case "normal" -> normal = start;
				case "color" -> color = start;
				case "uv" -> uv = start;
				case "ao" -> ao = start;
				case "blocklight" -> blocklight = start;
				case "sunlight" -> sunlight = start;
				default -> {
				}
			}
			buf.position(start + count * cardinality * bytes(encoding));
		}
		if (position < 0 || normal < 0 || uv < 0) {
			throw new IOException("PRBM ohne Position/Normale/UV");
		}
		pad(buf);

		int size = tileSize * PX_PER_BLOCK;
		int[] solid = new int[size * size];
		float[] solidY = new float[size * size];
		int[] glass = new int[size * size];
		float[] glassY = new float[size * size];
		java.util.Arrays.fill(solidY, Float.NEGATIVE_INFINITY);
		java.util.Arrays.fill(glassY, Float.NEGATIVE_INFINITY);

		while (buf.remaining() >= 4) {
			int material = buf.getInt();
			if (material == -1) {
				break;
			}
			int start = buf.getInt();
			int length = buf.getInt();
			Texture texture = material >= 0 && material < textures.size() ? textures.get(material) : null;
			if (texture == null) {
				continue;
			}
			for (int v = start; v + 2 < start + length && v + 2 < count; v += 3) {
				// nur Flaechen, die nach oben zeigen (BlueMap speichert die Normale pro Ecke)
				float ny = buf.get(normal + v * 3 + 1) / 127f;
				if (ny < 0.3f) {
					continue;
				}
				triangle(buf, v, position, uv, color, ao, blocklight, sunlight, texture, size,
						texture.translucent() ? glass : solid, texture.translucent() ? glassY : solidY);
			}
		}

		int[] out = new int[size * size];
		for (int i = 0; i < out.length; i++) {
			int base = solid[i];
			if (glassY[i] > solidY[i] && glass[i] != 0) {
				base = blend(glass[i], base);
			}
			out[i] = base;
		}
		return out;
	}

	private static void triangle(ByteBuffer buf, int v, int position, int uv, int color, int ao, int blocklight, int sunlight,
			Texture texture, int size, int[] target, float[] depth) {
		float[] x = new float[3], z = new float[3], y = new float[3], u = new float[3], w = new float[3];
		float[] r = new float[3], g = new float[3], b = new float[3], shade = new float[3];
		for (int k = 0; k < 3; k++) {
			int p = position + (v + k) * 12;
			x[k] = buf.getFloat(p) * PX_PER_BLOCK;
			y[k] = buf.getFloat(p + 4);
			z[k] = buf.getFloat(p + 8) * PX_PER_BLOCK;
			u[k] = buf.getFloat(uv + (v + k) * 8);
			w[k] = buf.getFloat(uv + (v + k) * 8 + 4);
			if (color >= 0) {
				r[k] = (buf.get(color + (v + k) * 3) & 0xFF) / 255f;
				g[k] = (buf.get(color + (v + k) * 3 + 1) & 0xFF) / 255f;
				b[k] = (buf.get(color + (v + k) * 3 + 2) & 0xFF) / 255f;
			} else {
				r[k] = g[k] = b[k] = 1;
			}
			float occlusion = ao >= 0 ? (buf.get(ao + v + k) & 0xFF) / 255f : 1;
			float light = 15;
			if (sunlight >= 0) {
				light = Math.max(buf.get(sunlight + v + k), blocklight >= 0 ? buf.get(blocklight + v + k) : 0);
			}
			// unter Ueberhaengen etwas dunkler, aber nie schwarz
			shade[k] = occlusion * (0.55f + 0.45f * Math.max(0, Math.min(15, light)) / 15f);
		}
		float area = (x[1] - x[0]) * (z[2] - z[0]) - (x[2] - x[0]) * (z[1] - z[0]);
		if (Math.abs(area) < 1e-6f) {
			return;
		}
		int minX = Math.max(0, (int) Math.floor(Math.min(x[0], Math.min(x[1], x[2]))));
		int maxX = Math.min(size - 1, (int) Math.ceil(Math.max(x[0], Math.max(x[1], x[2]))) - 1);
		int minZ = Math.max(0, (int) Math.floor(Math.min(z[0], Math.min(z[1], z[2]))));
		int maxZ = Math.min(size - 1, (int) Math.ceil(Math.max(z[0], Math.max(z[1], z[2]))) - 1);
		int frame = Math.min(texture.width(), texture.height());
		for (int pz = minZ; pz <= maxZ; pz++) {
			float cz = pz + 0.5f;
			for (int px = minX; px <= maxX; px++) {
				float cx = px + 0.5f;
				float l0 = ((x[1] - cx) * (z[2] - cz) - (x[2] - cx) * (z[1] - cz)) / area;
				float l1 = ((x[2] - cx) * (z[0] - cz) - (x[0] - cx) * (z[2] - cz)) / area;
				float l2 = 1 - l0 - l1;
				if (l0 < -1e-4f || l1 < -1e-4f || l2 < -1e-4f) {
					continue;
				}
				float height = l0 * y[0] + l1 * y[1] + l2 * y[2];
				int i = pz * size + px;
				if (height <= depth[i]) {
					continue;
				}
				float tu = l0 * u[0] + l1 * u[1] + l2 * u[2];
				float tv = l0 * w[0] + l1 * w[1] + l2 * w[2];
				int tx = Math.max(0, Math.min(texture.width() - 1, (int) Math.floor(tu * texture.width())));
				int ty = Math.max(0, Math.min(frame - 1, (int) Math.floor(tv * frame)));
				int texel = texture.argb()[ty * texture.width() + tx];
				int alpha = texel >>> 24;
				if (!texture.translucent() && alpha < 128) {
					continue; // ausgeschnitten (Blaetter, Gras-Ränder ...)
				}
				if (alpha == 0) {
					continue;
				}
				float s = l0 * shade[0] + l1 * shade[1] + l2 * shade[2];
				int cr = clamp(((texel >> 16) & 0xFF) * (l0 * r[0] + l1 * r[1] + l2 * r[2]) * s);
				int cg = clamp(((texel >> 8) & 0xFF) * (l0 * g[0] + l1 * g[1] + l2 * g[2]) * s);
				int cb = clamp((texel & 0xFF) * (l0 * b[0] + l1 * b[1] + l2 * b[2]) * s);
				int outAlpha = texture.translucent() ? Math.max(alpha, 150) : 255;
				target[i] = outAlpha << 24 | cr << 16 | cg << 8 | cb;
				depth[i] = height;
			}
		}
	}

	private static int blend(int top, int bottom) {
		int a = top >>> 24;
		if (bottom == 0) {
			return top;
		}
		int r = (((top >> 16) & 0xFF) * a + ((bottom >> 16) & 0xFF) * (255 - a)) / 255;
		int g = (((top >> 8) & 0xFF) * a + ((bottom >> 8) & 0xFF) * (255 - a)) / 255;
		int b = ((top & 0xFF) * a + (bottom & 0xFF) * (255 - a)) / 255;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static int clamp(float value) {
		return Math.max(0, Math.min(255, (int) value));
	}

	private static int bytes(int encoding) {
		return switch (encoding) {
			case 3, 7 -> 1;
			case 4, 8 -> 2;
			default -> 4;
		};
	}

	private static int read3(ByteBuffer buf) {
		return (buf.get() & 0xFF) | (buf.get() & 0xFF) << 8 | (buf.get() & 0xFF) << 16;
	}

	private static String readString(ByteBuffer buf) {
		StringBuilder s = new StringBuilder();
		byte c;
		while ((c = buf.get()) != 0) {
			s.append((char) c);
		}
		return s.toString();
	}

	private static void pad(ByteBuffer buf) {
		buf.position(buf.position() + ((-buf.position()) & 3));
	}

	static byte[] gunzip(byte[] data) throws IOException {
		if (data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
				return in.readAllBytes();
			}
		}
		return data;
	}
}
