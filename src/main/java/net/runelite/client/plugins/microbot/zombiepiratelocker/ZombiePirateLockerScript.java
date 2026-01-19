package net.runelite.client.plugins.microbot.zombiepiratelocker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.GameState;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.coords.Rs2WorldArea;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.Rs2PlayerModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.api.widgets.Widget;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class ZombiePirateLockerScript extends Script {

    // ==== CONSTANTS ====
    private static final int ZOMBIE_KEY = 29449;
    private static final int[] GLORY_IDS = {1706,1708,1710,1712,11976,11978}; // Charged glories
    private static final int UNCHARGED_GLORY = 1704; // Uncharged Amulet of glory
    private static final int[] RING_OF_DUELING_IDS = {2552,2554,2556,2558,2560,2562,2564,2566}; // Ring of dueling(8) to (1)
    private static final int UNCHARGED_RING_OF_DUELING = -1; // Ring of dueling doesn't have uncharged variant (crumbles to dust)
    private static final int LUMBERYARD_TELE = 12642;
    private static final int BLIGHTED_ANGLERFISH = 28309; // Overheal food

    // Transport item IDs
    private static final int[] BURNING_AMULET_IDS = {21166, 21168, 21170, 21172, 21174}; // Burning amulet(5) to (1)
    private static final int RING_OF_ELEMENTS = 26815; // Ring of the elements (charged)

    private static final int CHEST_CLOSED = 53222;

    // HP thresholds for overheal mechanic
    private static final int BASE_HP = 24; // Base hitpoints
    private static final int OVERHEAL_HP = 28; // Overhealed to this amount (24 + 4)
    private static final int OVERHEAL_MAINTAIN_THRESHOLD = 26; // Eat when HP drops to this

    // Area around the chest location for random tile selection
    private static final Rs2WorldArea CHEST_AREA =
            new Rs2WorldArea(new WorldPoint(3368, 3624, 0), 6, 3);

    // Transport destination points
    private static final WorldPoint CHAOS_TEMPLE_AREA = new WorldPoint(3236, 3639, 0); // Near Chaos Temple after burning amulet
    private static final WorldPoint AGILITY_SHORTCUT_WEST = new WorldPoint(3238, 3625, 0); // West side of 72 agility shortcut
    private static final WorldPoint OBELISK_LVL18_AREA = new WorldPoint(3219, 3656, 0); // Level 18 obelisk area
    private static final WorldPoint FEROX_ENCLAVE_EXIT = new WorldPoint(3155, 3635, 0); // Ferox Enclave east exit
    private static final WorldPoint EARTH_ALTAR_AREA = new WorldPoint(3304, 3476, 0); // Earth Altar area (Ring of Elements)
    private static final WorldPoint VARROCK_BALLOON_AREA = new WorldPoint(3296, 3480, 0); // Varrock balloon landing

    // Wilderness level widget - shows "Level: --" in safe zones, "Level: X<br>Y-Z" in PvP areas
    // Widget ID 5898290 = Group 90, child 50
    // Ring of Dueling teleport to Ferox Enclave arrives at ~(3150, 3635), bank is at ~(3132, 3629) - about 18 tiles
    private static final int WILDERNESS_LEVEL_WIDGET_ID = 5898290;

    // ==== STATS ====
    public static int keysUsed = 0;
    public static long startTime;
    public static volatile boolean chestOpened = false;

    // ==== INTERNAL ====
    private long lastHopTime = 0;
    private static final long HOP_COOLDOWN = 10_000; // 30 seconds
    private long lastBankTime = 0;
    private static final long BANK_COOLDOWN = 5000; // 5 seconds between banking attempts
    private long lastEmergencyTeleport = 0;
    private static final long EMERGENCY_TELEPORT_COOLDOWN = 1_00; // 1 seconds
    private boolean escapedFromPker = false; // Flag to track PKer-triggered emergency teleports
    private boolean attackDetected = false; // Flag to prevent log spam
    private long lastDiscordWebhookSent = 0;
    private static final long DISCORD_WEBHOOK_COOLDOWN = 30_000; // 60 seconds between webhook messages
    private static final long CHEST_INTERACTION_COOLDOWN = 2000; // 2 seconds between chest click attempts

    private Rs2InventorySetup inventorySetup;
    private long lastChestInteraction = 0; // Track when we last clicked the chest
    private ZombiePirateLockerConfig config;
    private static long lastStartTime = 0;
    private static final long RESTART_COOLDOWN = 2000; // 2 seconds between restarts

    public boolean run(ZombiePirateLockerConfig config) {
        // Prevent rapid restart loops
        long timeSinceLastStart = System.currentTimeMillis() - lastStartTime;
        if (timeSinceLastStart < RESTART_COOLDOWN) {
            log.warn("Script restart attempted too quickly ({} ms since last start), waiting...", timeSinceLastStart);
            try {
                Thread.sleep(RESTART_COOLDOWN - timeSinceLastStart);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        lastStartTime = System.currentTimeMillis();

        log.info("Starting Zombie Pirate Locker script");
        this.config = config;
        startTime = System.currentTimeMillis();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;

                // Initialize inventory setup on first run (on client thread context)
                if (inventorySetup == null) {
                    try {
                        inventorySetup = new Rs2InventorySetup("default", mainScheduledFuture);
                        log.info("Inventory setup initialized");
                    } catch (Exception e) {
                        log.error("Failed to initialize inventory setup: {}", e.getMessage(), e);
                        return;
                    }
                }

                // 1. PKER DETECTION (HIGHEST PRIORITY)
                List<Rs2PlayerModel> threats = detectPker();
                if (!threats.isEmpty()) {
                    // Cancel any active walking immediately
                    try {
                        Rs2Walker.setTarget(null);
                    } catch (Exception e) {
                        // Silently ignore - walker may not be active
                    }
                    handlePkerDetected(threats);
                    return;
                }

                // 2. Overheal mechanic - maintain HP above base level while in wilderness (if enabled)
                if (config.enableOverheal() && Rs2Pvp.isInWilderness()) {
                    int currentHp = Rs2Player.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS);
                    int baseHpLevel = Rs2Player.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS);
                    // Threshold is base HP level + 2 (e.g., 24 HP = eat at 26 or below)
                    int threshold = baseHpLevel + 2;

                    // If HP drops to threshold or below, eat blighted anglerfish to overheal
                    if (currentHp <= threshold && Rs2Inventory.hasItem(BLIGHTED_ANGLERFISH)) {
                        log.info("HP at {} (threshold: {} based on {} HP level) - consuming Blighted Anglerfish to overheal",
                            currentHp, threshold, baseHpLevel);
                        if (Rs2Inventory.interact(BLIGHTED_ANGLERFISH, "Eat")) {
                            sleepUntil(() -> Rs2Player.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS) > currentHp, Rs2Random.between(1000, 1400));
                            log.info("Overhealed to {} HP", Rs2Player.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS));
                        }
                        return;
                    }
                }

                // 3. Eat if low HP (non-overheal food)
                if (Rs2Player.getHealthPercentage() < 80) {
                    log.info("Health below 80%, attempting to eat food");
                    eatFood();
                    return;
                }

                // 3. No keys → teleport to safety if in wilderness (but not if in safe zone like Ferox)
                if (!Rs2Inventory.hasItem(ZOMBIE_KEY)) {
                    // Check if we're still in wilderness AND not in a safe zone
                    if (Rs2Player.getWorldLocation().getY() >= 3520 && !isInWildernessSafeZone()) {
                        // Still in PvP wilderness, need to teleport out (not emergency - no PKer)
                        log.warn("Out of zombie keys in wilderness, teleporting to safety");
                        teleportToSafety();
                        return;
                    }
                    // If we're safe (below wilderness OR in safe zone), fall through to banking logic
                    log.debug("Out of zombie keys, proceeding to bank (in safe area)");
                }

                // 4. Banking - bank if we're out of keys OR if we escaped from a PKer
                // (inventory setup will be checked and loaded inside handleBanking)
                boolean needsBank = !Rs2Inventory.hasItem(ZOMBIE_KEY) || escapedFromPker;
                if (needsBank) {
                    // Check if we're near a bank
                    if (Rs2Bank.isNearBank(15)) {
                        // Skip cooldown check if we escaped from PKer
                        if (!escapedFromPker && System.currentTimeMillis() - lastBankTime < BANK_COOLDOWN) {
                            log.debug("Banking on cooldown, waiting {} ms",
                                BANK_COOLDOWN - (System.currentTimeMillis() - lastBankTime));
                            return;
                        }

                        if (escapedFromPker) {
                            log.info("Escaped from PKer - forcing rebank and world hop");
                        }

                        log.debug("Near bank, handling banking operations");
                        handleBanking();
                        return;
                    }

                    // Not near bank - need to get to safety first if in wilderness
                    if (Rs2Pvp.isInWilderness() && !isInWildernessSafeZone()) {
                        // Still in PvP wilderness, MUST teleport before we can bank
                        log.warn("Still in wilderness without keys, attempting teleport to safety");
                        teleportToSafety();
                        return; // Don't fall through to chest logic!
                    }

                    // Safe (not in wilderness or in safe zone) - walk to bank
                    BankLocation targetBank = config.teleportMethod() == ZombiePirateLockerConfig.TeleportMethod.GLORY_EDGEVILLE
                        ? BankLocation.EDGEVILLE
                        : BankLocation.FEROX_ENCLAVE;
                    log.debug("Walking to {} bank (safe zone: {})", targetBank, isInWildernessSafeZone());
                    Rs2Bank.walkToBankAndUseBank(targetBank);
                    return;
                }

                // 5. Walk to chest area
                if (!CHEST_AREA.contains(Rs2Player.getWorldLocation())) {
                    log.debug("Walking to chest area");
                    int totalTiles = CHEST_AREA.toWorldPointList().size();
                    WorldPoint randomTile = CHEST_AREA.toWorldPointList().get(
                        net.runelite.client.plugins.microbot.util.math.Rs2Random.between(0, totalTiles - 1)
                    );
                    Rs2Walker.walkTo(randomTile, 0);
                    return;
                }

                // 6. Open chest
                openChest();

            } catch (Exception e) {
                // Check if this is an interruption (normal during shutdown) - exit gracefully
                if (Thread.currentThread().isInterrupted() ||
                    e.getCause() instanceof InterruptedException ||
                    (e.getMessage() != null && e.getMessage().contains("Interrupted"))) {
                    log.debug("Script thread interrupted (normal during shutdown)");
                    return; // Exit gracefully, don't log as error
                }

                log.error("Error in main script loop: {}", e.getMessage(), e);
                Microbot.log("Error in Zombie Pirate Locker: " + e.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        return true;
    }

    // ================= HELPERS =================

    /**
     * Checks if the player is in a wilderness safe zone (like Ferox Enclave).
     * Uses the wilderness level widget to determine if PvP is disabled.
     *
     * Widget behavior:
     * - Not visible: Not in wilderness at all (safe, but not a "wilderness safe zone")
     * - Shows "Level: --": In a wilderness safe zone like Ferox Enclave (PvP disabled)
     * - Shows "Level: X": In actual PvP wilderness
     *
     * @return true if in a safe zone within the wilderness (PvP disabled), or if not in wilderness at all
     */
    private boolean isInWildernessSafeZone() {
        try {
            // Check the wilderness level widget
            Widget wildernessWidget = Rs2Widget.getWidget(WILDERNESS_LEVEL_WIDGET_ID);

            // If widget doesn't exist or is hidden, we're not in the wilderness at all - safe
            if (wildernessWidget == null || wildernessWidget.isHidden()) {
                log.debug("Wilderness widget not visible - not in wilderness");
                return true; // Not in wilderness = safe
            }

            // Widget is visible, check the text
            String text = wildernessWidget.getText();
            if (text != null && text.contains("Level:")) {
                // "Level: --" = safe zone (Ferox Enclave, etc.)
                // "Level: 14<br>3-20" = PvP enabled wilderness
                if (text.contains("--")) {
                    log.debug("In wilderness safe zone (widget shows: {})", text);
                    return true;
                } else {
                    log.debug("In PvP wilderness (widget shows: {})", text);
                    return false;
                }
            }

            // Widget exists but couldn't read text - assume not safe to be cautious
            log.debug("Could not read wilderness widget text, assuming PvP wilderness");
            return false;
        } catch (Exception e) {
            log.debug("Error checking wilderness safe zone: {}", e.getMessage());
            return false; // Fail safe - assume not in safe zone
        }
    }

    /**
     * Checks if the player has a valid teleport item based on config.
     * Uses name-based matching for reliability across all charge variants.
     *
     * @return true if player has a valid teleport item in inventory
     */
    private boolean hasTeleportItem() {
        boolean useGlory = config.teleportMethod() == ZombiePirateLockerConfig.TeleportMethod.GLORY_EDGEVILLE;

        if (useGlory) {
            // Check for any Glory amulet by name (handles all charge variants)
            return Rs2Inventory.contains(item -> item != null &&
                item.getName() != null &&
                item.getName().toLowerCase().contains("amulet of glory") &&
                !item.getName().contains("(t)") && // Exclude trimmed (uncharged)
                item.getName().contains("(")); // Must have charges indicator
        } else {
            // Check for any Ring of Dueling by name (handles all charge variants)
            return Rs2Inventory.contains(item -> item != null &&
                item.getName() != null &&
                item.getName().toLowerCase().contains("ring of dueling"));
        }
    }

    private List<Rs2PlayerModel> detectPker() {
        try {
            if (!Rs2Pvp.isInWilderness()) {
                attackDetected = false; // Reset flag when not in wilderness
                return java.util.Collections.emptyList();
            }

            // Use Rs2Player helper to get players in combat level range
            var threateningPlayers = Rs2Player.getPlayersInCombatLevelRange();

            // Filter based on config setting
            List<Rs2PlayerModel> actualThreats;
            if (config.pkerDetectionFilter() == ZombiePirateLockerConfig.PKerDetectionFilter.EXCLUDE_FRIENDS_FC_CC) {
                // Filter out friends, friends chat members, and clan members
                actualThreats = threateningPlayers.stream()
                    .filter(playerModel -> {
                        var player = playerModel.getPlayer();
                        // Ignore if player is a friend, in friends chat, or in clan
                        if (player.isFriend()) {
                            log.debug("Ignoring friend: {}", player.getName());
                            return false;
                        }
                        if (player.isFriendsChatMember()) {
                            log.debug("Ignoring friends chat member: {}", player.getName());
                            return false;
                        }
                        if (player.isClanMember()) {
                            log.debug("Ignoring clan member: {}", player.getName());
                            return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
            } else {
                // Detect everyone in combat range
                actualThreats = threateningPlayers;
            }

            if (!actualThreats.isEmpty()) {
                if (!attackDetected) {
                    log.warn("Detected {} threatening player(s) in combat level range (filtered out friends/FC)", actualThreats.size());
                    attackDetected = true; // Set flag to prevent log spam
                }
                return actualThreats;
            }

            attackDetected = false; // Reset flag when no threats
            return java.util.Collections.emptyList();
        } catch (Exception e) {
            log.error("Error detecting PKer: {}", e.getMessage());
            return java.util.Collections.emptyList(); // Fail safe - don't trigger teleport on error
        }
    }

    private void handlePkerDetected(List<Rs2PlayerModel> threats) {
        log.info("⚠️ PKER DETECTED - EMERGENCY TELEPORT INITIATED");

        // Log PKer details on a separate thread (doesn't block emergency teleport)
        new Thread(() -> logPkerDetails(threats), "PKer-Detail-Logger").start();

        // Send Discord webhook notification (if enabled and not on cooldown)
        if (config.enableDiscordNotifications() && !config.discordWebhook().isEmpty()) {
            long timeSinceLastWebhook = System.currentTimeMillis() - lastDiscordWebhookSent;
            if (timeSinceLastWebhook >= DISCORD_WEBHOOK_COOLDOWN) {
                sendDiscordWebhook(threats);
                lastDiscordWebhookSent = System.currentTimeMillis();
            } else {
                log.debug("Discord webhook on cooldown ({} seconds remaining)",
                    (DISCORD_WEBHOOK_COOLDOWN - timeSinceLastWebhook) / 1000);
            }
        }

        escapedFromPker = true; // Set flag for PKer escape
        emergencyTeleport(); // TELEPORT IMMEDIATELY - NO DELAY
    }

    /**
     * Logs detailed PKer information on a separate thread
     */
    private void logPkerDetails(List<Rs2PlayerModel> threats) {
        try {
            for (Rs2PlayerModel threat : threats) {
                var player = threat.getPlayer();
                String playerName = player.getName() != null ? player.getName() : "Unknown";
                int combatLevel = player.getCombatLevel();

                log.warn("╔════════════════════════════════════════");
                log.warn("║ PKer: {} (Level {})", playerName, combatLevel);

                // Log equipment if available
                try {
                    var equipment = player.getPlayerComposition();
                    if (equipment != null) {
                        log.warn("║ Equipment IDs:");
                        log.warn("║   Head: {}", equipment.getEquipmentId(net.runelite.api.kit.KitType.HEAD));
                        log.warn("║   Cape: {}", equipment.getEquipmentId(net.runelite.api.kit.KitType.CAPE));
                        log.warn("║   Amulet: {}", equipment.getEquipmentId(net.runelite.api.kit.KitType.AMULET));
                        log.warn("║   Weapon: {}", equipment.getEquipmentId(net.runelite.api.kit.KitType.WEAPON));
                        log.warn("║   Torso: {}", equipment.getEquipmentId(net.runelite.api.kit.KitType.TORSO));
                        log.warn("║   Shield: {}", equipment.getEquipmentId(net.runelite.api.kit.KitType.SHIELD));
                        log.warn("║   Legs: {}", equipment.getEquipmentId(net.runelite.api.kit.KitType.LEGS));
                        log.warn("║   Gloves: {}", equipment.getEquipmentId(net.runelite.api.kit.KitType.HANDS));
                        log.warn("║   Boots: {}", equipment.getEquipmentId(net.runelite.api.kit.KitType.BOOTS));
                    }
                } catch (Exception e) {
                    log.debug("Could not retrieve PKer equipment: {}", e.getMessage());
                }

                log.warn("╚════════════════════════════════════════");
            }
        } catch (Exception e) {
            log.error("Error logging PKer details: {}", e.getMessage());
        }
    }

    private void sendDiscordWebhook(List<Rs2PlayerModel> threats) {
        try {
            String webhookUrl = config.discordWebhook();
            int currentWorld = Microbot.getClient().getWorld();
            String accountName = Microbot.getClient().getLocalPlayer() != null ?
                Microbot.getClient().getLocalPlayer().getName() : "Unknown";

            // Build the message content
            StringBuilder description = new StringBuilder();
            description.append("**Account:** ").append(accountName).append("\n");
            description.append("**World:** ").append(currentWorld).append("\n\n");
            description.append("**PKers Detected:**\n");

            for (Rs2PlayerModel threat : threats) {
                var player = threat.getPlayer();
                int combatLevel = player.getCombatLevel();
                String playerName = player.getName() != null ? player.getName() : "Unknown";
                description.append("• ").append(playerName)
                    .append(" (Level ").append(combatLevel).append(")\n");
            }

            // Create JSON payload for Discord webhook
            // Must escape special characters for valid JSON: quotes, newlines, backslashes
            String escapedDescription = description.toString()
                .replace("\\", "\\\\")  // Escape backslashes first
                .replace("\"", "\\\"")  // Escape quotes
                .replace("\n", "\\n")   // Escape newlines
                .replace("\r", "\\r")   // Escape carriage returns
                .replace("\t", "\\t");  // Escape tabs

            String jsonPayload = String.format(
                "{\"embeds\": [{\"title\": \"⚠️ PKer Alert\", \"description\": \"%s\", \"color\": 16711680}]}",
                escapedDescription
            );

            // Send the webhook in a separate thread to avoid blocking the main script
            new Thread(() -> {
                try {
                    URL url = new URL(webhookUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setDoOutput(true);

                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }

                    int responseCode = connection.getResponseCode();
                    if (responseCode == 204 || responseCode == 200) {
                        log.info("Discord webhook sent successfully");
                    } else {
                        log.warn("Discord webhook returned status code: {}", responseCode);
                    }

                    connection.disconnect();
                } catch (Exception e) {
                    log.error("Failed to send Discord webhook: {}", e.getMessage());
                }
            }, "Discord-Webhook-Thread").start();

        } catch (Exception e) {
            log.error("Error preparing Discord webhook: {}", e.getMessage());
        }
    }

    /**
     * Simple teleport to safety when out of keys (non-emergency).
     * Does NOT use logout, sleep chains, or recursive calls to avoid client freeze.
     */
    private void teleportToSafety() {
        boolean useGlory = config.teleportMethod() == ZombiePirateLockerConfig.TeleportMethod.GLORY_EDGEVILLE;
        String teleportDestination = useGlory ? "Edgeville" : "Ferox Enclave";
        String itemNamePattern = useGlory ? "amulet of glory" : "ring of dueling";

        // Find teleport item by name (handles all charge variants)
        Rs2ItemModel teleportItem = Rs2Inventory.get(item -> item != null &&
            item.getName() != null &&
            item.getName().toLowerCase().contains(itemNamePattern));

        if (teleportItem == null) {
            String itemName = useGlory ? "Amulet of Glory" : "Ring of Dueling";
            log.error("No {} found - it may have crumbled/uncharged! Cannot escape!", itemName);
            Microbot.log("No " + itemName + " found! Ring of Dueling crumbles when charges run out. Shutting down for safety.");
            shutdown();
            return;
        }

        log.info("Teleporting to {} using {} (out of keys)", teleportDestination, teleportItem.getName());
        Rs2Inventory.interact(teleportItem, teleportDestination);
        // Let the main loop check if we escaped - no blocking sleepUntil here
    }

    /**
     * EMERGENCY ESCAPE - Try instant logout first, then rapid-fire teleport
     * Priority: Logout (if not in combat) > Spam teleport
     */
    private void emergencyTeleport() {
        // Check cooldown to prevent spam teleporting
        if (System.currentTimeMillis() - lastEmergencyTeleport < EMERGENCY_TELEPORT_COOLDOWN) {
            log.debug("Emergency teleport on cooldown");
            return;
        }

        // PRIORITY 1: Try instant logout if NOT in combat
        if (!Rs2Combat.inCombat()) {
            log.error("⚡ NOT IN COMBAT - ATTEMPTING INSTANT LOGOUT ⚡");
            try {
                Rs2Player.logout();
                Global.sleep(500, 700);

                // Check if we're still logged in
                if (!Microbot.isLoggedIn()) {
                    log.info("✅ LOGGED OUT SUCCESSFULLY!");

                    // Wait for login screen
                    sleepUntil(() ->
                        Microbot.getClient().getGameState() == GameState.LOGIN_SCREEN, 3000);

                    if (Microbot.getClient().getGameState() == GameState.LOGIN_SCREEN) {
                        log.info("🔄 Hopping worlds on login screen for safety...");

                        // Get a random world
                        int newWorld = Login.getRandomWorld(true, null);
                        int currentWorld = Microbot.getClient().getWorld();
                        log.info("Hopping from world {} to {} on login screen", currentWorld, newWorld);

                        // Hop world on login screen
                        Microbot.hopToWorld(newWorld);
                        Global.sleep(800, 1200);

                        // Wait for hopping state
                        sleepUntil(() ->
                            Microbot.getClient().getGameState() == GameState.HOPPING, 5000);

                        if (Microbot.getClient().getGameState() == GameState.HOPPING) {
                            log.info("World hop initiated, waiting for AutoLogin...");

                            // Wait for AutoLogin to log us back in
                            sleepUntil(() ->
                                Microbot.getClient().getGameState() == GameState.LOGGED_IN, 15000);

                            if (Microbot.getClient().getGameState() == GameState.LOGGED_IN) {
                                log.info("✅ LOGGED IN ON WORLD {} - CHECKING LOCATION", Microbot.getClient().getWorld());

                                // Give client time to load position
                                Global.sleep(800, 1200);

                                // Check if we're still in wilderness after relogin
                                if (Rs2Pvp.isInWilderness()) {
                                    log.warn("⚠️ Still in wilderness after relogin - teleporting to safety");
                                    // Fall through to teleport logic below
                                } else {
                                    log.info("✅ SAFE LOCATION - PKer AVOIDED!");
                                    escapedFromPker = true; // Set flag to trigger bank
                                    lastEmergencyTeleport = System.currentTimeMillis();
                                    return;
                                }
                            } else {
                                log.warn("⚠️ Failed to log back in after world hop - may need manual intervention");
                                lastEmergencyTeleport = System.currentTimeMillis();
                                return;
                            }
                        } else {
                            log.warn("⚠️ World hop failed on login screen");
                        }
                    } else {
                        log.warn("⚠️ Did not reach login screen - unexpected state");
                    }
                }

                log.warn("⚠️ LOGOUT FAILED - FALLING BACK TO TELEPORT");
            } catch (Exception e) {
                log.error("Logout attempt failed: {}", e.getMessage());
            }
        } else {
            log.warn("⚠️ IN COMBAT - CANNOT LOGOUT - TELEPORTING INSTEAD");
        }

        // PRIORITY 2: Rapid-fire teleport spam
        boolean useGlory = config.teleportMethod() == ZombiePirateLockerConfig.TeleportMethod.GLORY_EDGEVILLE;
        String teleportDestination = useGlory ? "Edgeville" : "Ferox Enclave";
        String itemNamePattern = useGlory ? "amulet of glory" : "ring of dueling";
        String teleportItemName = useGlory ? "GLORY" : "RING OF DUELING";

        log.error("⚡ EMERGENCY TELEPORT - SPAMMING {} ⚡", teleportItemName);

        // Find teleport item by name (handles all charge variants)
        Rs2ItemModel teleportItem = Rs2Inventory.get(item -> item != null &&
            item.getName() != null &&
            item.getName().toLowerCase().contains(itemNamePattern));

        if (teleportItem == null) {
            log.error("❌ NO {} FOUND - CANNOT ESCAPE!", teleportItemName);
            shutdown();
            return;
        }

        // SPAM TELEPORT ATTEMPTS - Goal is to ESCAPE!
        int maxAttempts = 20; // Spam up to 20 times

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Check if we escaped
            if (Rs2Player.getWorldLocation().getY() < 3520) {
                log.info("✅ ESCAPED SUCCESSFULLY (attempt {})", attempt);
                lastEmergencyTeleport = System.currentTimeMillis();
                return;
            }

            // SPAM TELEPORT - NO DELAY
            log.warn("Teleport attempt {}/{} - QUICK!", attempt, maxAttempts);
            Rs2Inventory.interact(teleportItem, teleportDestination);

            // Minimal delay between attempts
            Global.sleep(50, 150);
        }

        // If we're still in wilderness after all attempts, let the main loop retry
        if (Rs2Player.getWorldLocation().getY() >= 3520) {
            log.error("❌ TELEPORT FAILED AFTER {} ATTEMPTS - STILL IN WILDERNESS!", maxAttempts);
            log.error("Main loop will retry on next tick");
            // Do NOT recursive call - let main loop handle retry to avoid stack overflow/freeze
        } else {
            log.info("✅ ESCAPED TO SAFETY");
        }
        lastEmergencyTeleport = System.currentTimeMillis();
    }

    private void handleBanking() {
        if (inventorySetup == null) {
            log.warn("Inventory setup not initialized yet, skipping banking");
            return;
        }

        log.debug("Opening bank");
        if (!Rs2Bank.openBank()) {
            log.warn("Failed to open bank");
            return;
        }

        log.debug("Bank opened successfully, waiting for bank interface");
        sleepUntil(Rs2Bank::isOpen, 2000);

        // Deposit looting bag first (uses built-in method)
        log.debug("Depositing looting bag");
        Rs2Bank.depositLootingBag();
        Global.sleep(350, 800);

        // Check if inventory/equipment matches setup (with glory exception)
        if (!doesInventoryMatchWithTeleportException() || !inventorySetup.doesEquipmentMatch()) {
            log.info("Inventory/equipment mismatch detected, loading setup");
            log.info("Loading inventory and equipment from setup");

            // ===== EQUIPMENT =====
            var items = inventorySetup.getEquipmentItems();
            for (var item : items) {
                if (item != null && item.getId() != -1) {
                    Rs2Bank.wearItem(item.getName(), true);
                }
            }

            // ===== INVENTORY =====
            // Custom loading logic that handles glory flexibility and missing items
            loadInventoryWithTeleportSupport();

            // Return and let next iteration verify setup is complete
            log.debug("Loading inventory, returning to let it complete");
            return;
        }

        if (doesInventoryMatchWithTeleportException() && inventorySetup.doesEquipmentMatch() && Rs2Bank.isOpen()) {
            // CRITICAL: Verify we have a teleport item before closing bank
            if (!hasTeleportItem()) {
                log.error("No teleport item available! Cannot safely enter wilderness.");
                Microbot.log("No teleport item (Ring of Dueling/Glory) found! Please add one to your bank.");
                shutdown();
                return;
            }

            log.debug("Inventory setup matches, closing bank");
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.between(1600, 2200)); // Wait for bank to close
        }

        // Set bank cooldown AFTER closing bank to prevent immediate re-banking
        lastBankTime = System.currentTimeMillis();
        log.debug("Bank cooldown set, preventing re-banking for {} seconds", BANK_COOLDOWN / 1000);

        // If we escaped from a PKer, hop worlds immediately at the bank
        if (escapedFromPker) {
            log.info("PKer escape: Hopping worlds at bank before returning to wilderness");
            int maxRetries = 6;
            boolean hopSuccessful = false;
            int initialWorld = Microbot.getClient().getWorld();

            for (int attempt = 1; attempt <= maxRetries && !hopSuccessful; attempt++) {
                int currentWorld = Microbot.getClient().getWorld();
                if (currentWorld != initialWorld && attempt > 1) {
                    log.info("Previous hop attempt succeeded - now on world {}", currentWorld);
                    lastHopTime = System.currentTimeMillis();
                    hopSuccessful = true;
                    break;
                }

                // Open world hopper widget before attempting hop
                Microbot.getClient().openWorldHopper();
                Global.sleep(300, 500);

                int newWorld = Login.getRandomWorld(true, null);
                log.info("PKer escape hop: Hopping from world {} to {} (attempt {}/{})",
                    currentWorld, newWorld, attempt, maxRetries);

                boolean isHopped = Microbot.hopToWorld(newWorld);
                Global.sleep(800, 1200);

                if (isHopped) {
                    log.debug("World hop initiated");
                } else {
                    log.debug("Microbot.hopToWorld returned false, waiting for potential async hop");
                }

                // Wait for hopping state
                sleepUntil(() ->
                    Microbot.getClient().getGameState() == GameState.HOPPING, 5000);

                if (Microbot.getClient().getGameState() == GameState.HOPPING) {
                    // Wait for logged in state
                    sleepUntil(() ->
                        Microbot.getClient().getGameState() == GameState.LOGGED_IN, 10000);

                    if (Microbot.getClient().getGameState() == GameState.LOGGED_IN) {
                        log.info("PKer escape hop successful - now on world {}", Microbot.getClient().getWorld());
                        lastHopTime = System.currentTimeMillis();
                        hopSuccessful = true;
                    } else {
                        log.warn("World hop timed out waiting for LOGGED_IN state (attempt {}/{})",
                            attempt, maxRetries);
                    }
                } else {
                    log.warn("World hop timed out waiting for HOPPING state (attempt {}/{})",
                        attempt, maxRetries);
                }

                if (!hopSuccessful && attempt < maxRetries) {
                    log.info("Waiting before retry...");
                    Global.sleep(2500, 3500);
                }
            }

            if (!hopSuccessful) {
                log.error("Failed to hop worlds after {} attempts, continuing anyway", maxRetries);
            }

            // Clear the PKer escape flag now that we've handled it
            escapedFromPker = false;
            log.info("PKer escape protocol complete, resuming normal operations");
        }

        // Travel to zombie pirates using configured transport method
        travelToZombiePirates();
    }

    /**
     * Travels to zombie pirates using the configured transport method
     */
    private void travelToZombiePirates() {
        // Close bank if open
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.between(1600, 2200));
        }

        WorldPoint currentLocation = Rs2Player.getWorldLocation();
        ZombiePirateLockerConfig.TransportMethod transportMethod = config.transportMethod();

        log.info("Using transport method: {}", transportMethod);

        switch (transportMethod) {
            case LUMBERYARD_TELEPORT:
                useLumberyardTeleport(currentLocation);
                break;
            case BURNING_AMULET:
                useBurningAmulet(currentLocation);
                break;
            case WILDERNESS_OBELISK:
                useWildernessObelisk(currentLocation);
                break;
            case FEROX_ENCLAVE_RUN:
                runFromFeroxEnclave();
                break;
            case RING_OF_ELEMENTS:
                useRingOfElements(currentLocation);
                break;
            case HOT_AIR_BALLOON:
                useHotAirBalloon(currentLocation);
                break;
            default:
                log.warn("Unknown transport method, defaulting to Lumberyard");
                useLumberyardTeleport(currentLocation);
        }

        // World hop after reaching destination (if not on cooldown)
        performWorldHopIfNeeded();
    }

    /**
     * Uses Lumberyard teleport tab to travel near zombie pirates
     */
    private void useLumberyardTeleport(WorldPoint currentLocation) {
        Rs2ItemModel lumberyardTab = Rs2Inventory.get(LUMBERYARD_TELE);
        if (lumberyardTab != null) {
            log.info("Using Lumberyard teleport tab");
            if (Rs2Inventory.interact(lumberyardTab, "Teleport")) {
                log.info("Teleporting to Lumberyard");
                sleepUntil(() -> !Rs2Player.getWorldLocation().equals(currentLocation), Rs2Random.between(5000, 7000));

                if (!Rs2Bank.isNearBank(10)) {
                    log.info("Successfully teleported to Lumberyard area");
                } else {
                    log.warn("Still near bank after teleport attempt");
                }
            } else {
                log.error("Failed to interact with Lumberyard teleport tab");
            }
        } else {
            log.error("Failed to find Lumberyard teleport tab in inventory");
        }
    }

    /**
     * Uses Burning Amulet to teleport to Chaos Temple, then walk east
     */
    private void useBurningAmulet(WorldPoint currentLocation) {
        // Find a charged burning amulet
        int burningAmuletId = -1;
        for (int id : BURNING_AMULET_IDS) {
            if (Rs2Inventory.hasItem(id)) {
                burningAmuletId = id;
                break;
            }
        }

        if (burningAmuletId == -1) {
            log.error("No Burning Amulet found in inventory!");
            return;
        }

        log.info("Using Burning Amulet to Chaos Temple");
        if (Rs2Inventory.interact(burningAmuletId, "Chaos Temple")) {
            sleepUntil(() -> !Rs2Player.getWorldLocation().equals(currentLocation), Rs2Random.between(5000, 7000));
            log.info("Teleported to Chaos Temple area");

            // Check if we have 72 agility for shortcut (use boosted level in case of stat drain)
            int agilityLevel = Rs2Player.getBoostedSkillLevel(net.runelite.api.Skill.AGILITY);
            if (agilityLevel >= 72) {
                log.info("Using 72 Agility shortcut (current level: {})", agilityLevel);
                // Walk to shortcut and use it, then continue to chest
            } else {
                log.info("Walking through Chaos Temple to zombie pirates (agility: {}, need 72)", agilityLevel);
                // Walker will handle the route
            }
        } else {
            log.error("Failed to use Burning Amulet");
        }
    }

    /**
     * Uses Wilderness Obelisk to teleport to level 18, then walk to zombie pirates
     */
    private void useWildernessObelisk(WorldPoint currentLocation) {
        log.info("Using Wilderness Obelisk method - walking to obelisk first");
        // This requires walking to an obelisk and activating it
        // The obelisk teleports to level 18 wilderness
        // Then walk south (with optional shortcut) and east to zombie pirates

        // For now, just walk towards the wilderness - Rs2Walker will handle routing
        log.warn("Wilderness Obelisk method requires walking to obelisk - using walker");
    }

    /**
     * Runs east from Ferox Enclave to zombie pirates
     */
    private void runFromFeroxEnclave() {
        log.info("Running east from Ferox Enclave to zombie pirates");
        // Already at Ferox if using Ring of Dueling, just need to walk east
        // Rs2Walker will handle the routing to CHEST_AREA
    }

    /**
     * Uses Ring of Elements to teleport to Earth Altar, then walk north
     */
    private void useRingOfElements(WorldPoint currentLocation) {
        if (!Rs2Inventory.hasItem(RING_OF_ELEMENTS)) {
            log.error("No Ring of Elements found in inventory!");
            return;
        }

        log.info("Using Ring of Elements to Earth Altar");
        if (Rs2Inventory.interact(RING_OF_ELEMENTS, "Earth Altar")) {
            sleepUntil(() -> !Rs2Player.getWorldLocation().equals(currentLocation), Rs2Random.between(5000, 7000));
            log.info("Teleported to Earth Altar area");
            // Walker will handle the route north to wilderness and zombie pirates
        } else {
            log.error("Failed to use Ring of Elements");
        }
    }

    /**
     * Uses Hot Air Balloon to travel to Varrock, then walk north
     */
    private void useHotAirBalloon(WorldPoint currentLocation) {
        log.info("Hot Air Balloon method - requires manual travel to balloon");
        // Hot air balloon requires physical interaction at the balloon location
        // This is less automated but still a valid route
        log.warn("Hot Air Balloon requires starting near a balloon - using walker to destination");
    }

    /**
     * Performs world hop if not on cooldown
     */
    private void performWorldHopIfNeeded() {
        if (System.currentTimeMillis() - lastHopTime >= HOP_COOLDOWN) {
            int maxRetries = 3;
            boolean hopSuccessful = false;
            int initialWorld = Microbot.getClient().getWorld();

            for (int attempt = 1; attempt <= maxRetries && !hopSuccessful; attempt++) {
                int currentWorld = Microbot.getClient().getWorld();
                if (currentWorld != initialWorld && attempt > 1) {
                    log.info("Previous hop attempt succeeded - now on world {}", currentWorld);
                    lastHopTime = System.currentTimeMillis();
                    hopSuccessful = true;
                    break;
                }

                Microbot.getClient().openWorldHopper();
                Global.sleep(300, 500);

                int newWorld = Login.getRandomWorld(true, null);
                log.info("Attempting to hop worlds from {} to {} (attempt {}/{})",
                    currentWorld, newWorld, attempt, maxRetries);

                boolean isHopped = Microbot.hopToWorld(newWorld);
                Global.sleep(800, 1200);

                sleepUntil(() ->
                    Microbot.getClient().getGameState() == GameState.HOPPING, 5000);

                if (Microbot.getClient().getGameState() == GameState.HOPPING) {
                    sleepUntil(() ->
                        Microbot.getClient().getGameState() == GameState.LOGGED_IN, 10000);

                    if (Microbot.getClient().getGameState() == GameState.LOGGED_IN) {
                        log.info("Successfully hopped to world {}", Microbot.getClient().getWorld());
                        lastHopTime = System.currentTimeMillis();
                        hopSuccessful = true;
                    } else {
                        log.warn("World hop timed out waiting for LOGGED_IN state (attempt {}/{})",
                            attempt, maxRetries);
                    }
                } else {
                    log.warn("World hop timed out waiting for HOPPING state (attempt {}/{})",
                        attempt, maxRetries);
                }

                if (!hopSuccessful && attempt < maxRetries) {
                    log.info("Waiting before retry...");
                    Global.sleep(2500, 3500);
                }
            }

            if (!hopSuccessful) {
                log.error("Failed to hop worlds after {} attempts, continuing anyway", maxRetries);
            }
        } else {
            log.debug("World hop on cooldown ({} seconds remaining)",
                (HOP_COOLDOWN - (System.currentTimeMillis() - lastHopTime)) / 1000);
        }
    }

    /**
     * Custom inventory check that accepts any charged teleport item (Glory or Ring of Dueling)
     * instead of requiring exact ID match. Uses name-based matching for reliability.
     */
    private boolean doesInventoryMatchWithTeleportException() {
        var setupItems = inventorySetup.getInventoryItems();

        for (var setupItem : setupItems) {
            if (setupItem == null || setupItem.getId() == -1) continue;

            String itemName = setupItem.getName() != null ? setupItem.getName().toLowerCase() : "";

            // Check if this setup item is a teleport item (Glory or Ring of Dueling) by name
            boolean isTeleportItemInSetup = itemName.contains("amulet of glory") ||
                                             itemName.contains("ring of dueling");

            if (isTeleportItemInSetup) {
                // For teleport items, check if we have ANY version based on config using hasTeleportItem()
                if (!hasTeleportItem()) {
                    return false; // Missing teleport item entirely
                }
                continue; // Teleport item OK, check next item
            }

            // For non-teleport items, check exact quantity
            int required = setupItem.getQuantity();
            int current = Rs2Inventory.items()
                .filter(i -> i != null && i.getId() == setupItem.getId())
                .mapToInt(Rs2ItemModel::getQuantity)
                .sum();

            if (current < required) {
                return false;
            }
        }

        return true;
    }

    /**
     * Custom inventory loading that handles teleport item flexibility and withdraws only missing items.
     * Uses name-based matching for teleport items.
     */
    private void loadInventoryWithTeleportSupport() {
        var setupItems = inventorySetup.getInventoryItems();

        log.debug("Starting custom inventory load with teleport item support");

        for (var setupItem : setupItems) {
            if (setupItem == null || setupItem.getId() == -1) continue;

            String itemName = setupItem.getName() != null ? setupItem.getName().toLowerCase() : "";

            // Check if this setup item is a teleport item (Glory or Ring of Dueling) by name
            boolean isTeleportItemInSetup = itemName.contains("amulet of glory") ||
                                             itemName.contains("ring of dueling");

            if (isTeleportItemInSetup) {
                // Handle teleport item with flexibility based on config
                handleTeleportItemInInventory();
                continue;
            }

            // For non-teleport items, check if we need more
            int required = setupItem.getQuantity();
            int current = Rs2Inventory.items()
                .filter(i -> i != null && i.getId() == setupItem.getId())
                .mapToInt(Rs2ItemModel::getQuantity)
                .sum();

            int needed = required - current;

            if (needed > 0) {
                log.info("Need {} more of {} (ID: {})", needed, setupItem.getName(), setupItem.getId());
                Rs2Bank.withdrawX(setupItem.getId(), needed);
                sleepUntil(() -> {
                    int newCurrent = Rs2Inventory.items()
                        .filter(i -> i != null && i.getId() == setupItem.getId())
                        .mapToInt(Rs2ItemModel::getQuantity)
                        .sum();
                    return newCurrent >= required;
                }, Rs2Random.between(800, 1600));
            } else if (needed < 0) {
                log.info("Have {} excess of {} (ID: {}), depositing", -needed, setupItem.getName(), setupItem.getId());
                Rs2Bank.depositX(setupItem.getId(), -needed);
                Global.sleep(300, 600);
            }
        }
    }

    /**
     * Handles teleport item in inventory - accepts any charged teleport item based on config,
     * deposits wrong type and withdraws correct type. Uses name-based matching.
     */
    private void handleTeleportItemInInventory() {
        boolean useGlory = config.teleportMethod() == ZombiePirateLockerConfig.TeleportMethod.GLORY_EDGEVILLE;
        String correctPattern = useGlory ? "amulet of glory" : "ring of dueling";
        String wrongPattern = useGlory ? "ring of dueling" : "amulet of glory";
        String correctItemName = useGlory ? "Amulet of Glory" : "Ring of Dueling";
        String wrongItemName = useGlory ? "Ring of Dueling" : "Amulet of Glory";

        // First, deposit any wrong teleport items by name
        Rs2ItemModel wrongItem = Rs2Inventory.get(item -> item != null &&
            item.getName() != null &&
            item.getName().toLowerCase().contains(wrongPattern));

        if (wrongItem != null) {
            log.info("Found {} in inventory but using {}, depositing", wrongItemName, correctItemName);
            Rs2Bank.depositOne(wrongItem.getId());
            Global.sleep(300, 500);
        }

        // For glory, also check for uncharged version
        if (useGlory && Rs2Inventory.hasItem(UNCHARGED_GLORY)) {
            log.info("Found uncharged glory, depositing and withdrawing charged glory");
            Rs2Bank.depositOne(UNCHARGED_GLORY);
            Global.sleep(300, 500);
        }

        // Check if we already have the correct charged teleport item using hasTeleportItem()
        if (hasTeleportItem()) {
            log.debug("Already have charged {}", correctItemName);
            return;
        }

        // Need to withdraw a charged teleport item by name
        log.info("No {} in inventory, withdrawing one", correctItemName);

        // Try to find and withdraw from bank - search bank items by name pattern
        // Use the ID arrays as fallback since Rs2Bank doesn't support predicate methods
        int[] teleportIds = useGlory ? GLORY_IDS : RING_OF_DUELING_IDS;
        boolean found = false;

        for (int teleportId : teleportIds) {
            if (Rs2Bank.hasItem(teleportId)) {
                log.info("Withdrawing {} (ID: {})", correctItemName, teleportId);
                Rs2Bank.withdrawOne(teleportId);
                int finalId = teleportId;
                sleepUntil(() -> Rs2Inventory.hasItem(finalId), Rs2Random.between(800, 1600));
                found = true;
                break;
            }
        }

        if (found) {
            log.info("Successfully withdrew {}", correctItemName);
        } else {
            log.warn("No charged {} found in bank!", correctItemName);
        }
    }

    private void openChest() {
        // Verify we have keys before attempting to open
        if (!Rs2Inventory.hasItem(ZOMBIE_KEY)) {
            log.debug("No zombie keys in inventory, cannot open chest");
            return;
        }

        // Verify we have a teleport item for safety
        if (!hasTeleportItem()) {
            log.warn("No teleport item available! Returning to bank for safety.");
            return; // Main loop will handle teleporting/banking
        }

        // Check if player is currently animating (already interacting with chest)
        if (Rs2Player.isAnimating()) {
            log.debug("Player is animating, waiting for current action to complete");
            return;
        }

        // Check cooldown to prevent rapid re-clicking the chest
        long timeSinceLastInteraction = System.currentTimeMillis() - lastChestInteraction;
        if (timeSinceLastInteraction < CHEST_INTERACTION_COOLDOWN) {
            log.debug("Chest interaction on cooldown ({} ms remaining)",
                CHEST_INTERACTION_COOLDOWN - timeSinceLastInteraction);
            return;
        }

        var chest = Rs2GameObject.findObjectById(CHEST_CLOSED);
        if (chest == null) {
            log.debug("No closed chest found nearby");
            return;
        }

        log.debug("Opening chest");
        chestOpened = false; // Reset flag before interacting
        lastChestInteraction = System.currentTimeMillis(); // Set cooldown BEFORE interaction

        if (Rs2GameObject.interact(chest, "Open")) {
            log.debug("Waiting for chest opening message...");

            // Wait for chat message confirmation with configurable delay
            int delayMin = config.chestOpenDelayMin();
            int delayMax = config.chestOpenDelayMax();
            sleepUntil(() -> chestOpened, Rs2Random.between(delayMin, delayMax));

            if (chestOpened) {
                log.debug("Chest opening message received");
                keysUsed++;
                log.info("Chest opened! Keys used: {}", keysUsed);
            } else {
                // Chest didn't open in time - but DON'T immediately retry
                // The cooldown will prevent rapid re-clicking
                log.warn("Chest opening message not received within expected time - will retry after cooldown");
            }
        } else {
            log.error("Failed to interact with closed chest");
        }
    }

    private void eatFood() {
        try {
            if (Rs2Inventory.contains(it -> it != null && it.isFood())) {
                Rs2ItemModel food = Rs2Inventory.get(it -> it != null && it.isFood());
                if (food != null) {
                    int currentHealth = Rs2Player.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS);
                    log.info("Eating food: {}", food.getName());
                    if (Rs2Inventory.interact(food, "Eat")) {
                        // Wait for health to increase or food to disappear from inventory
                        sleepUntil(() -> Rs2Player.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS) > currentHealth, Rs2Random.between(2500, 3500));
                        log.debug("Food consumed successfully");
                    } else {
                        log.warn("Failed to interact with food item");
                    }
                }
            } else {
                log.warn("No food found in inventory when trying to eat");
            }
        } catch (Exception e) {
            log.error("Error while eating food: {}", e.getMessage(), e);
        }
    }

    public static double getPerHour(long value) {
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed <= 0) return 0;
        return value * (3600000d / elapsed);
    }
}
