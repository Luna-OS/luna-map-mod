package luna.map.ui;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.platform.NativeImage;

import luna.map.LunaMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

/**
 * Mob-Symbole fuers Radar wie bei Xaero: der ganze Kopf des Mobs (mit Schnauze, Ohren, Hoernern, Schnabel ...)
 * wird einmal von vorne aus dem Modell und der Textur gezeichnet, bekommt einen dunklen Rand und wird als kleine
 * Textur zwischengespeichert. Mobs ohne Kopf (Schleim, Ghast, Fische) werden ganz von vorne gezeigt.
 */
final class MobHeads {
	private record Icon(Identifier id, int width, int height) {
	}

	private record Key(EntityType<?> type, Identifier texture) {
	}

	private record CachedTexture(Identifier id, long until) {
	}

	/** Ein nach vorne zeigendes Viereck des Modells, schon in Kopf-Koordinaten (Pixel, y nach unten). */
	private record Quad(float[] x, float[] y, float[] u, float[] v, float depth) {
	}

	private record Pixels(int[] argb, int width, int height) {
	}

	private static final Icon FAILED = new Icon(null, 0, 0);
	private static final long TEXTURE_CACHE_MS = 1500;
	private static final int OUTLINE = 0xFF0F0B1E;

	private static final Map<Key, Icon> ICONS = new HashMap<>();
	private static final Map<Identifier, Pixels> PIXELS = new HashMap<>();
	private static final Map<Integer, CachedTexture> TEXTURES = new HashMap<>();
	private static Field cubesField;
	private static Field childrenField;
	private static boolean reflectionFailed;
	private static long lastCleanup;
	private static int counter;

	private MobHeads() {
	}

	/** Zeichnet das Symbol mittig auf (x, y), size = Hoehe in GUI-Pixeln. false = keins (dann Punkt). */
	static boolean draw(GuiGraphicsExtractor graphics, Entity entity, double x, double y, int size, float alpha) {
		if (!(entity instanceof LivingEntity living)) {
			return false;
		}
		Identifier texture = texture(living);
		if (texture == null) {
			return false;
		}
		Icon icon = ICONS.computeIfAbsent(new Key(living.getType(), texture), key -> bake(living, texture));
		if (icon == FAILED) {
			return false;
		}
		// Hoehe = size, Breite im Seitenverhaeltnis (breite Koepfe wie Kuh oder Ghast werden nicht gequetscht)
		int h = Math.max(4, size);
		int w = Math.max(4, Math.round(h * icon.width() / (float) icon.height()));
		if (w > h * 2) {
			w = h * 2;
			h = Math.max(4, Math.round(w * icon.height() / (float) icon.width()));
		}
		int x0 = (int) Math.round(x - w / 2.0);
		int y0 = (int) Math.round(y - h / 2.0);
		int color = ((int) (alpha * 255) << 24) | 0xFFFFFF;
		graphics.blit(RenderPipelines.GUI_TEXTURED, icon.id(), x0, y0, 0, 0, w, h, icon.width(), icon.height(),
				icon.width(), icon.height(), color);
		return true;
	}

	// ---------------------------------------------------------------- Symbol erzeugen

	private static Icon bake(LivingEntity entity, Identifier texture) {
		try {
			EntityRenderer<?, ?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
			if (!(renderer instanceof LivingEntityRenderer<?, ?, ?> living) || !reflect()) {
				return FAILED;
			}
			Pixels pixels = pixels(texture);
			if (pixels == null) {
				return FAILED;
			}
			ModelPart root = living.getModel().root();
			ModelPart head = findPart(root, "head");
			List<Quad> quads = new ArrayList<>();
			collect(head != null ? head : root, new Matrix4f(), quads);
			if (quads.isEmpty() && head != null) {
				collect(root, new Matrix4f(), quads);
			}
			if (quads.isEmpty()) {
				return FAILED;
			}
			return raster(quads, pixels);
		} catch (Exception e) {
			LunaMap.LOGGER.debug("Kein Radar-Symbol fuer {}", entity.getType(), e);
			return FAILED;
		}
	}

