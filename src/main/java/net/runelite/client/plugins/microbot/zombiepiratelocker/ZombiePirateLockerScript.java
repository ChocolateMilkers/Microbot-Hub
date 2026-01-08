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
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.concurrent.TimeUnit;

@Slf4j
public class ZombiePirateLockerScript extends Script {

    // ==== CONSTANTS ====
    private static final int ZOMBIE_KEY = 29449;
    private static final int[] GLORY_IDS = {1706,1708,1710,1712,11976,11978};
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

                // 4. Banking - only bank if we're out of keys
                // (inventory setup will be checked and loaded inside handleBanking)
                if (!Rs2Inventory.hasItem(ZOMBIE_KEY) && Rs2Bank.isNearBank(15)) {
                    // Prevent banking loop by checking cooldown
                    if (System.currentTimeMillis() - lastBankTime < BANK_COOLDOWN) {
                        log.debug("Banking on cooldown, waiting {} ms",
                            BANK_COOLDOWN - (System.currentTimeMillis() - lastBankTime));
                        return;
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
        Rs2Bank.depositLootingBag();
        sleep(600);

        // Check if inventory/equipment matches setup
        if (!inventorySetup.doesInventoryMatch() || !inventorySetup.doesEquipmentMatch()) {
            log.info("Inventory/equipment mismatch detected, loading setup");
            log.info("Loading inventory and equipment from setup");

            var items = inventorySetup.getEquipmentItems();
            var inventory = inventorySetup.getInventoryItems();
            for (var item : items) {
                if (item != null && item.getId() != -1) {
                    Rs2Bank.wearItem(item.getName(), true);
                }
            }

            for (var item : inventory) {
                if (item != null && item.getId() != -1) {
                    int required = item.getQuantity();
                    int current = Rs2Inventory.count(item.getName());
                    int missing = required - current;

                    if (missing <= 0)
                        continue;

                    Rs2Bank.withdrawX(item.getName(), missing, true);
                    sleepUntil(() -> Rs2Inventory.count(item.getName()) >= required, 3500);
                }
            }

            // Wait for inventory setup to complete
            log.debug("Waiting for inventory setup to complete");
            boolean setupComplete = sleepUntil(() ->
                inventorySetup.doesInventoryMatch() && inventorySetup.doesEquipmentMatch(), 2000);

            if (!setupComplete) {
                log.error("Failed to load inventory setup after 10 seconds");
                // Check what's missing
                if (!Rs2Inventory.hasItem(ZOMBIE_KEY)) {
                    log.error("Missing zombie pirate keys in bank!");
                }
                if (!Rs2Inventory.hasItem(LUMBERYARD_TELE)) {
                    log.error("Missing lumberyard teleport in bank!");
                }
                // Still try to continue - maybe some items are optional
            } else {
                log.info("Inventory setup loaded successfully");
            }
            return;
        }

        log.debug("Inventory setup matches, closing bank and teleporting");
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 3000); // Wait for bank to close

        // Set bank cooldown AFTER closing bank to prevent immediate re-banking
        lastBankTime = System.currentTimeMillis();
        log.debug("Bank cooldown set, preventing re-banking for {} seconds", BANK_COOLDOWN / 1000);

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
            boolean messageReceived = sleepUntil(() -> chestOpened, 3000);

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
