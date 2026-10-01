package dev.zymekoh.kohsanchors;

import dev.zymekoh.kohsanchors.bridge.BridgeClient;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KoHsAnchorsClient implements ClientModInitializer {
    public static final String MOD_ID = "kohs_anchors";
    public static final Logger LOGGER = LoggerFactory.getLogger("KoHs Anchor's");

    @Override
    public void onInitializeClient() {
        AnchorsConfig.load();
        BridgeClient.init();
    }
}
