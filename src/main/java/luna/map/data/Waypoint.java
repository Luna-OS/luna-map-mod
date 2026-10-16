package luna.map.data;

/** Ein Wegpunkt. Wird per Gson in waypoints.json gespeichert. */
public class Waypoint {
	public String name;
	public int x;
	public int y;
	public int z;
	public int color;
	public String dimension;

	public Waypoint(String name, int x, int y, int z, int color, String dimension) {
		this.name = name;
		this.x = x;
		this.y = y;
		this.z = z;
		this.color = color;
		this.dimension = dimension;
	}

	public String initial() {
		return name == null || name.isBlank() ? "?" : name.strip().substring(0, 1).toUpperCase();
	}
}
