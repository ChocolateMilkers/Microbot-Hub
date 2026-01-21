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
<<<<<<< Updated upstream
    default String setupInstructions() {
        return "This script uses Inventory Setups for tracking the inventory and gear to bring each trip. " +
                "Please download that plugin from RuneLite's plugin hub (not Microbot), and use the \"default\" profile. " +
                "To use that plugin, click the armor guy on the side-bar, click the eye under the default box, " +
                "and click the refresh icon to set your current inventory as the default inventory.";
=======
    String pkerSection = "pker";

    @ConfigItem(
            keyName = "enablePlayerMonitor",
            name = "Enable Player Monitor?",
            description = "Enable Player Monitor during banking and emergency scape for enhanced safety against PKers.",
            position = 0,
            section = pkerSection
    )
    default boolean enablePlayerMonitor() { return true; }

//    @ConfigItem(
//            keyName = "pkerDetectionFilter",
//            name = "Detection Filter",
//            description = "Choose who to detect as threats. Excluding friends/FC/CC is recommended for team activities.",
//            position = 0,
//            section = pkerSection
//    )
//    default PKerDetectionFilter pkerDetectionFilter() {
//        return PKerDetectionFilter.EXCLUDE_FRIENDS_FC_CC;
//    }
//
//    @ConfigItem(
//            keyName = "emergencyEscapeMethod",
//            name = "Emergency Escape Method",
//            description = "Logout + Hop: Safest, saves teleport charges (only works if not in combat)\nTeleport Only: Works in combat, uses teleport charge",
//            position = 1,
//            section = pkerSection
//    )
//    default EmergencyEscapeMethod emergencyEscapeMethod() {
//        return EmergencyEscapeMethod.LOGOUT_AND_HOP;
//    }
//
    @ConfigItem(
            keyName = "teleportMethod",
            name = "Teleport Method",
            description = "Choose which teleport item to use for banking and emergency escapes.\nGlory: Teleports to Edgeville bank\nRing of Dueling: Teleports to Ferox Enclave",
            position = 2,
            section = pkerSection
    )
    default TeleportMethod teleportMethod() {
        return TeleportMethod.GLORY_EDGEVILLE;
    }

    @ConfigSection(
            name = "Transportation",
            description = "Configure how to travel to the zombie pirates",
            position = 1,
            closedByDefault = false
    )
    String transportSection = "transport";

    @ConfigItem(
            keyName = "transportMethod",
            name = "Transport Method",
            description = "Choose how to travel to the zombie pirates after banking.\nNote: 72 Agility shortcut is automatically used if your level is high enough.",
            position = 0,
            section = transportSection
    )
    default TransportMethod transportMethod() {
        return TransportMethod.LUMBERYARD_TELEPORT;
    }

    @ConfigSection(
            name = "Combat & Survival",
            description = "Configure combat and survival settings",
            position = 2,
            closedByDefault = false
    )
    String combatSection = "combat";

    @ConfigItem(
            keyName = "enableOverheal",
            name = "Enable Overheal Mechanic",
            description = "Automatically eat Blighted Anglerfish to overheal while in the wilderness.\n" +
                    "Threshold is set to your Hitpoints level + 2 (e.g., 24 HP = eat at 26 or below).\n" +
                    "This provides extra survivability against PKers by keeping HP above max.",
            position = 0,
            section = combatSection
    )
    default boolean enableOverheal() {
        return true;
    }

    @ConfigSection(
            name = "Timing & Delays",
            description = "Configure script timing and delays",
            position = 3,
            closedByDefault = true
    )
    String timingSection = "timing";

    @ConfigItem(
            keyName = "chestOpenDelayMin",
            name = "Chest Open Delay (Min)",
            description = "Minimum delay in milliseconds to wait for chest opening confirmation.\nChest typically takes 10-12 seconds to open.",
            position = 0,
            section = timingSection
    )
    @Range(min = 1000, max = 20000)
    default int chestOpenDelayMin() {
        return 10000;
    }

    @ConfigItem(
            keyName = "chestOpenDelayMax",
            name = "Chest Open Delay (Max)",
            description = "Maximum delay in milliseconds to wait for chest opening confirmation.\nChest typically takes 10-12 seconds to open.",
            position = 1,
            section = timingSection
    )
    @Range(min = 2000, max = 20000)
    default int chestOpenDelayMax() {
        return 13000;
>>>>>>> Stashed changes
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
