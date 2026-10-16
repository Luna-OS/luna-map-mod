package luna.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Schaltet in XaeroLib "allow_internet_access" ab, bevor Xaero seine Konfiguration liest.
 * Damit laedt Xaero keine Online-Widgets mehr (z. B. den Patreon-Banner in den Einstellungen).
 */
public class LunaPreLaunch implements PreLaunchEntrypoint {
	private static final String KEY = "allow_internet_access";

	@Override
	public void onPreLaunch() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("xaero/lib/common.cfg");
		try {
			List<String> lines = Files.exists(file)
					? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
					: new ArrayList<>();
			List<String> updated = new ArrayList<>();
			boolean found = false;
			for (String line : lines) {
				if (line.trim().startsWith(KEY)) {
					found = true;
					line = KEY + " = false";
				}
				updated.add(line);
			}
			if (!found) {
				updated.add(KEY + " = false");
			}
			if (!updated.equals(lines)) {
				Files.createDirectories(file.getParent());
				Files.write(file, updated, StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			LunaMap.LOGGER.warn("Konnte {} nicht anpassen", file, e);
		}
	}
}
