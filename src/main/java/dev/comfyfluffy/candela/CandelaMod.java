package dev.comfyfluffy.candela;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CandelaMod implements ModInitializer {
	public static final String MOD_ID = "candela";
	public static final Logger LOGGER = LoggerFactory.getLogger("Candela");

	@Override
	public void onInitialize() {
		// Register every setting (applying TOML file values) and write a default config on first run.
		CandelaConfig.ensureRegistered();
		CandelaConfig.saveIfMissing();
		LOGGER.info("Candela initialized (common); config: {}", CandelaConfig.configPath());
	}
}
