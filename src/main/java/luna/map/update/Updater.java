package luna.map.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import luna.map.LunaConfig;
import luna.map.LunaMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.network.chat.Component;

/**
 * Sucht beim Start nach einem neueren Release auf GitHub (Luna-OS/luna-map-mod), laedt das Jar
 * herunter und tauscht es beim Beenden von Minecraft aus. Der Tausch passiert nach dem Beenden,
 * weil Windows ein laufendes Jar nicht loeschen laesst.
 */
public final class Updater {
	private static final String LATEST = "https://api.github.com/repos/Luna-OS/luna-map-mod/releases/latest";
	/** Nur Jars fuer diese Minecraft-Version nehmen. */
	private static final String MC_SUFFIX = "+26.3.jar";

	private static volatile String status;
	private static volatile String chatMessage;
	private static volatile boolean started;

	private Updater() {
	}

	public static String currentVersion() {
		return FabricLoader.getInstance().getModContainer(LunaMap.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	/** Text fuer den Einstellungs-Screen, oder null. */
	public static Component statusText() {
		String s = status;
		return s == null ? null : Component.translatable("lunamap.update." + s.split(":", 2)[0], s.contains(":") ? s.split(":", 2)[1] : "");
	}

	/** Einmalige Chat-Nachricht, sobald man in einer Welt ist. */
	public static String takeChatMessage() {
		String message = chatMessage;
		chatMessage = null;
		return message;
	}

	public static void start() {
		if (started || !LunaConfig.get().autoUpdate) {
			return;
		}
		started = true;
		Thread thread = new Thread(Updater::check, "Luna Map Updater");
		thread.setDaemon(true);
		thread.start();
	}

	private static void check() {
		try {
			Path ownJar = ownJar();
			if (ownJar == null) {
				return; // Entwicklungsumgebung o. Ae.
			}
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
					.followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(LATEST))
					.timeout(Duration.ofSeconds(20))
					.header("Accept", "application/vnd.github+json")
					.header("User-Agent", "LunaMap-Updater").GET().build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return;
			}
			JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
			String latest = release.get("tag_name").getAsString().replaceFirst("^v", "");
			if (compare(latest, currentVersion()) <= 0) {
				status = "current:" + currentVersion();
				return;
			}
			String url = null;
			String name = null;
			JsonArray assets = release.getAsJsonArray("assets");
			for (JsonElement element : assets) {
				JsonObject asset = element.getAsJsonObject();
				String assetName = asset.get("name").getAsString();
				if (assetName.startsWith("luna-map-") && assetName.endsWith(MC_SUFFIX)) {
					url = asset.get("browser_download_url").getAsString();
					name = assetName;
				}
			}
			if (url == null || !url.startsWith("https://github.com/Luna-OS/luna-map-mod/releases/download/")) {
				return;
			}
			Path mods = ownJar.getParent();
			Path download = mods.resolve(name + ".download");
			HttpResponse<InputStream> jar = http.send(HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofMinutes(2)).header("User-Agent", "LunaMap-Updater").GET().build(),
					HttpResponse.BodyHandlers.ofInputStream());
			if (jar.statusCode() != 200) {
				return;
			}
			try (InputStream in = jar.body()) {
				Files.copy(in, download, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!isLunaMapJar(download)) {
				Files.deleteIfExists(download);
				return;
			}
			// ".update" wird von Fabric nicht geladen; beim Beenden wird es zum richtigen Jar
			Path update = mods.resolve(name + ".update");
			Files.move(download, update, StandardCopyOption.REPLACE_EXISTING);
			Path target = mods.resolve(name);
			Runtime.getRuntime().addShutdownHook(new Thread(() -> install(ownJar, update, target), "Luna Map Update-Installation"));
			status = "ready:" + latest;
			chatMessage = latest;
			LunaMap.LOGGER.info("Luna Map {} heruntergeladen, wird beim Beenden installiert", latest);
		} catch (IOException | RuntimeException e) {
			LunaMap.LOGGER.warn("Update-Pruefung fehlgeschlagen: {}", e.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Pfad zum eigenen Jar im mods-Ordner, sonst null. */
	private static Path ownJar() {
		ModContainer self = FabricLoader.getInstance().getModContainer(LunaMap.MOD_ID).orElse(null);
		if (self == null || self.getOrigin().getKind() != ModOrigin.Kind.PATH) {
			return null;
		}
		for (Path path : self.getOrigin().getPaths()) {
			if (path.getFileName().toString().endsWith(".jar") && Files.isRegularFile(path)) {
				return path.toAbsolutePath();
			}
		}
		return null;
	}

	private static boolean isLunaMapJar(Path file) {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals("fabric.mod.json")) {
					String json = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
					return JsonParser.parseString(json).getAsJsonObject().get("id").getAsString().equals(LunaMap.MOD_ID);
				}
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}
		return false;
	}

	/** Startet ein kleines Skript, das nach dem Beenden das alte Jar loescht und das neue einsetzt. */
	private static void install(Path oldJar, Path update, Path newJar) {
		try {
			boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
			Path script = Files.createTempFile("lunamap-update", windows ? ".bat" : ".sh");
			String text;
			if (windows) {
				text = "@echo off\r\n"
						+ "set /a n=0\r\n"
						+ ":loop\r\n"
						+ "ping -n 3 127.0.0.1 >nul\r\n"
						+ "del /f /q \"" + oldJar + "\" >nul 2>&1\r\n"
						+ "set /a n+=1\r\n"
						+ "if exist \"" + oldJar + "\" if %n% lss 90 goto loop\r\n"
						+ "if not exist \"" + oldJar + "\" move /y \"" + update + "\" \"" + newJar + "\" >nul\r\n"
						+ "del \"%~f0\"\r\n";
			} else {
				text = "#!/bin/sh\n"
						+ "sleep 3\n"
						+ "rm -f '" + oldJar + "' && mv -f '" + update + "' '" + newJar + "'\n"
						+ "rm -f \"$0\"\n";
			}
			Files.writeString(script, text, StandardCharsets.UTF_8);
			ProcessBuilder builder = windows
					? new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", script.toString())
					: new ProcessBuilder("sh", script.toString());
			builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		} catch (IOException e) {
			LunaMap.LOGGER.warn("Update konnte nicht installiert werden", e);
		}
	}

	/** Vergleicht "2.0.1" mit "2.0.0+26.3" (alles ab "+" wird ignoriert). */
	static int compare(String a, String b) {
		String[] pa = a.split("\\+")[0].split("\\.");
		String[] pb = b.split("\\+")[0].split("\\.");
		for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
			int x = i < pa.length ? parse(pa[i]) : 0;
			int y = i < pb.length ? parse(pb[i]) : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		return 0;
	}

	private static int parse(String part) {
		try {
			return Integer.parseInt(part.replaceAll("[^0-9].*$", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
