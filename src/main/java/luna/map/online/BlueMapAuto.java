package luna.map.online;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import luna.map.LunaMap;
import luna.map.data.Dimensions;
import luna.map.data.MapSession;
import net.minecraft.client.Minecraft;

/**
 * Ordnet die BlueMap-Karten automatisch den Dimensionen zu: erst nach Namen ("nether", "end" …),
 * dann sicher ueber die Live-Daten von BlueMap (auf welcher Karte steht der eigene Spieler gerade?).
 */
public final class BlueMapAuto {
	public static final String OVERWORLD = Dimensions.OVERWORLD;
	public static final String NETHER = Dimensions.NETHER;
	public static final String END = Dimensions.END;

	/** Erste Pruefung kurz nach dem Betreten, dann noch ein paar Versuche, falls BlueMap hinterherhinkt. */
	private static final int FIRST_DELAY = 60;
	private static final int RETRY_DELAY = 200;
	private static final int MAX_ATTEMPTS = 4;

	private static String checkedKey;
	private static int delay;
	private static int attempts;
	private static volatile boolean running;

	private BlueMapAuto() {
	}

	/** Passende Karte zu einer Dimension nach Namen raten. null = keine passende. */
	public static String guess(String dim, Iterable<String> ids, Map<String, String> names) {
		String want = dim.equals(NETHER) ? "nether" : dim.equals(END) ? "end" : dim.equals(OVERWORLD) ? ""
				: dim.substring(dim.indexOf(':') + 1).toLowerCase(Locale.ROOT);
		String best = null;
		int bestRank = -1;
		for (String id : ids) {
			String text = (id + " " + names.getOrDefault(id, id)).toLowerCase(Locale.ROOT);
			boolean nether = text.contains("nether") || text.contains("hell");
			boolean end = text.matches(".*\\bend\\b.*") || text.contains("the_end") || text.contains("_end");
			boolean fits = want.isEmpty() ? !nether && !end
					: want.equals("nether") ? nether : want.equals("end") ? end : text.contains(want);
			if (!fits) {
				continue;
			}
			// Oberwelt: "overworld"/"world" vor allem anderen, sonst landet z. B. eine Datapack-Welt "parallel" dort
			int rank = !want.isEmpty() ? 0 : text.contains("overworld") ? 2 : text.contains("world") ? 1 : 0;
			// bevorzugt die "normale" Karte, nicht z. B. eine 2D- oder Lowres-Variante
			if (rank > bestRank || rank == bestRank && id.length() < best.length()) {
				best = id;
				bestRank = rank;
			}
		}
		return best;
	}

	/** Beim Verbinden: Oberwelt, Nether, End und die aktuelle Dimension sofort zuordnen. */
	public static void assignAll(BlueMapLink link) {
		List<String> dims = new ArrayList<>(List.of(OVERWORLD, NETHER, END));
		String current = MapSession.dimension();
		if (current != null && !dims.contains(current)) {
			dims.add(current);
		}
		for (String dim : dims) {
			String id = guess(dim, link.names.keySet(), link.names);
			if (id != null) {
				link.maps.put(dim, id);
			}
		}
		checkedKey = null;
	}

	/** Jeden Tick: neue Dimension zuordnen und kurz danach live pruefen. */
	public static void tick(Minecraft mc) {
		BlueMapLink link = BlueMapSource.link();
		String dim = MapSession.dimension();
		if (link == null || dim == null || mc.player == null || link.names.isEmpty()) {
			checkedKey = null;
			return;
		}
		String key = MapSession.world() + "|" + dim + "|" + System.identityHashCode(link);
		if (!key.equals(checkedKey)) {
			checkedKey = key;
			delay = FIRST_DELAY;
			attempts = 0;
			if (!link.maps.containsKey(dim)) {
				String id = guess(dim, link.names.keySet(), link.names);
				if (id != null) {
					link.maps.put(dim, id);
					BlueMapSource.saveLink();
				}
			}
		}
		if (delay > 0 && --delay == 0 && !running) {
			detect(link, dim, mc.player.getUUID(), key);
		}
	}

	private static void detect(BlueMapLink link, String dim, UUID player, String key) {
		running = true;
		attempts++;
		List<String> ids = new ArrayList<>(link.names.keySet());
		BlueMapSource.EXECUTOR.execute(() -> {
			try {
				List<String> found = BlueMapClient.mapsWithPlayer(link.url, link.liveRoot, ids, player);
				MapSession.runOnMainThread(() -> apply(link, dim, key, found));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				running = false;
			}
		});
	}

	private static void apply(BlueMapLink link, String dim, String key, List<String> found) {
		if (!key.equals(checkedKey) || BlueMapSource.link() != link) {
			return;
		}
		if (found.isEmpty()) {
			// Live-Daten aus, Spieler versteckt oder BlueMap hat die Position noch nicht: spaeter nochmal
			if (attempts < MAX_ATTEMPTS) {
				delay = RETRY_DELAY;
			}
			return;
		}
		String current = link.maps.get(dim);
		String id = found.contains(current) ? current : guess(dim, found, link.names);
		if (id == null) {
			id = found.get(0);
			for (String candidate : found) {
				if (candidate.length() < id.length()) {
					id = candidate;
				}
			}
		}
		if (id.equals(link.confirmed.get(dim)) && id.equals(current)) {
			return;
		}
		link.maps.put(dim, id);
		link.confirmed.put(dim, id);
		// dieselbe Karte war fuer eine andere Dimension nur geraten -> dort wieder raus
		for (Iterator<Map.Entry<String, String>> it = link.maps.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, String> entry = it.next();
			if (!entry.getKey().equals(dim) && found.contains(entry.getValue()) && !link.confirmed.containsKey(entry.getKey())) {
				it.remove();
			}
		}
		BlueMapSource.saveLink();
		if (!id.equals(current)) {
			LunaMap.LOGGER.info("BlueMap: {} gehoert zur Karte {} (live erkannt)", dim, id);
		}
	}
}
