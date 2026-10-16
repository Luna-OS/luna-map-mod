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
	public record Connection(String baseUrl, List<MapInfo> maps) {
	}

	/**
	 * Verbindet sich mit einer BlueMap. Klappt https nicht, wird http versucht (der eingebaute
	 * Webserver von BlueMap laeuft oft ohne https, z. B. http://server:8100/).
	 */
	public static CompletableFuture<Connection> connect(String baseUrl) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return new Connection(baseUrl, maps(baseUrl));
			} catch (RuntimeException first) {
				if (baseUrl.startsWith("https://")) {
					String http = "http://" + baseUrl.substring("https://".length());
					try {
						return new Connection(http, maps(http));
					} catch (RuntimeException ignored) {
						// erste Fehlermeldung ist meist aussagekraeftiger
					}
				}
				throw first;
			}
		});
	}

	private static List<MapInfo> maps(String baseUrl) {
		{
			try {
				JsonObject settings = getJson(baseUrl + "settings.json");
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
				return maps;
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
