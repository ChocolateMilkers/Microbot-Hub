package net.runelite.client.plugins.microbot.zombiepiratelocker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("ZombiePirateLocker")
public interface ZombiePirateLockerConfig extends Config {

    @ConfigSection(
            name = "Instructions",
            description = "How to set up this script",
            position = 0
    )
    String instructionsSection = "instructions";

    @ConfigItem(
            keyName = "setupInstructions",
            name = "Setup Instructions",
            description = "",
            position = 0,
            section = instructionsSection
    )
    default String setupInstructions() {
        return "This script uses Inventory Setups for tracking the inventory and gear to bring each trip. " +
                "Please download that plugin from RuneLite's plugin hub (not Microbot), and use the \"default\" profile. " +
                "To use that plugin, click the armor guy on the side-bar, click the eye under the default box, " +
                "and click the refresh icon to set your current inventory as the default inventory.";
    }

    @ConfigSection(
            name = "Discord Notifications",
            description = "Discord webhook settings for PKer alerts",
            position = 1
    )
    String discordSection = "discord";

    @ConfigItem(
            keyName = "discordWebhook",
            name = "Discord Webhook URL",
            description = "Discord webhook URL to send PKer notifications (leave empty to disable)",
            position = 0,
            section = discordSection
    )
    default String discordWebhook() {
        return "";
    }

    @ConfigItem(
            keyName = "enableDiscordNotifications",
            name = "Enable Discord Notifications",
            description = "Send Discord notifications when PKers are detected",
            position = 1,
            section = discordSection
    )
    default boolean enableDiscordNotifications() {
        return false;
    }
}
