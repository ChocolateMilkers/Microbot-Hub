package net.runelite.client.plugins.microbot.zombiepiratelocker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.GameState;
import net.runelite.client.plugins.Plugin;
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
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.Global;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ZombiePirateLockerScript extends Script {

    // ==== CONSTANTS ====
    private static final int ZOMBIE_KEY = 29449;
    private static final int[] GLORY_IDS = {1706,1708,1710,1712,11976,11978}; // Charged glories
    private static final int[] RING_OF_DUELING_IDS = {2552,2554,2556,2558,2560,2562,2564,2566}; // Ring of dueling (8) to (1)
    private static final int[] BURNING_AMULET_IDS = {21166,21169,21171,21173,21175}; // Burning amulet (5) to (1)
    private static final int RING_OF_ELEMENTS = 26815;
    private static final int UNCHARGED_GLORY = 1704;
    private static final int LUMBERYARD_TELE = 12642;

    private static final int CHEST_CLOSED = 53222;

    // Area around the chest location for random tile selection
    private static final Rs2WorldArea CHEST_AREA =
            new Rs2WorldArea(new WorldPoint(3368, 3624, 0), 6, 3);

    // Ferox Enclave safe zone boundaries (approximate)
    private static final int FEROX_MIN_X = 3125;
    private static final int FEROX_MAX_X = 3155;
    private static final int FEROX_MIN_Y = 3618;
    private static final int FEROX_MAX_Y = 3642;

    // ==== STATS ====
    public static int keysUsed = 0;
    public static long startTime;
    public static volatile boolean chestOpened = false;

    // ==== INTERNAL ====
    private long lastHopTime = 0;
    private static final long HOP_COOLDOWN = 10_000; // 10 seconds
    private long lastBankTime = 0;
    private static final long BANK_COOLDOWN = 5000; // 5 seconds between banking attempts
    private static final long CHEST_INTERACTION_COOLDOWN = 2000; // 2 seconds between chest click attempts
    private long lastChestInteraction = 0;
    // PKer escape flag - can be set by PlayerMonitor plugin to trigger world hop after escape
    public boolean escapedFromPker = false;

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
                        }
                    } catch (Exception e) {
                        log.debug("Failed to start Player Monitor: {}", e.getMessage());
                    }
                }

                // Debug: Log loop start with key count
                int keyCount = Rs2Inventory.count(ZOMBIE_KEY);
                log.debug("Loop tick - Keys: {}, Location: {}", keyCount, Rs2Player.getWorldLocation());

                // Initialize inventory setup on first run
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

                // 2. Eat if low HP
                if (Rs2Player.getHealthPercentage() < 80) {
                    log.info("Health below 80%, attempting to eat food");
                    eatFood();
                    return;
                }

                // 3. No keys → teleport to safety if in wilderness (but not if in safe zone like Ferox)
                if (Rs2Inventory.count(ZOMBIE_KEY) == 0) {
                    WorldPoint playerLoc = Rs2Player.getWorldLocation();
                    int playerY = playerLoc.getY();
                    boolean inWildernessRange = playerY >= 3520;

                    log.info("OUT OF KEYS! Location: {}, inWildernessRange={}", playerLoc, inWildernessRange);

                    boolean inSafeZone = isInWildernessSafeZone();
                    log.info("Safe zone check complete: inSafeZone={}", inSafeZone);

                    if (inWildernessRange && !inSafeZone) {
                        log.warn("Out of zombie keys in wilderness, teleporting to safety");
                        teleportToSafety();
                        return;
                    }
                    log.info("Out of zombie keys, proceeding to bank (in safe area: Y={}, safeZone={})",
                        playerY, inSafeZone);
                }

                // 4. Banking - bank if we're out of keys OR if we escaped from a PKer
                boolean needsBank = Rs2Inventory.count(ZOMBIE_KEY) == 0 || escapedFromPker;
                if (needsBank) {
                    BankLocation targetBank = config.teleportMethod() == ZombiePirateLockerConfig.TeleportMethod.GLORY_EDGEVILLE
                        ? BankLocation.EDGEVILLE
                        : BankLocation.FEROX_ENCLAVE;

                    if (Rs2Bank.isNearBank(30)) {
                        if (Rs2Bank.isNearBank(10)) {
                            if (!escapedFromPker && System.currentTimeMillis() - lastBankTime < BANK_COOLDOWN) {
                                log.debug("Banking on cooldown");
                                return;
                            }

                            if (escapedFromPker) {
                                log.info("Escaped from PKer - forcing rebank and world hop");
                            }

                            log.debug("Close to bank, handling banking operations");
                            handleBanking();
                            return;
                        } else {
                            log.debug("In bank area but too far, walking to {} bank", targetBank);
                            Rs2Bank.walkToBank(targetBank);
                            return;
                        }
                    }

                    boolean inWilderness = Rs2Pvp.isInWilderness();
                    boolean inSafeZone = isInWildernessSafeZone();

                    if (inWilderness && !inSafeZone) {
                        log.warn("Still in wilderness without keys, attempting teleport to safety");
                        teleportToSafety();
                        return;
                    }

                    log.info("Walking to {} bank from safe area", targetBank);
                    Rs2Bank.walkToBank(targetBank);
                    return;
                }

                // 5. Walk to chest area
                if (!CHEST_AREA.contains(Rs2Player.getWorldLocation())) {
                    if (!Rs2Player.isMoving()) {
                        int totalTiles = CHEST_AREA.toWorldPointList().size();
                        WorldPoint randomTile = CHEST_AREA.toWorldPointList().get(
                            Rs2Random.between(0, totalTiles - 1)
                        );
                        log.debug("Walking to chest area: {}", randomTile);
                        Rs2Walker.walkTo(randomTile, 3);
                    }
                    return;
                }

                // 6. Open chest
                openChest();

            } catch (Exception e) {
                log.error("Error in main script loop: {}", e.getMessage(), e);
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        return true;
    }

    // ================= HELPERS =================

    private boolean isInWildernessSafeZone() {
        try {
            WorldPoint loc = Rs2Player.getWorldLocation();
            int x = loc.getX();
            int y = loc.getY();

            if (y < 3520) {
                log.debug("Below wilderness (Y={}) - safe", y);
                return true;
            }

            boolean inFerox = x >= FEROX_MIN_X && x <= FEROX_MAX_X &&
                              y >= FEROX_MIN_Y && y <= FEROX_MAX_Y;

            if (inFerox) {
                log.debug("In Ferox Enclave area (X={}, Y={}) - safe", x, y);
                return true;
            }

            log.debug("In wilderness (X={}, Y={}) - NOT safe", x, y);
            return false;
        } catch (Exception e) {
            log.warn("Error checking safe zone: {} - assuming NOT safe", e.getMessage());
            return false;
        }
    }

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
                for (int id : GLORY_IDS) {
                    if (Rs2Inventory.hasItem(id)) return true;
                }
                for (int id : RING_OF_DUELING_IDS) {
                    if (Rs2Inventory.hasItem(id)) return true;
                }
                return false;
        }

        for (int id : idsToCheck) {
            if (Rs2Inventory.hasItem(id)) {
                return true;
            }
        }
        return false;
    }

    private void teleportToSafety() {
        ZombiePirateLockerConfig.TeleportMethod method = config.teleportMethod();
        log.info("teleportToSafety() called - TeleportMethod: {}", method);

        String teleportDestination;
        int[] itemIds;
        String itemDisplayName;

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
            Microbot.log("No " + itemDisplayName + " found! Shutting down for safety.");
            shutdown();
            return;
        }

        log.info("Teleporting to {} using {} (ID: {})", teleportDestination, teleportItem.getName(), teleportItem.getId());

        WorldPoint beforeTeleport = Rs2Player.getWorldLocation();
        Rs2Inventory.interact(teleportItem.getId(), teleportDestination);

        Global.sleep(600, 1000);
        WorldPoint afterWait = Rs2Player.getWorldLocation();
        if (!afterWait.equals(beforeTeleport)) {
            log.info("Position changed - teleport successful!");
        } else {
            log.warn("Position unchanged after teleport attempt");
        }
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

        sleepUntil(Rs2Bank::isOpen, 2000);

        // Deposit looting bag
        Rs2Bank.depositLootingBag();
        Global.sleep(350, 800);

        // Check if inventory/equipment matches setup
        if (!doesInventoryMatchWithTeleportException() || !inventorySetup.doesEquipmentMatch()) {
            log.info("Inventory/equipment mismatch detected, loading setup");

            // Equipment
            var items = inventorySetup.getEquipmentItems();
            for (var item : items) {
                if (item != null && item.getId() != -1) {
                    Rs2Bank.wearItem(item.getName(), true);
                }
            }

            // Inventory
            loadInventoryWithTeleportSupport();
            return;
        }

        // Verify teleport item before closing bank
        if (!hasTeleportItem()) {
            log.error("No teleport item available! Cannot safely enter wilderness.");
            Microbot.log("No teleport item found! Please add one to your bank.");
            shutdown();
            return;
        }

        log.debug("Inventory setup matches, closing bank");
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.between(1600, 2200));

        lastBankTime = System.currentTimeMillis();

        // Handle PKer escape world hop
        if (escapedFromPker) {
            log.info("PKer escape: Hopping worlds at bank");
            int maxRetries = 6;
            boolean hopSuccessful = false;
            int initialWorld = Microbot.getClient().getWorld();

            for (int attempt = 1; attempt <= maxRetries && !hopSuccessful; attempt++) {
                int currentWorld = Microbot.getClient().getWorld();
                if (currentWorld != initialWorld && attempt > 1) {
                    log.info("Hop succeeded - now on world {}", currentWorld);
                    lastHopTime = System.currentTimeMillis();
                    hopSuccessful = true;
                    break;
                }

                Microbot.getClient().openWorldHopper();
                Global.sleep(300, 500);

                int newWorld = Login.getRandomWorld(true, null);
                log.info("Hopping from world {} to {} (attempt {}/{})", currentWorld, newWorld, attempt, maxRetries);

                Microbot.hopToWorld(newWorld);
                Global.sleep(800, 1200);

                sleepUntil(() -> Microbot.getClient().getGameState() == GameState.HOPPING, 5000);

                if (Microbot.getClient().getGameState() == GameState.HOPPING) {
                    sleepUntil(() -> Microbot.getClient().getGameState() == GameState.LOGGED_IN, 10000);
                    if (Microbot.getClient().getGameState() == GameState.LOGGED_IN) {
                        log.info("Hop successful - now on world {}", Microbot.getClient().getWorld());
                        lastHopTime = System.currentTimeMillis();
                        hopSuccessful = true;
                    }
                }

                if (!hopSuccessful && attempt < maxRetries) {
                    Global.sleep(2500, 3500);
                }
            }

            escapedFromPker = false;
            log.info("PKer escape protocol complete");
        }

        // Travel to zombie pirates
        travelToZombiePirates();
    }

    private void travelToZombiePirates() {
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
            case FEROX_ENCLAVE_RUN:
                // Just walk - Rs2Walker will handle it
                break;
            default:
                useLumberyardTeleport(currentLocation);
        }
    }

    private void useLumberyardTeleport(WorldPoint currentLocation) {
        Rs2ItemModel lumberyardTab = Rs2Inventory.get(LUMBERYARD_TELE);
        if (lumberyardTab != null) {
            log.info("Using Lumberyard teleport tab");
            if (Rs2Inventory.interact(lumberyardTab, "Teleport")) {
                sleepUntil(() -> !Rs2Player.getWorldLocation().equals(currentLocation), Rs2Random.between(5000, 7000));
                log.info("Teleported to Lumberyard area");
            }
        } else {
            log.error("No Lumberyard teleport tab found");
        }
    }

    private void useBurningAmulet(WorldPoint currentLocation) {
        int burningAmuletId = -1;
        for (int id : BURNING_AMULET_IDS) {
            if (Rs2Inventory.hasItem(id)) {
                burningAmuletId = id;
                break;
            }
        }

        if (burningAmuletId == -1) {
            log.error("No Burning Amulet found!");
            return;
        }

        log.info("Using Burning Amulet to Chaos Temple");
        if (Rs2Inventory.interact(burningAmuletId, "Chaos Temple")) {
            sleepUntil(() -> !Rs2Player.getWorldLocation().equals(currentLocation), Rs2Random.between(5000, 7000));
            log.info("Teleported to Chaos Temple area");
        }
    }

    private boolean doesInventoryMatchWithTeleportException() {
        var setupItems = inventorySetup.getInventoryItems();

        Map<Integer, Integer> requiredByItemId = new HashMap<>();
        boolean hasTeleportInSetup = false;

        for (var setupItem : setupItems) {
            if (setupItem == null || setupItem.getId() == -1) continue;

            String itemName = setupItem.getName() != null ? setupItem.getName().toLowerCase() : "";

            boolean isTeleportItem = itemName.contains("amulet of glory") ||
                                     itemName.contains("ring of dueling");

            if (isTeleportItem) {
                hasTeleportInSetup = true;
                continue;
            }

            int itemId = setupItem.getId();
            int qty = setupItem.getQuantity();
            requiredByItemId.merge(itemId, qty, Integer::sum);
        }

        if (hasTeleportInSetup && !hasTeleportItem()) {
            return false;
        }

        for (Map.Entry<Integer, Integer> entry : requiredByItemId.entrySet()) {
            int itemId = entry.getKey();
            int required = entry.getValue();

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

    private void loadInventoryWithTeleportSupport() {
        var setupItems = inventorySetup.getInventoryItems();

        log.debug("Starting custom inventory load with teleport support");

        Map<Integer, Integer> requiredByItemId = new HashMap<>();
        Map<Integer, String> itemNames = new HashMap<>();
        boolean hasTeleportInSetup = false;

        for (var setupItem : setupItems) {
            if (setupItem == null || setupItem.getId() == -1) continue;

            String itemName = setupItem.getName() != null ? setupItem.getName().toLowerCase() : "";

            boolean isTeleportItem = itemName.contains("amulet of glory") ||
                                     itemName.contains("ring of dueling");

            if (isTeleportItem) {
                hasTeleportInSetup = true;
                continue;
            }

            int itemId = setupItem.getId();
            int qty = setupItem.getQuantity();
            requiredByItemId.merge(itemId, qty, Integer::sum);
            itemNames.putIfAbsent(itemId, setupItem.getName());
        }

        if (hasTeleportInSetup) {
            handleTeleportItemInInventory();
        }

        for (Map.Entry<Integer, Integer> entry : requiredByItemId.entrySet()) {
            int itemId = entry.getKey();
            int required = entry.getValue();
            String name = itemNames.getOrDefault(itemId, "Unknown");

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
                log.info("Have {} excess of {} (ID: {}), depositing", -needed, name, itemId);
                Rs2Bank.depositX(itemId, -needed);
                Global.sleep(300, 600);
            }
        }
    }

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
                correctIds = GLORY_IDS;
                wrongIds = RING_OF_DUELING_IDS;
                correctItemName = "Amulet of Glory";
                wrongItemName = "Ring of Dueling";
        }

        // Deposit wrong teleport items
        for (int wrongId : wrongIds) {
            if (Rs2Inventory.hasItem(wrongId)) {
                log.info("Depositing {} (ID: {})", wrongItemName, wrongId);
                Rs2Bank.depositOne(wrongId);
                Global.sleep(300, 500);
            }
        }

        // Deposit uncharged glory if using glory
        if (method == ZombiePirateLockerConfig.TeleportMethod.GLORY_EDGEVILLE && Rs2Inventory.hasItem(UNCHARGED_GLORY)) {
            log.info("Found uncharged glory, depositing");
            Rs2Bank.depositOne(UNCHARGED_GLORY);
            Global.sleep(300, 500);
        }

        // Check if we already have correct teleport item
        for (int correctId : correctIds) {
            if (Rs2Inventory.hasItem(correctId)) {
                log.debug("Already have charged {} (ID: {})", correctItemName, correctId);
                return;
            }
        }

        // Withdraw teleport item
        log.info("No {} in inventory, withdrawing one", correctItemName);
        for (int teleportId : correctIds) {
            if (Rs2Bank.hasItem(teleportId)) {
                log.info("Withdrawing {} (ID: {})", correctItemName, teleportId);
                Rs2Bank.withdrawOne(teleportId);
                final int idToCheck = teleportId;
                sleepUntil(() -> Rs2Inventory.hasItem(idToCheck), Rs2Random.between(800, 1600));
                return;
            }
        }

        log.warn("No charged {} found in bank!", correctItemName);
    }

    private void openChest() {
        if (Rs2Inventory.count(ZOMBIE_KEY) == 0) {
            log.debug("No zombie keys in inventory, cannot open chest");
            return;
        }

        if (!hasTeleportItem()) {
            log.warn("No teleport item available! Returning to bank for safety.");
            return;
        }

        if (Rs2Player.isAnimating()) {
            log.debug("Player is animating, waiting");
            return;
        }

        long timeSinceLastInteraction = System.currentTimeMillis() - lastChestInteraction;
        if (timeSinceLastInteraction < CHEST_INTERACTION_COOLDOWN) {
            return;
        }

        var chest = Rs2GameObject.findObjectById(CHEST_CLOSED);
        if (chest == null) {
            log.debug("No closed chest found nearby");
            return;
        }

        log.debug("Opening chest");
        chestOpened = false;
        lastChestInteraction = System.currentTimeMillis();

        if (Rs2GameObject.interact(chest, "Open")) {
            boolean messageReceived = sleepUntil(() -> chestOpened, Rs2Random.between(2800, 3100));

            if (messageReceived) {
                keysUsed++;
                log.info("Chest opened! Keys used: {}", keysUsed);
            } else {
                log.warn("Chest opening message not received");
            }
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
                        sleepUntil(() -> Rs2Player.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS) > currentHealth, 3000);
                    }
                }
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
