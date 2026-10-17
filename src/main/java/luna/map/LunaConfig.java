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
	public enum Zoom {
		VERY_NEAR(16), NEAR(32), MEDIUM(64), FAR(128);

		/** Halbe Kantenlaenge des Ausschnitts in Bloecken. */
		public final int radius;

		Zoom(int radius) {
			this.radius = radius;
		}
	}

	public static final int MIN_SIZE = 48;
	public static final int MAX_SIZE = 256;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static LunaConfig instance;

	public boolean minimap = true;
	/** Kantenlaenge der Minimap in GUI-Pixeln. */
	public int mapSize = 96;
	/** Position als Anteil des freien Platzes: 0 = links/oben, 1 = rechts/unten. */
	public double posX = 1;
	public double posY = 0;
	public Zoom zoom = Zoom.MEDIUM;
	public boolean coordinates = true;
	public boolean worldWaypoints = true;
	public boolean caveMode = true;
	public boolean radar = true;
	public boolean autoUpdate = true;
	/** Beim Tod automatisch einen Todespunkt setzen (verschwindet, wenn man ihn erreicht). */
	public boolean deathPoints = true;

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
					if (config.zoom == null) {
						config.zoom = Zoom.MEDIUM;
					}
					config.mapSize = Math.max(MIN_SIZE, Math.min(MAX_SIZE, config.mapSize));
					config.posX = Math.max(0, Math.min(1, config.posX));
					config.posY = Math.max(0, Math.min(1, config.posY));
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
