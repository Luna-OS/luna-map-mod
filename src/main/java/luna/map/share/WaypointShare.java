package luna.map.share;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import luna.map.LunaColors;
import luna.map.LunaMap;
import luna.map.data.MapSession;
import luna.map.data.Waypoint;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Wegpunkte im Chat teilen – im selben Textformat wie Xaero's Minimap
 * ("xaero-waypoint:Name:K:x:y:z:Farbe:false:0:Internal-overworld"). So koennen Xaero-Spieler Luna-Wegpunkte
 * uebernehmen und umgekehrt. Empfangene Wegpunkte erscheinen als Chatzeile mit "[Hinzufuegen]".
 */
public final class WaypointShare {
	private static final String PREFIX = "xaero-waypoint:";
	private static final String OLD_PREFIX = "xaero_waypoint:";
	/** Die 16 Chatfarben, deren Nummer Xaero als Wegpunktfarbe verschickt. */
	private static final int[] CHAT_COLORS = {
			0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
	/** Neuere Xaero-Versionen kennen noch Magenta, Hellblau, Limette, Pink und Braun. */
	private static final int[] EXTRA_COLORS = {0xFF00FF, 0x55AAFF, 0xAAFF00, 0xFF88CC, 0x8B5A2B};

	/** Ein empfangener Wegpunkt; y == null heisst "Hoehe unbekannt". */
	public record Shared(String sender, String name, int x, Integer y, int z, int color, String dimension) {
	}

	private static final List<Shared> RECEIVED = new ArrayList<>();

	private WaypointShare() {
	}

	public static void register() {
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, timestamp) ->
				!handle(message, sender != null ? sender.name() : null));
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !handle(message, null));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
				ClientCommands.literal("lunamap").then(ClientCommands.literal("wegpunkt")
						.then(ClientCommands.argument("nr", IntegerArgumentType.integer(0)).executes(ctx -> {
							add(IntegerArgumentType.getInteger(ctx, "nr"));
							return 1;
						})))));
	}

	// ---------------------------------------------------------------- senden

	/** Schickt einen Wegpunkt in den Chat (Xaero-kompatibel). */
	public static void send(Waypoint waypoint) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		String text = encode(waypoint);
		mc.gui.hud.getChat().addRecentChat(text);
		mc.player.connection.sendChat(text);
	}

	public static String encode(Waypoint waypoint) {
		String name = waypoint.name == null || waypoint.name.isBlank() ? "Wegpunkt" : waypoint.name.strip();
		if (name.length() > 48) {
			name = name.substring(0, 48);
		}
		return PREFIX + escape(name) + ":" + escape(waypoint.initial()) + ":" + waypoint.x + ":" + waypoint.y + ":"
				+ waypoint.z + ":" + nearestColor(waypoint.color) + ":false:0:Internal-" + escape(destination(waypoint.dimension));
	}

	/** Xaero: ":" -> "^col^", dann "-" -> "^min^", "_" -> "-", "*" -> "^ast^". */
	static String escape(String text) {
		return text.replace(":", "^col^").replace("-", "^min^").replace("_", "-").replace("*", "^ast^")
				.replaceAll("[\\u0000-\\u001F\\u007F\\u00A7]", "");
	}

	static String unescape(String text) {
		return text.replace("^ast^", "*").replace("-", "_").replace("^min^", "-").replace("^col^", ":");
	}

	/** Dimension wie Xaero sie benennt: overworld, the_nether, the_end, sonst dim%namespace$pfad. */
	private static String destination(String dimension) {
		if (dimension == null) {
			return "overworld";
		}
		return switch (dimension) {
			case "minecraft:overworld" -> "overworld";
			case "minecraft:the_nether" -> "the_nether";
			case "minecraft:the_end" -> "the_end";
			default -> "dim%" + dimension.replace(':', '$');
		};
	}

	private static int nearestColor(int argb) {
		int best = 15;
		long bestDistance = Long.MAX_VALUE;
		for (int i = 0; i < CHAT_COLORS.length; i++) {
			int c = CHAT_COLORS[i];
			long dr = ((argb >> 16) & 0xFF) - ((c >> 16) & 0xFF);
			long dg = ((argb >> 8) & 0xFF) - ((c >> 8) & 0xFF);
			long db = (argb & 0xFF) - (c & 0xFF);
			long distance = dr * dr * 3 + dg * dg * 4 + db * db * 2;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- empfangen

	/** Liest einen geteilten Wegpunkt aus einer Chatzeile, null wenn keiner drin ist. */
	public static Shared parse(String line, String sender) {
		int start = line.indexOf(PREFIX);
		int prefixLength = PREFIX.length();
		if (start < 0) {
			start = line.indexOf(OLD_PREFIX);
			prefixLength = OLD_PREFIX.length();
		}
		if (start < 0) {
			return null;
		}
		String body = line.substring(start + prefixLength).strip();
		String[] parts = body.split(":", -1);
		if (parts.length < 6) {
			return null;
		}
		try {
			String name = unescape(parts[0]);
			int x = Integer.parseInt(parts[2]);
			Integer y = parts[3].equals("~") ? null : Integer.valueOf(parts[3]);
			int z = Integer.parseInt(parts[4]);
			int color = xaeroColor(Integer.parseInt(parts[5]));
			// Namen duerfen Leerzeichen haben, nach dem Ziel kann aber noch anderer Text kommen
			String dimension = parts.length >= 9 ? dimension(parts[8].strip().split("\\s+")[0]) : MapSession.dimension();
			if (sender == null) {
				// Systemnachricht (z. B. von Chat-Plugins): Name steht meist davor, "<Steve> " oder "Steve: "
				String before = line.substring(0, start).replaceAll("[<>\\[\\]:»|]", " ").strip();
				String[] words = before.split("\\s+");
				sender = words.length > 0 && !words[words.length - 1].isEmpty() ? words[words.length - 1] : null;
			}
			return new Shared(sender, name.isBlank() ? "Wegpunkt" : name, x, y, z, color, dimension);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String dimension(String destination) {
		if (!destination.startsWith("Internal-")) {
			return MapSession.dimension(); // "Internal"/"External": gilt fuer die Welt, in der man gerade ist
		}
		String key = unescape(destination.substring("Internal-".length())).split("/")[0];
		switch (key) {
			case "overworld", "dim%0" -> {
				return "minecraft:overworld";
			}
			case "the_nether", "dim%-1" -> {
				return "minecraft:the_nether";
			}
			case "the_end", "dim%1" -> {
				return "minecraft:the_end";
			}
			default -> {
				if (key.startsWith("dim%")) {
					key = key.substring(4).replace('$', ':');
				}
				return key.contains(":") ? key.toLowerCase(Locale.ROOT) : "minecraft:" + key.toLowerCase(Locale.ROOT);
			}
		}
	}

	private static int xaeroColor(int index) {
		if (index >= 0 && index < CHAT_COLORS.length) {
			return 0xFF000000 | CHAT_COLORS[index];
		}
		if (index >= 16 && index < 16 + EXTRA_COLORS.length) {
			return 0xFF000000 | EXTRA_COLORS[index - 16];
		}
		return LunaColors.WAYPOINT[Math.floorMod(index, LunaColors.WAYPOINT.length)];
	}

	/** true = Nachricht war ein Wegpunkt und wurde als schoene Zeile ersetzt. */
	private static boolean handle(Component message, String sender) {
		String text = message.getString();
		if (!text.contains(PREFIX) && !text.contains(OLD_PREFIX)) {
			return false;
		}
		Shared shared = parse(text, sender);
		if (shared == null) {
			return false;
		}
		int id;
		synchronized (RECEIVED) {
			RECEIVED.add(shared);
			id = RECEIVED.size() - 1;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(line(shared, id)));
		return true;
	}

	private static Component line(Shared shared, int id) {
		String coords = shared.x() + ", " + (shared.y() == null ? "~" : shared.y()) + ", " + shared.z();
		Component dimName = dimensionName(shared.dimension());
		MutableComponent hover = Component.literal(shared.name()).withStyle(Style.EMPTY.withColor(shared.color() & 0xFFFFFF))
				.append(Component.literal("\n" + coords).withStyle(Style.EMPTY.withColor(LunaColors.LAVENDER & 0xFFFFFF)))
				.append(Component.literal("\n").append(dimName));
		MutableComponent line = Component.literal("» ").withStyle(Style.EMPTY.withColor(LunaColors.MOON & 0xFFFFFF));
		line.append(shared.sender() != null
				? Component.translatable("lunamap.share.received", shared.sender())
				: Component.translatable("lunamap.share.received_unknown"));
		line.append(" ");
		line.append(Component.literal("● ").withStyle(Style.EMPTY.withColor(shared.color() & 0xFFFFFF)));
		line.append(Component.literal(shared.name()).withStyle(Style.EMPTY.withColor(0xFFFFFF)));
		line.append(Component.literal(" (" + coords + ") ").withStyle(Style.EMPTY.withColor(LunaColors.LAVENDER & 0xFFFFFF)));
		line.append(Component.translatable("lunamap.share.add").withStyle(Style.EMPTY.withColor(0x7CF2A2)));
		// die ganze Zeile ist anklickbar, nicht nur "[Hinzufuegen]"
		ClickEvent click = new ClickEvent.RunCommand("/lunamap wegpunkt " + id);
		HoverEvent hoverAdd = new HoverEvent.ShowText(hover.copy().append(Component.literal("\n\n"))
				.append(Component.translatable("lunamap.share.add_hover").withStyle(Style.EMPTY.withColor(0x7CF2A2))));
		return line.withStyle(style -> style.withClickEvent(click).withHoverEvent(hoverAdd));
	}

	private static Component dimensionName(String dimension) {
		if (dimension == null) {
			return Component.literal("?");
		}
		return switch (dimension) {
			case "minecraft:overworld" -> Component.translatable("lunamap.dim.overworld");
			case "minecraft:the_nether" -> Component.translatable("lunamap.dim.nether");
			case "minecraft:the_end" -> Component.translatable("lunamap.dim.end");
			default -> Component.literal(dimension);
		};
	}

	/** Klick auf "[Hinzufuegen]". */
	private static void add(int id) {
		Minecraft mc = Minecraft.getInstance();
		Shared shared;
		synchronized (RECEIVED) {
			shared = id < RECEIVED.size() ? RECEIVED.get(id) : null;
		}
		if (shared == null || MapSession.waypoints() == null) {
			return;
		}
		int y = shared.y() != null ? shared.y() : mc.player != null ? mc.player.getBlockY() : 64;
		String dimension = shared.dimension() != null ? shared.dimension() : MapSession.dimension();
		for (Waypoint existing : MapSession.waypoints().in(dimension)) {
			if (existing.x == shared.x() && existing.z == shared.z() && existing.name.equals(shared.name())) {
				mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("lunamap.share.exists", shared.name())
						.withStyle(Style.EMPTY.withColor(LunaColors.LAVENDER & 0xFFFFFF)));
				return;
			}
		}
		MapSession.waypoints().add(new Waypoint(shared.name(), shared.x(), y, shared.z(), shared.color(), dimension));
		mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("lunamap.share.added", shared.name())
				.withStyle(Style.EMPTY.withColor(0x7CF2A2)));
		LunaMap.LOGGER.info("Geteilten Wegpunkt {} hinzugefuegt", shared.name());
	}
}
