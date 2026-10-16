package luna.map.online;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Spricht mit dem Webserver von BlueMap (nur lesend: settings.json und Lowres-Kacheln). */
public final class BlueMapClient {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/** Eine Karte auf dem BlueMap-Server. */
	public record MapInfo(String id, String name, int tileSize) {
	}

	private BlueMapClient() {
	}

	static HttpRequest request(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", "LunaMap (Minecraft-Mod)")
				.GET().build();
	}

	/** Ergebnis der Verbindung: die Adresse, die funktioniert hat, und ihre Karten. */
	public record Connection(String baseUrl, List<MapInfo> maps, String liveRoot) {
	}

	/**
	 * Verbindet sich mit einer BlueMap. Klappt https nicht, wird http versucht (der eingebaute
	 * Webserver von BlueMap laeuft oft ohne https, z. B. http://server:8100/).
	 */
	public static CompletableFuture<Connection> connect(String baseUrl) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return connection(baseUrl);
			} catch (RuntimeException first) {
				if (baseUrl.startsWith("https://")) {
					String http = "http://" + baseUrl.substring("https://".length());
					try {
						return connection(http);
					} catch (RuntimeException ignored) {
						// erste Fehlermeldung ist meist aussagekraeftiger
					}
				}
				throw first;
			}
		});
	}

	private static Connection connection(String baseUrl) {
		{
			try {
				JsonObject settings = getJson(baseUrl + "settings.json");
				String liveRoot = settings.has("liveDataRoot") ? settings.get("liveDataRoot").getAsString() : "maps";
				String root = settings.has("mapDataRoot") ? settings.get("mapDataRoot").getAsString() : "maps";
				List<MapInfo> maps = new ArrayList<>();
				JsonArray ids = settings.getAsJsonArray("maps");
				if (ids == null) {
					throw new IOException("keine Karten in settings.json");
				}
				for (JsonElement element : ids) {
					String id = element.getAsString();
					String name = id;
					int tileSize = 500;
					try {
						JsonObject map = getJson(baseUrl + root + "/" + id + "/settings.json");
						if (map.has("name")) {
							name = map.get("name").getAsString();
						}
						JsonObject lowres = map.getAsJsonObject("lowres");
						if (lowres != null && lowres.has("tileSize")) {
							tileSize = lowres.getAsJsonArray("tileSize").get(0).getAsInt();
						}
					} catch (IOException | RuntimeException e) {
						// Karte ohne eigene settings.json: Standardwerte
					}
					maps.add(new MapInfo(id, name, tileSize));
				}
				return new Connection(baseUrl, maps, liveRoot);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			}
		}
	}

	private static JsonObject getJson(String url) throws IOException, InterruptedException {
		HttpResponse<String> response = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " fuer " + url);
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	static byte[] getBytes(String url) throws IOException, InterruptedException {
		HttpResponse<byte[]> response = HTTP.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() == 404) {
			return null;
		}
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode());
		}
		return response.body();
	}

	/** Antwort einer bedingten Abfrage: body == null bei 404, unchanged bei 304. */
	record Fetched(byte[] body, boolean unchanged, String etag, String lastModified) {
	}

	/**
	 * Holt eine Datei nur, wenn sie sich geaendert hat (sofern der Server ETag/Last-Modified schickt;
	 * sonst kommt sie immer und der Aufrufer vergleicht den Inhalt selbst).
	 */
	static Fetched getIfChanged(String url, String etag, String lastModified) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", "LunaMap (Minecraft-Mod)")
				.header("Cache-Control", "no-cache");
		if (etag != null) {
			builder.header("If-None-Match", etag);
		}
		if (lastModified != null) {
			builder.header("If-Modified-Since", lastModified);
		}
		HttpResponse<byte[]> response = HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		String newEtag = response.headers().firstValue("ETag").orElse(null);
		String newModified = response.headers().firstValue("Last-Modified").orElse(null);
		return switch (response.statusCode()) {
			case 304 -> new Fetched(null, true, etag, lastModified);
			case 404 -> new Fetched(null, false, null, null);
			case 200 -> new Fetched(response.body(), false, newEtag, newModified);
			default -> throw new IOException("HTTP " + response.statusCode());
		};
	}

	/** Eigene Live-Position: Liste der Karten-IDs, auf deren Welt der Spieler gerade steht. */
	static List<String> mapsWithPlayer(String baseUrl, String liveRoot, Iterable<String> mapIds, java.util.UUID player)
			throws InterruptedException {
		String want = player.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
		List<String> found = new ArrayList<>();
		for (String id : mapIds) {
			try {
				JsonObject live = getJson(baseUrl + liveRoot + "/" + id + "/live/players.json");
				JsonArray players = live.getAsJsonArray("players");
				if (players == null) {
					continue;
				}
				for (JsonElement element : players) {
					JsonObject p = element.getAsJsonObject();
					String uuid = p.has("uuid") ? p.get("uuid").getAsString().replace("-", "").toLowerCase(java.util.Locale.ROOT) : "";
					boolean foreign = p.has("foreign") && p.get("foreign").getAsBoolean();
					if (uuid.equals(want) && !foreign) {
						found.add(id);
						break;
					}
				}
			} catch (IOException | RuntimeException e) {
				// keine Live-Daten fuer diese Karte
			}
		}
		return found;
	}

	/** BlueMap legt Kacheln wie x1/2/z-3 ab (jede Ziffer ein Ordner). */
	static String tilePath(int x, int z) {
		return "x" + split(x) + "z" + split(z).substring(0, split(z).length() - 1);
	}

	private static String split(int number) {
		StringBuilder path = new StringBuilder();
		if (number < 0) {
			path.append('-');
		}
		for (char c : Integer.toString(Math.abs(number)).toCharArray()) {
			path.append(c).append('/');
		}
		return path.toString();
	}
}
