package net.runelite.client.plugins.microbot.zombiepiratelocker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.GameState;
import net.runelite.client.plugins.Plugin;
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
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
<<<<<<< Updated upstream
=======
import net.runelite.client.plugins.microbot.util.Global;
>>>>>>> Stashed changes

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

<<<<<<< Updated upstream
=======
    // Transport destination points
    private static final WorldPoint CHAOS_TEMPLE_AREA = new WorldPoint(3236, 3639, 0); // Near Chaos Temple after burning amulet
    private static final WorldPoint AGILITY_SHORTCUT_WEST = new WorldPoint(3267, 3628, 0); // West side of 72 agility shortcut
    private static final WorldPoint OBELISK_LVL18_AREA = new WorldPoint(3227, 3667, 0); // Level 18 obelisk area
    private static final WorldPoint FEROX_ENCLAVE_EXIT = new WorldPoint(3155, 3635, 0); // Ferox Enclave east exit
    private static final WorldPoint EARTH_ALTAR_AREA = new WorldPoint(3304, 3476, 0); // Earth Altar area (Ring of Elements)
    private static final WorldPoint VARROCK_BALLOON_AREA = new WorldPoint(3296, 3480, 0); // Varrock balloon landing

    // Ring of Dueling teleport to Ferox Enclave arrives at ~(3150, 3635), bank is at ~(3132, 3629) - about 18 tiles

>>>>>>> Stashed changes
    // ==== STATS ====
    public static int keysUsed = 0;
    public static long startTime;
    public static volatile boolean chestOpened = false;

    // ==== INTERNAL ====
    private long lastHopTime = 0;
<<<<<<< Updated upstream
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
=======
    private static final long HOP_COOLDOWN = 10_000; // 10 seconds
    private long lastBankTime = 0;
    private static final long BANK_COOLDOWN = 5000; // 5 seconds between banking attempts
    private static final long CHEST_INTERACTION_COOLDOWN = 2000; // 2 seconds between chest click attempts
    // PKer escape flag - can be set by PlayerMonitor plugin to trigger world hop after escape
    public boolean escapedFromPker = false;
>>>>>>> Stashed changes

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

                if (config.enablePlayerMonitor()) {
                    try {
                        Plugin playerMonitor = Microbot.getPluginManager().getPlugins().stream()
                                .filter(x -> x.getClass().getName().equals("net.runelite.client.plugins.microbot.playermonitor.PlayerMonitorPlugin"))
                                .findFirst()
                                .orElse(null);
                        if (playerMonitor != null) {
                            Microbot.startPlugin(playerMonitor);
                            Microbot.log("[Zombie Locker] Player Monitor enabled for banking safety");
                        } else {
                            Microbot.log("[Zombie Locker] Player Monitor plugin not found - you are at risk!");
                        }
                    } catch (Exception e) {
                        Microbot.log("[Zombie Locker] Failed to start Player Monitor: " + e.getMessage());
                    }
                }

                // Debug: Log loop start with key count
                int keyCount = Rs2Inventory.count(ZOMBIE_KEY);
                boolean hasKeyByHasItem = Rs2Inventory.hasItem(ZOMBIE_KEY);
                log.debug("Loop tick - Keys: {} (count), hasItem={}, Location: {}",
                    keyCount, hasKeyByHasItem, Rs2Player.getWorldLocation());

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

                // 1. PKer detection is handled by PlayerMonitor plugin (runs on separate thread)
                // PlayerMonitor will automatically interrupt walking and trigger emergency actions

                // 2. Eat if low HP
                if (Rs2Player.getHealthPercentage() < 80) {
                    log.info("Health below 80%, attempting to eat food");
                    eatFood();
                    return;
                }

