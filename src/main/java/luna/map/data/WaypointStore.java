package luna.map.data;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import luna.map.LunaMap;

/** Wegpunkte einer Welt (alle Dimensionen). */
public final class WaypointStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST = new TypeToken<List<Waypoint>>() { }.getType();

	private final Path file;
	private final List<Waypoint> waypoints = new ArrayList<>();

	WaypointStore(Path file) {
		this.file = file;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				List<Waypoint> loaded = GSON.fromJson(reader, LIST);
				if (loaded != null) {
					waypoints.addAll(loaded);
				}
			} catch (IOException | JsonParseException e) {
				LunaMap.LOGGER.warn("Konnte Wegpunkte aus {} nicht lesen", file, e);
			}
		}
	}

	public List<Waypoint> in(String dimension) {
		List<Waypoint> result = new ArrayList<>();
		if (dimension == null) {
			return result;
		}
		for (Waypoint waypoint : waypoints) {
			if (dimension.equals(waypoint.dimension)) {
				result.add(waypoint);
			}
		}
		return result;
	}

	/** Nur die nicht ausgeblendeten Wegpunkte einer Dimension (fuer Welt, Minimap und Weltkarte). */
	public List<Waypoint> visible(String dimension) {
		List<Waypoint> result = in(dimension);
		result.removeIf(w -> w.hidden);
		return result;
	}

	/** Alle Wegpunkte aller Dimensionen (Kopie). */
	public List<Waypoint> all() {
		return new ArrayList<>(waypoints);
	}

	public int count(String dimension) {
		return in(dimension).size();
	}

	public void add(Waypoint waypoint) {
		waypoints.add(waypoint);
		save();
	}

	/** Fuegt Wegpunkte hinzu, die es (gleicher Name, gleiche Stelle, gleiche Dimension) noch nicht gibt. */
	public int addAllNew(List<Waypoint> incoming) {
		int added = 0;
		for (Waypoint w : incoming) {
			boolean exists = false;
			for (Waypoint old : waypoints) {
				if (old.x == w.x && old.y == w.y && old.z == w.z && java.util.Objects.equals(old.name, w.name)
						&& java.util.Objects.equals(old.dimension, w.dimension)) {
					exists = true;
					break;
				}
			}
			if (!exists) {
				waypoints.add(w);
				added++;
			}
		}
		if (added > 0) {
			save();
		}
		return added;
	}

	public void remove(Waypoint waypoint) {
		waypoints.remove(waypoint);
		save();
	}

	public void save() {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(waypoints, LIST, writer);
			}
		} catch (IOException e) {
			LunaMap.LOGGER.warn("Konnte Wegpunkte nicht speichern", e);
		}
	}
}
