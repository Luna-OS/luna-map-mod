package luna.map;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

/** Einstellungen, gespeichert in config/lunamap.json. */
public class LunaConfig {
	public enum Size {
		SMALL(64), MEDIUM(96), LARGE(128);

		public final int pixels;

		Size(int pixels) {
			this.pixels = pixels;
		}
	}

	public enum Zoom {
		NEAR(32), MEDIUM(64), FAR(128);

		/** Halbe Kantenlaenge des Ausschnitts in Bloecken. */
		public final int radius;

		Zoom(int radius) {
			this.radius = radius;
		}
	}

	public enum Corner {
		TOP_LEFT, TOP_RIGHT
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static LunaConfig instance;

	public boolean minimap = true;
	public Size size = Size.MEDIUM;
	public Zoom zoom = Zoom.MEDIUM;
	public boolean coordinates = true;
	public boolean worldWaypoints = true;
	public Corner corner = Corner.TOP_RIGHT;

	public static LunaConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("lunamap.json");
	}

	private static LunaConfig load() {
		Path file = file();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				LunaConfig config = GSON.fromJson(reader, LunaConfig.class);
				if (config != null) {
					if (config.size == null) {
						config.size = Size.MEDIUM;
					}
					if (config.zoom == null) {
						config.zoom = Zoom.MEDIUM;
					}
					if (config.corner == null) {
						config.corner = Corner.TOP_RIGHT;
					}
					return config;
				}
			} catch (IOException | JsonParseException e) {
				LunaMap.LOGGER.warn("Konnte {} nicht lesen, nehme Standardwerte", file, e);
			}
		}
		return new LunaConfig();
	}

	public void save() {
		try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
			GSON.toJson(this, writer);
		} catch (IOException e) {
			LunaMap.LOGGER.warn("Konnte Einstellungen nicht speichern", e);
		}
	}
}