<<<<<<< Updated upstream
                // 3. No keys → teleport to safety if in wilderness
                if (!Rs2Inventory.hasItem(ZOMBIE_KEY)) {
                    // Check if we're still in wilderness
                    if (Rs2Player.getWorldLocation().getY() >= 3520) {
                        // Still in wilderness, need emergency teleport
=======
                // 3. No keys → teleport to safety if in wilderness (but not if in safe zone like Ferox)
                // Use count() instead of hasItem() - hasItem may return stale/cached data
                if (Rs2Inventory.count(ZOMBIE_KEY) == 0) {
                    WorldPoint playerLoc = Rs2Player.getWorldLocation();
                    int playerY = playerLoc.getY();
                    boolean inWildernessRange = playerY >= 3520;

                    // Log immediately BEFORE checking safe zone
                    log.info("OUT OF KEYS! Location: {}, inWildernessRange={}", playerLoc, inWildernessRange);

                    boolean inSafeZone = isInWildernessSafeZone();
                    log.info("Safe zone check complete: inSafeZone={}", inSafeZone);

                    // Check if we're still in wilderness AND not in a safe zone
                    if (inWildernessRange && !inSafeZone) {
                        // Still in PvP wilderness, need to teleport out (not emergency - no PKer)
>>>>>>> Stashed changes
                        log.warn("Out of zombie keys in wilderness, teleporting to safety");
                        emergencyTeleport();
                        return;
                    }
<<<<<<< Updated upstream
                    // If we're safe, fall through to banking logic below
                    log.debug("Out of zombie keys, proceeding to bank");
=======
                    // If we're safe (below wilderness OR in safe zone), fall through to banking logic
                    log.info("Out of zombie keys, proceeding to bank (in safe area: Y={}, safeZone={})",
                        playerY, inSafeZone);
>>>>>>> Stashed changes
                }

                // 4. Banking - bank if we're out of keys OR if we escaped from a PKer
                // (inventory setup will be checked and loaded inside handleBanking)
<<<<<<< Updated upstream
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
=======
                // Use count() instead of hasItem() for reliability
                boolean needsBank = Rs2Inventory.count(ZOMBIE_KEY) == 0 || escapedFromPker;
                if (needsBank) {
                    // Determine target bank based on teleport method
                    BankLocation targetBank = config.teleportMethod() == ZombiePirateLockerConfig.TeleportMethod.GLORY_EDGEVILLE
                        ? BankLocation.EDGEVILLE
                        : BankLocation.FEROX_ENCLAVE;

                    // Check if we're in the general bank area (30 tiles)
                    if (Rs2Bank.isNearBank(30)) {
                        // We're in the area - but are we close enough to interact?
                        if (Rs2Bank.isNearBank(10)) {
                            // Close enough to interact
                            if (!escapedFromPker && System.currentTimeMillis() - lastBankTime < BANK_COOLDOWN) {
                                log.debug("Banking on cooldown, waiting {} ms",
                                    BANK_COOLDOWN - (System.currentTimeMillis() - lastBankTime));
                                return;
                            }

                            if (escapedFromPker) {
                                log.info("Escaped from PKer - forcing rebank and world hop");
                            }

                            log.debug("Close to bank (within 10 tiles), handling banking operations");
                            handleBanking();
                            return;
                        } else {
                            // In bank area but too far to interact - walk closer
                            log.debug("In bank area but too far to interact, walking to {} bank", targetBank);
                            Rs2Bank.walkToBank(targetBank);
                            return;
                        }
                    }

                    // Not near any bank - check if in safe zone or wilderness
                    boolean inWilderness = Rs2Pvp.isInWilderness();
                    boolean inSafeZone = isInWildernessSafeZone();

                    if (inWilderness && !inSafeZone) {
                        // Still in PvP wilderness, MUST teleport before we can bank
                        log.warn("Still in wilderness without keys, attempting teleport to safety");
                        teleportToSafety();
                        return;
                    }

                    // In safe zone or outside wilderness - walk to target bank
                    log.info("Walking to {} bank from safe area", targetBank);
                    Rs2Bank.walkToBank(targetBank);
>>>>>>> Stashed changes
                    return;
                }

                // 5. Walk to chest area
                if (!CHEST_AREA.contains(Rs2Player.getWorldLocation())) {
                    // Only initiate walk if not already moving (prevents spam)
                    if (!Rs2Player.isMoving()) {
                        int totalTiles = CHEST_AREA.toWorldPointList().size();
                        WorldPoint randomTile = CHEST_AREA.toWorldPointList().get(
                            net.runelite.client.plugins.microbot.util.math.Rs2Random.between(0, totalTiles - 1)
                        );
                        log.debug("Walking to chest area: {}", randomTile);
                        // Use distance tolerance of 3 - walker will return when within 3 tiles
                        // This allows more frequent PKer checks between path segments
                        Rs2Walker.walkTo(randomTile, 3);
                    }
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

<<<<<<< Updated upstream
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
=======
    // Ferox Enclave safe zone boundaries (approximate)
    private static final int FEROX_MIN_X = 3125;
    private static final int FEROX_MAX_X = 3155;
    private static final int FEROX_MIN_Y = 3618;
    private static final int FEROX_MAX_Y = 3642;

    /**
     * Checks if the player is in a wilderness safe zone (like Ferox Enclave).
     * Uses coordinate-based detection for reliability - widget lookups can block/fail.
     *
     * @return true if in Ferox Enclave or below wilderness line, false otherwise
     */
    private boolean isInWildernessSafeZone() {
        try {
            WorldPoint loc = Rs2Player.getWorldLocation();
            int x = loc.getX();
            int y = loc.getY();

            // Below wilderness line = safe
            if (y < 3520) {
                log.debug("Below wilderness (Y={}) - safe", y);
                return true;
            }

            // Check if in Ferox Enclave bounds
            boolean inFerox = x >= FEROX_MIN_X && x <= FEROX_MAX_X &&
                              y >= FEROX_MIN_Y && y <= FEROX_MAX_Y;

            if (inFerox) {
                log.debug("In Ferox Enclave area (X={}, Y={}) - safe", x, y);
                return true;
            }

            // In wilderness range but not in known safe zone
            log.debug("In wilderness (X={}, Y={}) - NOT safe", x, y);
            return false;
        } catch (Exception e) {
            log.warn("Error checking safe zone: {} - assuming NOT safe", e.getMessage());
            return false;
        }
    }

    /**
     * Checks if the player has a valid teleport item based on config.
     * Uses item IDs for reliable detection across all charge variants.
     *
     * @return true if player has a valid teleport item in inventory
     */
    private boolean hasTeleportItem() {
        ZombiePirateLockerConfig.TeleportMethod method = config.teleportMethod();
        int[] idsToCheck;

        switch (method) {
            case GLORY_EDGEVILLE:
                idsToCheck = GLORY_IDS;
                break;
            case RING_OF_DUELING_FEROX:
                idsToCheck = RING_OF_DUELING_IDS;
                break;
            default:
                log.warn("Unknown teleport method: {} - checking for any teleport item", method);
                // Check for either glory or ring of dueling
                for (int id : GLORY_IDS) {
                    if (Rs2Inventory.hasItem(id)) return true;
                }
                for (int id : RING_OF_DUELING_IDS) {
                    if (Rs2Inventory.hasItem(id)) return true;
                }
                return false;
        }
>>>>>>> Stashed changes

        for (int id : idsToCheck) {
            if (Rs2Inventory.hasItem(id)) {
                return true;
            }
<<<<<<< Updated upstream

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
=======
>>>>>>> Stashed changes
        }
        return false;
    }

<<<<<<< Updated upstream
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
=======
    /**
     * Simple teleport to safety when out of keys (non-emergency).
     * Does NOT use logout, sleep chains, or recursive calls to avoid client freeze.
     */
    private void teleportToSafety() {
        ZombiePirateLockerConfig.TeleportMethod method = config.teleportMethod();
        log.info("teleportToSafety() called - TeleportMethod: {}", method);

        String teleportDestination;
        int[] itemIds;
        String itemDisplayName;

        // Use switch for proper handling of all teleport methods
        switch (method) {
            case GLORY_EDGEVILLE:
                teleportDestination = "Edgeville";
                itemIds = GLORY_IDS;
                itemDisplayName = "Amulet of Glory";
                break;
            case RING_OF_DUELING_FEROX:
                teleportDestination = "Ferox Enclave";
                itemIds = RING_OF_DUELING_IDS;
                itemDisplayName = "Ring of Dueling";
                break;
            default:
                log.error("Unknown teleport method: {} - defaulting to Glory", method);
                teleportDestination = "Edgeville";
                itemIds = GLORY_IDS;
                itemDisplayName = "Amulet of Glory";
        }

        // Find teleport item by ID (handles all charge variants)
        Rs2ItemModel teleportItem = null;
        for (int itemId : itemIds) {
            teleportItem = Rs2Inventory.get(itemId);
            if (teleportItem != null) {
                log.info("Found {} (ID: {}) in inventory", itemDisplayName, itemId);
                break;
            }
        }

        if (teleportItem == null) {
            log.error("NO {} FOUND IN INVENTORY - Cannot escape!", itemDisplayName);
            log.error("Searched for IDs: {}", java.util.Arrays.toString(itemIds));
            log.error("Inventory contents:");
            Rs2Inventory.items().forEach(item -> {
                if (item != null) {
                    log.error("  - {} (ID: {})", item.getName(), item.getId());
                }
            });
            Microbot.log("No " + itemDisplayName + " found! Ring of Dueling crumbles when charges run out. Shutting down for safety.");
            shutdown();
            return;
        }

        log.info("Teleporting to {} using {} (ID: {})", teleportDestination, teleportItem.getName(), teleportItem.getId());

        WorldPoint beforeTeleport = Rs2Player.getWorldLocation();

        // Interact with the teleport item
        Rs2Inventory.interact(teleportItem.getId(), teleportDestination);

        // Wait briefly and check if position changed
        Global.sleep(600, 1000);
        WorldPoint afterWait = Rs2Player.getWorldLocation();
        if (!afterWait.equals(beforeTeleport)) {
            log.info("Position changed from {} to {} - teleport successful!", beforeTeleport, afterWait);
        } else {
            log.warn("Position unchanged after teleport attempt - still at {}", afterWait);
        }

        // Let the main loop check if we escaped on next tick
>>>>>>> Stashed changes
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
<<<<<<< Updated upstream
=======
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
        // World hopping is only done when escaping from PKer (handled in handleBanking)
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
>>>>>>> Stashed changes
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
<<<<<<< Updated upstream
     * Custom inventory check that accepts any charged glory instead of requiring exact ID match
=======
     * Custom inventory check that accepts any charged teleport item (Glory or Ring of Dueling)
     * instead of requiring exact ID match. Aggregates items by ID to handle non-stackable items
     * like food that appear as multiple entries in the setup.
>>>>>>> Stashed changes
     */
    private boolean doesInventoryMatchWithGloryException() {
        var setupItems = inventorySetup.getInventoryItems();

        // Aggregate required quantities by item ID
        // For non-stackable items (quantity=1), this counts how many slots have that item
        // For stackable items, this sums the quantities
        Map<Integer, Integer> requiredByItemId = new HashMap<>();
        boolean hasTeleportInSetup = false;

        for (var setupItem : setupItems) {
            if (setupItem == null || setupItem.getId() == -1) continue;

<<<<<<< Updated upstream
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
=======
            String itemName = setupItem.getName() != null ? setupItem.getName().toLowerCase() : "";

            // Check if this setup item is a teleport item (Glory or Ring of Dueling) by name
            boolean isTeleportItem = itemName.contains("amulet of glory") ||
                                     itemName.contains("ring of dueling");

            if (isTeleportItem) {
                hasTeleportInSetup = true;
                continue; // Don't add to map, handled separately
            }

            // Aggregate by item ID - add quantity (1 for non-stackables, actual qty for stackables)
            int itemId = setupItem.getId();
            int qty = setupItem.getQuantity();
            requiredByItemId.merge(itemId, qty, Integer::sum);
        }

        // Check teleport item if setup has one
        if (hasTeleportInSetup && !hasTeleportItem()) {
            return false;
        }

        // Check each required item
        for (Map.Entry<Integer, Integer> entry : requiredByItemId.entrySet()) {
            int itemId = entry.getKey();
            int required = entry.getValue();

>>>>>>> Stashed changes
            int current = Rs2Inventory.items()
                .filter(i -> i != null && i.getId() == itemId)
                .mapToInt(Rs2ItemModel::getQuantity)
                .sum();

            if (current != required) {
                log.debug("Item ID {} mismatch: required={}, current={}", itemId, required, current);
                return false;
            }
        }

        return true;
    }

    /**
<<<<<<< Updated upstream
     * Custom inventory loading that handles glory flexibility and withdraws only missing items
=======
     * Custom inventory loading that handles teleport item flexibility and withdraws only missing items.
     * Aggregates items by ID to handle non-stackable items like food that appear as multiple entries.
>>>>>>> Stashed changes
     */
    private void loadInventoryWithGlorySupport() {
        var setupItems = inventorySetup.getInventoryItems();

        log.debug("Starting custom inventory load with glory support");

        // Aggregate required quantities by item ID (and track item names for logging)
        Map<Integer, Integer> requiredByItemId = new HashMap<>();
        Map<Integer, String> itemNames = new HashMap<>();
        boolean hasTeleportInSetup = false;

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

<<<<<<< Updated upstream
            if (isGloryInSetup) {
                // Handle glory with flexibility
                handleGloryInInventory();
                continue;
            }

            // For non-glory items, check if we need more
            int required = setupItem.getQuantity();
=======
            // Check if this setup item is a teleport item (Glory or Ring of Dueling) by name
            boolean isTeleportItem = itemName.contains("amulet of glory") ||
                                     itemName.contains("ring of dueling");

            if (isTeleportItem) {
                hasTeleportInSetup = true;
                continue;
            }

            // Aggregate by item ID
            int itemId = setupItem.getId();
            int qty = setupItem.getQuantity();
            requiredByItemId.merge(itemId, qty, Integer::sum);
            itemNames.putIfAbsent(itemId, setupItem.getName());
        }

        // Handle teleport item first if setup has one
        if (hasTeleportInSetup) {
            handleTeleportItemInInventory();
        }

        // Process each unique item ID
        for (Map.Entry<Integer, Integer> entry : requiredByItemId.entrySet()) {
            int itemId = entry.getKey();
            int required = entry.getValue();
            String name = itemNames.getOrDefault(itemId, "Unknown");

>>>>>>> Stashed changes
            int current = Rs2Inventory.items()
                .filter(i -> i != null && i.getId() == itemId)
                .mapToInt(Rs2ItemModel::getQuantity)
                .sum();

            int needed = required - current;

            if (needed > 0) {
                log.info("Need {} more of {} (ID: {})", needed, name, itemId);
                Rs2Bank.withdrawX(itemId, needed);
                final int targetAmount = required;
                sleepUntil(() -> {
                    int newCurrent = Rs2Inventory.items()
                        .filter(i -> i != null && i.getId() == itemId)
                        .mapToInt(Rs2ItemModel::getQuantity)
                        .sum();
                    return newCurrent >= targetAmount;
                }, Rs2Random.between(800, 1600));
            } else if (needed < 0) {
<<<<<<< Updated upstream
                log.info("Have {} excess of {} (ID: {}), depositing", -needed, setupItem.getName(), setupItem.getId());
                Rs2Bank.depositX(setupItem.getId(), -needed);
                sleep(Rs2Random.between(300, 600));
=======
                log.info("Have {} excess of {} (ID: {}), depositing", -needed, name, itemId);
                Rs2Bank.depositX(itemId, -needed);
                Global.sleep(300, 600);
>>>>>>> Stashed changes
            }
        }
    }

    /**
<<<<<<< Updated upstream
     * Handles glory in inventory - accepts any charged glory, swaps uncharged for charged
     */
    private void handleGloryInInventory() {
        // First, check if we have an uncharged glory and swap it
        if (Rs2Inventory.hasItem(UNCHARGED_GLORY)) {
            log.info("Found uncharged glory, depositing and withdrawing charged glory");
=======
     * Handles teleport item in inventory - withdraws correct type based on config.
     * Uses item IDs for reliable detection across all charge variants.
     */
    private void handleTeleportItemInInventory() {
        ZombiePirateLockerConfig.TeleportMethod method = config.teleportMethod();
        int[] correctIds;
        int[] wrongIds;
        String correctItemName;
        String wrongItemName;

        switch (method) {
            case GLORY_EDGEVILLE:
                correctIds = GLORY_IDS;
                wrongIds = RING_OF_DUELING_IDS;
                correctItemName = "Amulet of Glory";
                wrongItemName = "Ring of Dueling";
                break;
            case RING_OF_DUELING_FEROX:
                correctIds = RING_OF_DUELING_IDS;
                wrongIds = GLORY_IDS;
                correctItemName = "Ring of Dueling";
                wrongItemName = "Amulet of Glory";
                break;
            default:
                log.warn("Unknown teleport method: {} - defaulting to Glory", method);
                correctIds = GLORY_IDS;
                wrongIds = RING_OF_DUELING_IDS;
                correctItemName = "Amulet of Glory";
                wrongItemName = "Ring of Dueling";
        }

        // Deposit any wrong teleport items
        for (int wrongId : wrongIds) {
            if (Rs2Inventory.hasItem(wrongId)) {
                log.info("Found {} (ID: {}) in inventory but using {}, depositing", wrongItemName, wrongId, correctItemName);
                Rs2Bank.depositOne(wrongId);
                Global.sleep(300, 500);
            }
        }

        // For glory, also check for uncharged version
        if (method == ZombiePirateLockerConfig.TeleportMethod.GLORY_EDGEVILLE && Rs2Inventory.hasItem(UNCHARGED_GLORY)) {
            log.info("Found uncharged glory, depositing");
>>>>>>> Stashed changes
            Rs2Bank.depositOne(UNCHARGED_GLORY);
            sleep(Rs2Random.between(300, 500));

<<<<<<< Updated upstream
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
=======
        // Check if we already have the correct charged teleport item
        for (int correctId : correctIds) {
            if (Rs2Inventory.hasItem(correctId)) {
                log.debug("Already have charged {} (ID: {})", correctItemName, correctId);
                return;
            }
        }

        // Need to withdraw a charged teleport item
        log.info("No {} in inventory, withdrawing one", correctItemName);

        for (int teleportId : correctIds) {
            if (Rs2Bank.hasItem(teleportId)) {
                log.info("Withdrawing {} (ID: {})", correctItemName, teleportId);
                Rs2Bank.withdrawOne(teleportId);
                final int idToCheck = teleportId;
                sleepUntil(() -> Rs2Inventory.hasItem(idToCheck), Rs2Random.between(800, 1600));
                log.info("Successfully withdrew {}", correctItemName);
                return;
            }
>>>>>>> Stashed changes
        }

        log.warn("No charged {} found in bank!", correctItemName);
    }

    private void openChest() {
<<<<<<< Updated upstream
=======
        // Verify we have keys before attempting to open
        // Use count() instead of hasItem() for reliability
        if (Rs2Inventory.count(ZOMBIE_KEY) == 0) {
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

>>>>>>> Stashed changes
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
