package net.runelite.client.plugins.microbot.zombiepiratelocker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.GameState;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.coords.Rs2WorldArea;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.Rs2PlayerModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

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
    private static final int LUMBERYARD_TELE = 12642;

    private static final int CHEST_CLOSED = 53222;

    // Area around the chest location for random tile selection
    private static final Rs2WorldArea CHEST_AREA =
            new Rs2WorldArea(new WorldPoint(3368, 3624, 0), 6, 3);

    // ==== STATS ====
    public static int keysUsed = 0;
    public static long startTime;
    public static volatile boolean chestOpened = false;

    // ==== INTERNAL ====
    private long lastHopTime = 0;
    private static final long HOP_COOLDOWN = 30_000; // 30 seconds
    private long lastBankTime = 0;
    private static final long BANK_COOLDOWN = 5000; // 5 seconds between banking attempts
    private long lastEmergencyTeleport = 0;
    private static final long EMERGENCY_TELEPORT_COOLDOWN = 10_000; // 10 seconds
    private boolean escapedFromPker = false; // Flag to track PKer-triggered emergency teleports
    private boolean depositLootingBag = false;
    private boolean attackDetected = false; // Flag to prevent log spam
    private long lastDiscordWebhookSent = 0;
    private static final long DISCORD_WEBHOOK_COOLDOWN = 30_000; // 60 seconds between webhook messages

    private Rs2InventorySetup inventorySetup;
    private ZombiePirateLockerConfig config;

    public boolean run(ZombiePirateLockerConfig config) {
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

                // 2. Eat if low HP
                if (Rs2Player.getHealthPercentage() < 80) {
                    log.info("Health below 80%, attempting to eat food");
                    eatFood();
                    return;
                }

                // 3. No keys → teleport to safety if in wilderness
                if (!Rs2Inventory.hasItem(ZOMBIE_KEY)) {
                    // Check if we're still in wilderness
                    if (Rs2Player.getWorldLocation().getY() >= 3520) {
                        // Still in wilderness, need emergency teleport
                        log.warn("Out of zombie keys in wilderness, teleporting to safety");
                        emergencyTeleport();
                        return;
                    }
                    // If we're safe, fall through to banking logic below
                    log.debug("Out of zombie keys, proceeding to bank");
                }

                // 4. Banking - bank if we're out of keys OR if we escaped from a PKer
                // (inventory setup will be checked and loaded inside handleBanking)
                if ((!Rs2Inventory.hasItem(ZOMBIE_KEY) || escapedFromPker) && Rs2Bank.isNearBank(15)) {
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
                log.error("Error in main script loop: {}", e.getMessage(), e);
                Microbot.log("Error in Zombie Pirate Locker: " + e.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        return true;
    }

    // ================= HELPERS =================

    private List<Rs2PlayerModel> detectPker() {
        try {
            if (!Rs2Pvp.isInWilderness()) {
                attackDetected = false; // Reset flag when not in wilderness
                return java.util.Collections.emptyList();
            }

            // Use Rs2Player helper to get players in combat level range
            var threateningPlayers = Rs2Player.getPlayersInCombatLevelRange();

            // Filter out friends, friends chat members, and clan members
            var actualThreats = threateningPlayers.stream()
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
        log.info("Handling PKer detection - executing emergency teleport");

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
        emergencyTeleport();
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
            String jsonPayload = String.format(
                "{\"embeds\": [{\"title\": \"⚠️ PKer Alert\", \"description\": \"%s\", \"color\": 16711680}]}",
                description.toString().replace("\"", "\\\"")
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

    private void emergencyTeleport() {
        // Check cooldown to prevent spam teleporting
        if (System.currentTimeMillis() - lastEmergencyTeleport < EMERGENCY_TELEPORT_COOLDOWN) {
            log.debug("Emergency teleport on cooldown");
            return;
        }

        log.info("Attempting emergency teleport to Edgeville");
        for (int id : GLORY_IDS) {
            if (Rs2Inventory.hasItem(id)) {
                log.info("Using Amulet of Glory (ID: {}) to teleport", id);
                if (Rs2Inventory.interact(id, "Edgeville")) {
                    sleepUntil(() -> Rs2Player.getWorldLocation().getY() < 3520, 4000);
                    if (Rs2Player.getWorldLocation().getY() < 3520) {
                        log.info("Successfully teleported to safety");
                        lastEmergencyTeleport = System.currentTimeMillis();
                        depositLootingBag = true;
                    } else {
                        log.warn("Teleport may have failed - still in wilderness");
                    }
                    return;
                } else {
                    log.error("Failed to interact with Glory (ID: {})", id);
                }
            }
        }
        log.error("No Amulet of Glory found in inventory! Shutting down script");
        //TODO FIX THIS
        shutdown();
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
        if (depositLootingBag) {
            Rs2Bank.depositLootingBag();
            sleep(Rs2Random.between(350, 800));
            depositLootingBag = false;
        }

        // Check if inventory/equipment matches setup (with glory exception)
        if (!doesInventoryMatchWithGloryException() || !inventorySetup.doesEquipmentMatch()) {
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
            loadInventoryWithGlorySupport();

            // Return and let next iteration verify setup is complete
            log.debug("Loading inventory, returning to let it complete");
            return;
        }

        if (doesInventoryMatchWithGloryException() && inventorySetup.doesEquipmentMatch() && Rs2Bank.isOpen()) {
            log.debug("Inventory setup matches, closing bank");
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.between(1600, 2200)); // Wait for bank to close
            return;
        }

        // Set bank cooldown AFTER closing bank to prevent immediate re-banking
        lastBankTime = System.currentTimeMillis();
        log.debug("Bank cooldown set, preventing re-banking for {} seconds", BANK_COOLDOWN / 1000);

        // If we escaped from a PKer, hop worlds immediately at the bank
        if (escapedFromPker) {
            log.info("PKer escape: Hopping worlds at bank before returning to wilderness");
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

                int newWorld = Login.getRandomWorld(true, null);
                log.info("PKer escape hop: Hopping from world {} to {} (attempt {}/{})",
                    currentWorld, newWorld, attempt, maxRetries);

                boolean isHopped = Microbot.hopToWorld(newWorld);
                sleep(1000);

                if (isHopped) {
                    log.debug("World hop initiated");
                } else {
                    log.debug("Microbot.hopToWorld returned false, waiting for potential async hop");
                }

                // Wait for hopping state
                boolean hoppingStarted = sleepUntil(() ->
                    Microbot.getClient().getGameState() == GameState.HOPPING, 5000);

                if (hoppingStarted) {
                    // Wait for logged in state
                    boolean loggedIn = sleepUntil(() ->
                        Microbot.getClient().getGameState() == GameState.LOGGED_IN, 10000);

                    if (loggedIn) {
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
                    log.info("Waiting 3 seconds before retry...");
                    sleep(3000);
                }
            }

            if (!hopSuccessful) {
                log.error("Failed to hop worlds after {} attempts, continuing anyway", maxRetries);
            }

            // Clear the PKer escape flag now that we've handled it
            escapedFromPker = false;
            log.info("PKer escape protocol complete, resuming normal operations");
        }

        // Use the teleport tab with a more direct interaction
        Rs2ItemModel lumberyardTab = Rs2Inventory.get(LUMBERYARD_TELE);
        if (lumberyardTab != null) {
            log.info("Using Lumberyard teleport tab");
            WorldPoint currentLocation = Rs2Player.getWorldLocation();

            if (Rs2Bank.isOpen()) {
                Rs2Bank.closeBank();
                sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.between(1600, 2200));
            }

            if (Rs2Inventory.interact(lumberyardTab, "Teleport")) {
                log.info("Teleporting to Lumberyard");
                // Wait for teleport to complete - wait until location changes
                sleepUntil(() -> {
                    WorldPoint newLocation = Rs2Player.getWorldLocation();
                    return !newLocation.equals(currentLocation);
                }, 6000);

                // Verify we left the bank area
                if (!Rs2Bank.isNearBank(10)) {
                    log.info("Successfully teleported to Lumberyard area");

                    // World hop at Lumberyard (quieter location, less players)
                    if (System.currentTimeMillis() - lastHopTime >= HOP_COOLDOWN) {
                        int maxRetries = 3;
                        boolean hopSuccessful = false;
                        int initialWorld = Microbot.getClient().getWorld();

                        for (int attempt = 1; attempt <= maxRetries && !hopSuccessful; attempt++) {
                            // Check if we already hopped from a previous attempt
                            int currentWorld = Microbot.getClient().getWorld();
                            if (currentWorld != initialWorld && attempt > 1) {
                                log.info("Previous hop attempt succeeded - now on world {}", currentWorld);
                                lastHopTime = System.currentTimeMillis();
                                hopSuccessful = true;
                                break;
                            }

                            int newWorld = Login.getRandomWorld(true, null);
                            log.info("Attempting to hop worlds from {} to {} (attempt {}/{})",
                                currentWorld, newWorld, attempt, maxRetries);

                            boolean isHopped = Microbot.hopToWorld(newWorld);

                            // Give the hop a moment to initiate even if it returned false
                            sleep(1000);

                            if (isHopped) {
                                log.debug("World hop initiated, waiting for state changes");
                            } else {
                                log.debug("Microbot.hopToWorld returned false, waiting for potential async hop");
                            }

                            // Wait for hopping state (regardless of return value)
                            boolean hoppingStarted = sleepUntil(() ->
                                Microbot.getClient().getGameState() == GameState.HOPPING, 5000);

                            if (hoppingStarted) {
                                // Wait for logged in state
                                boolean loggedIn = sleepUntil(() ->
                                    Microbot.getClient().getGameState() == GameState.LOGGED_IN, 10000);

                                if (loggedIn) {
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

                            // If not successful and not the last attempt, wait before retrying
                            if (!hopSuccessful && attempt < maxRetries) {
                                log.info("Waiting 3 seconds before retry...");
                                sleep(3000);
                            }
                        }

                        if (!hopSuccessful) {
                            log.error("Failed to hop worlds after {} attempts, continuing anyway", maxRetries);
                        }
                    } else {
                        log.debug("World hop on cooldown ({} seconds remaining)",
                            (HOP_COOLDOWN - (System.currentTimeMillis() - lastHopTime)) / 1000);
                    }
                } else {
                    log.warn("Still near bank after teleport attempt - may not have completed");
                }
            } else {
                log.error("Failed to interact with Lumberyard teleport tab");
            }
        } else {
            log.error("Failed to find Lumberyard teleport tab in inventory");
        }
    }

    /**
     * Custom inventory check that accepts any charged glory instead of requiring exact ID match
     */
    private boolean doesInventoryMatchWithGloryException() {
        var setupItems = inventorySetup.getInventoryItems();

        for (var setupItem : setupItems) {
            if (setupItem == null || setupItem.getId() == -1) continue;

            // Check if this setup item is a glory
            boolean isGloryInSetup = false;
            for (int gloryId : GLORY_IDS) {
                if (setupItem.getId() == gloryId) {
                    isGloryInSetup = true;
                    break;
                }
            }

            if (isGloryInSetup) {
                // For glories, check if we have ANY charged glory (not specific ID)
                boolean hasAnyChargedGlory = false;
                for (int gloryId : GLORY_IDS) {
                    if (Rs2Inventory.hasItem(gloryId)) {
                        hasAnyChargedGlory = true;
                        break;
                    }
                }
                if (!hasAnyChargedGlory) {
                    return false; // Missing glory entirely
                }
                continue; // Glory OK, check next item
            }

            // For non-glory items, check exact quantity
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
     * Custom inventory loading that handles glory flexibility and withdraws only missing items
     */
    private void loadInventoryWithGlorySupport() {
        var setupItems = inventorySetup.getInventoryItems();

        log.debug("Starting custom inventory load with glory support");

        for (var setupItem : setupItems) {
            if (setupItem == null || setupItem.getId() == -1) continue;

            // Check if this setup item is a glory
            boolean isGloryInSetup = false;
            for (int gloryId : GLORY_IDS) {
                if (setupItem.getId() == gloryId) {
                    isGloryInSetup = true;
                    break;
                }
            }

            if (isGloryInSetup) {
                // Handle glory with flexibility
                handleGloryInInventory();
                continue;
            }

            // For non-glory items, check if we need more
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
                sleep(Rs2Random.between(300, 600));
            }
        }
    }

    /**
     * Handles glory in inventory - accepts any charged glory, swaps uncharged for charged
     */
    private void handleGloryInInventory() {
        // First, check if we have an uncharged glory and swap it
        if (Rs2Inventory.hasItem(UNCHARGED_GLORY)) {
            log.info("Found uncharged glory, depositing and withdrawing charged glory");
            Rs2Bank.depositOne(UNCHARGED_GLORY);
            sleep(Rs2Random.between(300, 500));

            // Withdraw a charged glory
            for (int gloryId : GLORY_IDS) {
                if (Rs2Bank.hasItem(gloryId)) {
                    log.info("Withdrawing charged glory (ID: {})", gloryId);
                    Rs2Bank.withdrawOne(gloryId);
                    sleepUntil(() -> Rs2Inventory.hasItem(gloryId), Rs2Random.between(800, 1600));
                    return;
                }
            }
            log.warn("No charged glories found in bank!");
            return;
        }

        // Check if we already have a charged glory
        boolean hasChargedGlory = false;
        for (int gloryId : GLORY_IDS) {
            if (Rs2Inventory.hasItem(gloryId)) {
                log.debug("Already have charged glory (ID: {})", gloryId);
                hasChargedGlory = true;
                break;
            }
        }

        if (!hasChargedGlory) {
            // Need to withdraw a charged glory
            log.info("No glory in inventory, withdrawing one");
            for (int gloryId : GLORY_IDS) {
                if (Rs2Bank.hasItem(gloryId)) {
                    log.info("Withdrawing charged glory (ID: {})", gloryId);
                    Rs2Bank.withdrawOne(gloryId);
                    sleepUntil(() -> Rs2Inventory.hasItem(gloryId), Rs2Random.between(800, 1600));
                    return;
                }
            }
            log.warn("No charged glories found in bank!");
        }
    }

    private void openChest() {
        var chest = Rs2GameObject.findObjectById(CHEST_CLOSED);
        if (chest == null) {
            log.debug("No closed chest found nearby");
            return;
        }

        log.debug("Opening chest");
        chestOpened = false; // Reset flag before interacting

        if (Rs2GameObject.interact(chest, "Open")) {
            log.debug("Waiting for chest opening message...");

            // Wait for chat message confirmation
            boolean messageReceived = sleepUntil(() -> chestOpened, Rs2Random.between(2800, 3100));

            if (messageReceived) {
                log.debug("Chest opening message received, interacting with locker again");

                keysUsed++;
                log.info("Chest opened! Keys used: {}", keysUsed);
            } else {
                log.warn("Chest opening message not received within 3 seconds");
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
                        sleepUntil(() -> Rs2Player.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS) > currentHealth, 3000);
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
