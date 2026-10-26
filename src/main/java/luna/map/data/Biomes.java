package luna.map.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;

/**
 * Biome werden in den Karten als kurze Nummern gespeichert (0 = unbekannt). Die Nummern gelten nur, solange das Spiel
 * laeuft; in den Dateien steht pro Region eine eigene Liste der Biom-Namen ("minecraft:plains").
 */
public final class Biomes {
	public static final short UNKNOWN = 0;

	private static final Map<String, Short> IDS = new HashMap<>();
	private static final List<String> KEYS = new ArrayList<>(List.of(""));
	private static final Map<Holder<Biome>, Short> HOLDERS = new IdentityHashMap<>();
	private static final Map<String, Component> NAMES = new HashMap<>();

	private Biomes() {
	}

	/** Nummer fuer einen Biom-Namen wie "minecraft:plains" (wird bei Bedarf vergeben). */
	public static synchronized short id(String key) {
		if (key == null || key.isEmpty()) {
			return UNKNOWN;
		}
		if (key.indexOf(':') < 0) {
			key = "minecraft:" + key; // aeltere Xaero-Dateien ohne Namensraum
		}
		Short id = IDS.get(key);
		if (id == null) {
			if (KEYS.size() >= Short.MAX_VALUE) {
				return UNKNOWN;
			}
			id = (short) KEYS.size();
			KEYS.add(key);
			IDS.put(key, id);
		}
		return id;
	}

	/** Nummer des Bioms aus der Welt. */
	public static synchronized short id(Holder<Biome> holder) {
		Short id = HOLDERS.get(holder);
		if (id == null) {
			id = id(holder.unwrapKey().map(k -> k.identifier().toString()).orElse(null));
			HOLDERS.put(holder, id);
		}
		return id;
	}

	/** Biom-Name zur Nummer, oder null. */
	public static synchronized String key(short id) {
		return id <= 0 || id >= KEYS.size() ? null : KEYS.get(id);
	}

	/** Biom an einer Stelle der geladenen Welt. */
	public static String at(ClientLevel level, int x, int y, int z) {
		return key(id(level.getBiome(new BlockPos(x, y, z))));
	}

	/** Anzeigename in der Spielsprache ("Ebene"); Biome aus Mods ohne Uebersetzung lesbar aus dem Namen gebaut. */
	public static Component name(String key) {
		if (key == null) {
			return null;
		}
		synchronized (NAMES) {
			return NAMES.computeIfAbsent(key, k -> {
				Identifier id = Identifier.tryParse(k);
				if (id == null) {
					return Component.literal(k);
				}
				return Component.translatableWithFallback(id.toLanguageKey("biome"), pretty(id.getPath()));
			});
		}
	}

	/** "windswept_gravelly_hills" -> "Windswept Gravelly Hills". */
	private static String pretty(String path) {
		String last = path.substring(path.lastIndexOf('/') + 1);
		StringBuilder out = new StringBuilder();
		for (String word : last.split("_")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return out.toString();
	}
}
