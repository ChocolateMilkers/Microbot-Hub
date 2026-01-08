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
}