	/** Alle nach vorne (-Z) zeigenden Flaechen des Teils und seiner Kinder sammeln. */
	@SuppressWarnings("unchecked")
	private static void collect(ModelPart part, Matrix4f transform, List<Quad> out) throws ReflectiveOperationException {
		if (!part.visible) {
			return;
		}
		Vector3f p = new Vector3f();
		for (ModelPart.Cube cube : (List<ModelPart.Cube>) cubesField.get(part)) {
			for (ModelPart.Polygon polygon : cube.polygons) {
				ModelPart.Vertex[] vertices = polygon.vertices();
				if (vertices.length < 3) {
					continue;
				}
				float[] xs = new float[vertices.length], ys = new float[vertices.length];
				float[] us = new float[vertices.length], vs = new float[vertices.length];
				float depth = 0;
				for (int i = 0; i < vertices.length; i++) {
					transform.transformPosition(vertices[i].x(), vertices[i].y(), vertices[i].z(), p);
					xs[i] = p.x;
					ys[i] = p.y;
					depth += p.z;
					us[i] = vertices[i].u();
					vs[i] = vertices[i].v();
				}
				// Richtung nach dem Drehen: nur Flaechen, die zum Betrachter (-Z) zeigen
				Vector3f normal = transform.transformDirection(new Vector3f(polygon.normal()));
				if (normal.z > -0.3f) {
					continue;
				}
				out.add(new Quad(xs, ys, us, vs, depth / vertices.length));
			}
		}
		for (ModelPart child : ((Map<String, ModelPart>) childrenField.get(part)).values()) {
			PartPose pose = child.getInitialPose();
			Matrix4f m = new Matrix4f(transform)
					.translate(pose.x(), pose.y(), pose.z())
					.rotateZYX(pose.zRot(), pose.yRot(), pose.xRot())
					.scale(pose.xScale(), pose.yScale(), pose.zScale());
			collect(child, m, out);
		}
	}

