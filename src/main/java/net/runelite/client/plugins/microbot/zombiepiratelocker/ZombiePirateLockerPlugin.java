package net.runelite.client.plugins.microbot.zombiepiratelocker;

import com.google.inject.Provides;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginConstants.SAM + "Zombie Pirate Locker",
        description = "Opens Zombie Pirate's Lockers with PK protection",
        tags = {"wilderness", "zombie", "locker", "microbot"},
        version = ZombiePirateLockerPlugin.version,
        minClientVersion = "2.0.13",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
public class ZombiePirateLockerPlugin extends Plugin {

    static final String version = "1.0.1";

    @Inject
    private ZombiePirateLockerConfig config;

    @Inject
    private ZombiePirateLockerScript script;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ZombiePirateLockerOverlay overlay;

    @Provides
    ZombiePirateLockerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ZombiePirateLockerConfig.class);
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage) {
        if (chatMessage.getType() == ChatMessageType.GAMEMESSAGE) {
            String message = chatMessage.getMessage();
            if (message.contains("You've opened the Zombie")) {
                ZombiePirateLockerScript.chestOpened = true;
            }
        }
    }

    @Override
    protected void startUp() throws AWTException {
        overlayManager.add(overlay);
        script.run(config);
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        overlayManager.remove(overlay);
    }
}