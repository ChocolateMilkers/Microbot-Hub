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
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

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

    private Rs2InventorySetup inventorySetup;

    public boolean run(ZombiePirateLockerConfig config) {
        log.info("Starting Zombie Pirate Locker script");
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
                if (detectPker()) {
                    log.warn("PKer detected! Initiating emergency protocol");
                    handlePkerDetected();
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

    private boolean detectPker() {
        if (!Rs2Pvp.isInWilderness()) {
            return false;
        }

        // Use Rs2Player helper to get players in combat level range
        var threateningPlayers = Rs2Player.getPlayersInCombatLevelRange();
        if (!threateningPlayers.isEmpty()) {
            log.warn("Detected {} threatening player(s) in combat level range", threateningPlayers.size());
            return true;
        }
        return false;
    }

    private void handlePkerDetected() {
        log.info("Handling PKer detection - executing emergency teleport");
        escapedFromPker = true; // Set flag for PKer escape
        emergencyTeleport();
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

            // CRITICAL: Handle glory management FIRST before loadInventory
            // This ensures we always have a charged glory and deposit uncharged ones
            handleGloryManagement();

            // ===== EQUIPMENT =====
            var items = inventorySetup.getEquipmentItems();
            for (var item : items) {
                if (item != null && item.getId() != -1) {
                    Rs2Bank.wearItem(item.getName(), true);
                }
            }

            // Load inventory setup (glory value is fuzzed in doesInventoryMatchWithGloryException)
            inventorySetup.loadInventory();

            // Return and let loadInventory() handle validation internally
            // Next loop iteration will check if setup is complete
            log.debug("Loading inventory, returning to let it complete");
            return;
        }

        log.debug("Inventory setup matches, closing bank");
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.between(1600, 2200)); // Wait for bank to close

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

    private void handleGloryManagement() {
        // Check if we have an uncharged glory in inventory
        if (Rs2Inventory.hasItem(UNCHARGED_GLORY)) {
            log.info("Found uncharged glory (ID: {}), depositing and withdrawing charged glory", UNCHARGED_GLORY);
            Rs2Bank.depositOne(UNCHARGED_GLORY);
            sleep(300, 500);

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

        // Check if we already have a charged glory - if so, skip withdrawing another
        boolean hasChargedGlory = false;
        for (int gloryId : GLORY_IDS) {
            if (Rs2Inventory.hasItem(gloryId)) {
                log.debug("Already have charged glory (ID: {}), skipping withdrawal", gloryId);
                hasChargedGlory = true;
                break;
            }
        }

        if (hasChargedGlory) {
            // We have a charged glory, don't need to do anything
            return;
        }

        // No glory in inventory, will be handled by the normal inventory setup loop
        log.debug("No glory in inventory, will be withdrawn by inventory setup");
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