	/** Die Vierecke von hinten nach vorne in ein kleines Bild zeichnen, dann einen dunklen Rand drumherum. */
	private static Icon raster(List<Quad> quads, Pixels tex) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (Quad q : quads) {
			for (int i = 0; i < q.x().length; i++) {
				minX = Math.min(minX, q.x()[i]);
				maxX = Math.max(maxX, q.x()[i]);
				minY = Math.min(minY, q.y()[i]);
				maxY = Math.max(maxY, q.y()[i]);
			}
		}
		float dim = Math.max(maxX - minX, maxY - minY);
		if (dim <= 0) {
			return FAILED;
		}
		// etwa 32 Pixel fuer die laengere Seite, mindestens 1 Pixel pro Modell-Einheit
		int k = Math.max(1, Math.round(32 / dim));
		int w = (int) Math.ceil((maxX - minX) * k) + 2;
		int h = (int) Math.ceil((maxY - minY) * k) + 2;
		if (w > 256 || h > 256) {
			return FAILED;
		}
		int[] img = new int[w * h];
		quads.sort((a, b) -> Float.compare(b.depth(), a.depth())); // hinten zuerst
		for (Quad q : quads) {
			float qx0 = Float.MAX_VALUE, qx1 = -Float.MAX_VALUE, qy0 = Float.MAX_VALUE, qy1 = -Float.MAX_VALUE;
			float uAtX0 = 0, uAtX1 = 0, vAtY0 = 0, vAtY1 = 0;
			for (int i = 0; i < q.x().length; i++) {
				if (q.x()[i] < qx0) {
					qx0 = q.x()[i];
					uAtX0 = q.u()[i];
				}
				if (q.x()[i] > qx1) {
					qx1 = q.x()[i];
					uAtX1 = q.u()[i];
				}
				if (q.y()[i] < qy0) {
					qy0 = q.y()[i];
					vAtY0 = q.v()[i];
				}
				if (q.y()[i] > qy1) {
					qy1 = q.y()[i];
					vAtY1 = q.v()[i];
				}
			}
			int px0 = 1 + Math.round((qx0 - minX) * k);
			int px1 = 1 + Math.round((qx1 - minX) * k);
			int py0 = 1 + Math.round((qy0 - minY) * k);
			int py1 = 1 + Math.round((qy1 - minY) * k);
			if (px1 <= px0 || py1 <= py0) {
				continue;
			}
			for (int py = py0; py < py1; py++) {
				float fy = (py + 0.5f - py0) / (py1 - py0);
				float v = vAtY0 + (vAtY1 - vAtY0) * fy;
				int ty = Math.max(0, Math.min(tex.height() - 1, (int) Math.floor(v * tex.height())));
				for (int px = px0; px < px1; px++) {
					float fx = (px + 0.5f - px0) / (px1 - px0);
					float u = uAtX0 + (uAtX1 - uAtX0) * fx;
					int tx = Math.max(0, Math.min(tex.width() - 1, (int) Math.floor(u * tex.width())));
					int argb = tex.argb()[ty * tex.width() + tx];
					if ((argb >>> 24) >= 128) {
						img[py * w + px] = argb | 0xFF000000;
					}
				}
			}
		}
		// dunkler Rand um alles Sichtbare
		int[] out = img.clone();
		boolean any = false;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (img[y * w + x] != 0) {
					any = true;
					continue;
				}
				if (x > 0 && img[y * w + x - 1] != 0 || x < w - 1 && img[y * w + x + 1] != 0
						|| y > 0 && img[(y - 1) * w + x] != 0 || y < h - 1 && img[(y + 1) * w + x] != 0) {
					out[y * w + x] = OUTLINE;
				}
			}
		}
		if (!any) {
			return FAILED;
		}
		NativeImage image = new NativeImage(w, h, true);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setPixel(x, y, out[y * w + x]);
			}
		}
		Identifier id = LunaMap.id("mob_icon/" + (counter++));
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Luna Map mob icon", image));
		return new Icon(id, w, h);
	}

	/** Pixel einer Entity-Textur aus den Ressourcen (Texturpakete inklusive). */
	private static Pixels pixels(Identifier texture) {
		if (PIXELS.containsKey(texture)) {
			return PIXELS.get(texture);
		}
		Pixels result = null;
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(texture);
		if (resource.isPresent()) {
			try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
				int[] argb = new int[image.getWidth() * image.getHeight()];
				for (int y = 0; y < image.getHeight(); y++) {
					for (int x = 0; x < image.getWidth(); x++) {
						argb[y * image.getWidth() + x] = image.getPixel(x, y);
					}
				}
				result = new Pixels(argb, image.getWidth(), image.getHeight());
			} catch (Exception e) {
				result = null;
			}
		}
		PIXELS.put(texture, result);
		return result;
	}

	/** Aktuelle Textur des Mobs (kurz zwischengespeichert, weil der Renderer dafuer einiges ausrechnet). */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Identifier texture(LivingEntity entity) {
		long now = System.currentTimeMillis();
		if (now - lastCleanup > 10_000) {
			lastCleanup = now;
			TEXTURES.values().removeIf(c -> c.until() < now);
		}
		CachedTexture cached = TEXTURES.get(entity.getId());
		if (cached != null && cached.until() > now) {
			return cached.id();
		}
		Identifier id = null;
		try {
			EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
			if (renderer instanceof LivingEntityRenderer living) {
				EntityRenderState state = renderer.createRenderState(entity, 1f);
				id = living.getTextureLocation((net.minecraft.client.renderer.entity.state.LivingEntityRenderState) state);
			}
		} catch (RuntimeException e) {
			id = null;
		}
		TEXTURES.put(entity.getId(), new CachedTexture(id, now + TEXTURE_CACHE_MS));
		return id;
	}

	/** Teil "head" im Modellbaum suchen; sonst einen Teil, dessen Name "head" enthaelt (z. B. von Modell-Mods). */
	@SuppressWarnings("unchecked")
	private static ModelPart findPart(ModelPart root, String name) throws ReflectiveOperationException {
		ArrayDeque<ModelPart> queue = new ArrayDeque<>();
		queue.add(root);
		ModelPart similar = null;
		while (!queue.isEmpty()) {
			ModelPart part = queue.poll();
			Map<String, ModelPart> children = (Map<String, ModelPart>) childrenField.get(part);
			if (children.containsKey(name)) {
				return children.get(name);
			}
			for (Map.Entry<String, ModelPart> entry : children.entrySet()) {
				if (similar == null && entry.getKey().toLowerCase(Locale.ROOT).contains(name)) {
					similar = entry.getValue();
				}
			}
			queue.addAll(children.values());
		}
		return similar;
	}

	private static boolean reflect() {
		if (cubesField != null) {
			return true;
		}
		if (reflectionFailed) {
			return false;
		}
		try {
			cubesField = ModelPart.class.getDeclaredField("cubes");
			cubesField.setAccessible(true);
			childrenField = ModelPart.class.getDeclaredField("children");
			childrenField.setAccessible(true);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			reflectionFailed = true;
			cubesField = null;
			LunaMap.LOGGER.warn("Radar-Symbole nicht verfuegbar: {}", e.toString());
			return false;
		}
	}
}
