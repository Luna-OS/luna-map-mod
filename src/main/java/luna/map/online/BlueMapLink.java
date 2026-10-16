package luna.map.online;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import luna.map.LunaMap;

/** BlueMap-Adresse einer Welt bzw. eines Servers, gespeichert in lunamap/<welt>/bluemap.json. */
public class BlueMapLink {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public String url;
	/** Dimension -> BlueMap-Karten-ID. */
	public Map<String, String> maps = new HashMap<>();
	/** BlueMap-Karten-ID -> Kantenlaenge einer Lowres-Kachel in Bloecken. */
	public Map<String, Integer> tileSizes = new HashMap<>();

	public static BlueMapLink load(Path folder) {
		Path file = folder.resolve("bluemap.json");
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				BlueMapLink link = GSON.fromJson(reader, BlueMapLink.class);
				if (link != null && link.url != null) {
					if (link.maps == null) {
						link.maps = new HashMap<>();
					}
					if (link.tileSizes == null) {
						link.tileSizes = new HashMap<>();
					}
					return link;
				}
			} catch (IOException | JsonParseException e) {
				LunaMap.LOGGER.warn("Konnte {} nicht lesen", file, e);
			}
		}
		return null;
	}

	public void save(Path folder) {
		try {
			Files.createDirectories(folder);
			try (Writer writer = Files.newBufferedWriter(folder.resolve("bluemap.json"), StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			LunaMap.LOGGER.warn("Konnte BlueMap-Link nicht speichern", e);
		}
	}

	public static void delete(Path folder) {
		try {
			Files.deleteIfExists(folder.resolve("bluemap.json"));
		} catch (IOException e) {
			LunaMap.LOGGER.warn("Konnte BlueMap-Link nicht loeschen", e);
		}
	}

	/** Macht aus einem kopierten Browser-Link die Basisadresse, z. B. "https://map.server.de/". */
	public static String normalize(String input) {
		String url = input.strip();
		int hash = url.indexOf('#');
		if (hash >= 0) {
			url = url.substring(0, hash);
		}
		int query = url.indexOf('?');
		if (query >= 0) {
			url = url.substring(0, query);
		}
		if (url.endsWith("index.html")) {
			url = url.substring(0, url.length() - "index.html".length());
		}
		// "https:///", "https//" oder ganz ohne Schema -> sauberes "https://"
		java.util.regex.Matcher scheme = java.util.regex.Pattern.compile("^(https?):?/*", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url);
		if (scheme.find()) {
			url = scheme.group(1).toLowerCase(java.util.Locale.ROOT) + "://" + url.substring(scheme.end());
		} else {
			url = "https://" + url.replaceFirst("^/+", "");
		}
		if (!url.endsWith("/")) {
			url += "/";
		}
		return url;
	}
}
