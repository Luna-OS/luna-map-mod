package luna.map.data;

/** Ein Wegpunkt. Wird per Gson in waypoints.json gespeichert. */
public class Waypoint {
	public String name;
	public int x;
	public int y;
	public int z;
	public int color;
	public String dimension;
	/** Ausgeblendet: nicht in der Welt, auf Minimap und Weltkarte (nur in der Liste). */
	public boolean hidden;
	/** null = normaler Wegpunkt, sonst DEATH oder OLD_DEATH. */
	public String type;

	public static final String DEATH = "death";
	public static final String OLD_DEATH = "old_death";

	public Waypoint(String name, int x, int y, int z, int color, String dimension) {
		this.name = name;
		this.x = x;
		this.y = y;
		this.z = z;
		this.color = color;
		this.dimension = dimension;
	}

	/** Todespunkt (auch aeltere, aus Xaero uebernommene "Tod"-Wegpunkte). */
	public boolean isDeath() {
		return DEATH.equals(type) || OLD_DEATH.equals(type) || type == null && "Tod".equals(name);
	}

	public boolean isOldDeath() {
		return OLD_DEATH.equals(type);
	}

	/** Die ersten zwei Buchstaben des Namens, gross geschrieben (steht auf dem Wegpunkt). */
	public String initials() {
		if (name == null || name.isBlank()) {
			return "?";
		}
		String trimmed = name.strip();
		int end = trimmed.offsetByCodePoints(0, Math.min(2, trimmed.codePointCount(0, trimmed.length())));
		return trimmed.substring(0, end).toUpperCase();
	}

	public String initial() {
		return name == null || name.isBlank() ? "?" : name.strip().substring(0, 1).toUpperCase();
	}
}
