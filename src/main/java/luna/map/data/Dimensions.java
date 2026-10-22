package luna.map.data;

import java.util.Locale;
import java.util.regex.Pattern;

import net.minecraft.network.chat.Component;

/**
 * Dimensionen als Text ("minecraft:the_nether", "nrz:parallel" …): Anzeigenamen und die Namen, mit denen
 * Xaero's Minimap Wegpunkte im Chat einer Dimension zuordnet. Dimensionen aus Datapacks und Mods werden
 * genauso behandelt wie Oberwelt, Nether und End – eigene Karte, eigene Wegpunkte.
 */
public final class Dimensions {
	public static final String OVERWORLD = "minecraft:overworld";
	public static final String NETHER = "minecraft:the_nether";
	public static final String END = "minecraft:the_end";

	/** Xaero haengt an die Dimension noch den Namen des Wegpunkt-Sets an: "_waypoints", "_mw$default" … */
	private static final Pattern XAERO_SET = Pattern.compile("_(waypoints|mw\\$[^_:]*|mw-?\\d[^_:]*)$");

	private Dimensions() {
	}

	/** Oberwelt/Nether/End uebersetzt, sonst der Pfad schoen geschrieben ("nrz:parallel" -> "Parallel"). */
	public static Component name(String dimension) {
		if (dimension == null) {
			return Component.literal("?");
		}
		return switch (dimension) {
			case OVERWORLD -> Component.translatable("lunamap.dim.overworld");
			case NETHER -> Component.translatable("lunamap.dim.nether");
			case END -> Component.translatable("lunamap.dim.end");
			default -> Component.literal(prettyPath(dimension));
		};
	}

	static String prettyPath(String dimension) {
		String path = dimension.substring(dimension.indexOf(':') + 1);
		path = path.substring(path.lastIndexOf('/') + 1);
		StringBuilder out = new StringBuilder();
		for (String word : path.split("[_\\-]+")) {
			if (!word.isEmpty()) {
				out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return out.isEmpty() ? dimension : out.toString();
	}

	/** Dimension wie Xaero sie im Chat schickt: overworld, the_nether, the_end, sonst dim%namespace$pfad. */
	public static String toXaero(String dimension) {
		if (dimension == null) {
			return "overworld";
		}
		return switch (dimension) {
			case OVERWORLD -> "overworld";
			case NETHER -> "the_nether";
			case END -> "the_end";
			default -> "dim%" + dimension.replace(':', '$');
		};
	}

	/**
	 * Liest Xaeros Dimensionsangabe (schon entschluesselt, ohne "Internal-"): "the_nether_waypoints",
	 * "dim%-1_mw$default", "dim%nrz$parallel_waypoints", aeltere Luna-Versionen auch ohne Set ("the_nether").
	 */
	public static String fromXaero(String key) {
		int slash = key.indexOf('/');
		if (slash >= 0) {
			key = key.substring(0, slash);
		}
		key = XAERO_SET.matcher(key.strip()).replaceFirst("");
		if (key.startsWith("dim%")) {
			key = key.substring(4);
		}
		return switch (key.toLowerCase(Locale.ROOT)) {
			case "overworld", "0", "minecraft$overworld" -> OVERWORLD;
			case "the_nether", "nether", "-1", "minecraft$the_nether" -> NETHER;
			case "the_end", "end", "1", "minecraft$the_end" -> END;
			default -> {
				String id = key.replace('$', ':').toLowerCase(Locale.ROOT);
				yield id.contains(":") ? id : "minecraft:" + id;
			}
		};
	}

	/**
	 * Bis 2.9.0 landeten von Xaero geteilte Wegpunkte in Dimensionen wie "minecraft:the_nether_waypoints"
	 * (Set-Name nicht abgeschnitten). Solche Angaben werden beim Laden richtiggestellt.
	 */
	public static String repair(String dimension) {
		if (dimension == null || !XAERO_SET.matcher(dimension).find()) {
			return dimension;
		}
		String fixed = XAERO_SET.matcher(dimension).replaceFirst("");
		return fromXaero(fixed.startsWith("minecraft:") ? fixed.substring("minecraft:".length()) : fixed.replace(':', '$'));
	}
}
