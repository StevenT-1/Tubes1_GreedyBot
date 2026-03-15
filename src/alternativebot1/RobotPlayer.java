package benibot;

import battlecode.common.*;
import java.util.Random;



public class RobotPlayer {
    static int turnCount = 0; // Tracks current round number across all robot types

    static final Random rng = new Random(6147); // Seeded RNG for consistent random movement

    static int builderState = 0; // Control builder job (0 = searching, 1 = traveling, 2 = building tower, 3 = building SRP)
    static int storedRuinMsg = -1; // Stores the last ruin location message received from soldiers/moppers, encoded as x*100+y+1
    static MapLocation targetRuinLocation = null; // Stores the MapLocation of the ruin it's currently assigned to build
    static boolean isBuilder = false; // True = this soldier is the dedicated builder, False = this soldier is a painter
    static boolean patternMarked = false; // Used by builder to track if it already called markTowerPattern()
    static MapLocation towerLocation = null; // Stores the MapLocation of the home paint tower
    static Direction exploreDir = null; // Remembers current exploration direction
    static MapLocation enemyTileHint = null; // Stores enemy paint tile location hint received from tower, used by splasher
    static int splasherState = 0; // Control splasher job (0 = exploring, 1 = traveling, 2 = attacking, 3 = refilling)
    static Direction wallSlideDir = null; // direction to slide when blocked by wall
    static MapLocation storedRuinToReport = null; // Stores ruin location found by soldier/mopper, waiting to be sent to tower
    static MapLocation storedEnemyTileToReport = null; // Stores enemy paint tile found by soldier/mopper, waiting to be sent to tower
    static boolean needsHelp = false; // True when builder encounters enemies during tower construction, triggers help request
    static boolean helpRequested = false; // True when tower has received a help request from a builder, triggers splasher spawn
    static UnitType chosenTowerType = null; // Stores the decided tower type for current build job, locked until build completes
    static MapLocation srpCenter = null; // Stores the center location of the SRP currently being built
    static boolean srpMarked = false; // True when builder has already called markResourcePattern() for current SRP
    static int idleTurns = 0; // Counts turns builder has been idle near tower, triggers SRP building after 10 turns
    static int storedSRPMsg = -1; // Stores last SRP location message received from soldiers, encoded as 10000 + x*1000 + y
    static MapLocation storedSRPToReport = null; // Stores valid SRP center found by soldier/mopper, waiting to be sent to tower

    /** Array containing all the possible movement directions. */
    static final Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    /**
     * run() is the method that is called when a robot is instantiated in the Battlecode world.
     * It is like the main function for your robot. If this method returns, the robot dies!
     *
     * @param rc  The RobotController object. You use it to perform actions from this robot, and to get
     *            information on its current status. Essentially your portal to interacting with the world.
     **/
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        System.out.println("I'm alive");

        rc.setIndicatorString("Hello world!");

        while (true) {
            turnCount += 1;

            try {
                switch (rc.getType()){
                    case SOLDIER: runSoldier(rc); break; 
                    case MOPPER: runMopper(rc); break;
                    case SPLASHER: runSplasher(rc); break;
                    default: runTower(rc); break;
                    }
                }
             catch (GameActionException e) {
                System.out.println("GameActionException");
                e.printStackTrace();

            } catch (Exception e) {
                System.out.println("Exception");
                e.printStackTrace();

            } finally {
                Clock.yield();
            }
        }
    }

    // Tower Logic
    public static void runTower(RobotController rc) throws GameActionException {
        // Read incoming ruin reports from soldiers/moppers
        Message[] incomingMsgs = rc.readMessages(-1);
        for (Message msg : incomingMsgs) {
            int data = msg.getBytes();
            if (data == -99999) {
                helpRequested = true;
            } else if (data >= 10000) {
                storedSRPMsg = data;
            } else if (data > 0) {
                storedRuinMsg = data;
            } else if (data < 0) {
                int x = (-data-1) / 100;
                int y = (-data-1) % 100;
                enemyTileHint = new MapLocation(x, y);
            }
        }

        // Forward stored ruin location to nearby builder soldier
        if (storedRuinMsg > 0) {
            RobotInfo[] nearbyAllies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : nearbyAllies) {
                if (ally.getType() == UnitType.SOLDIER &&
                    rc.getLocation().distanceSquaredTo(ally.getLocation()) <= 20) {
                    if (rc.canSendMessage(ally.getLocation(), storedRuinMsg)) {
                        rc.sendMessage(ally.getLocation(), storedRuinMsg);
                        storedRuinMsg = -1;
                        break;
                    }
                }
            }
        }

        // Forward stored SRP location to nearby builder soldier
        if (storedSRPMsg > 0) {
            RobotInfo[] nearbyAllies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : nearbyAllies) {
                if (ally.getType() == UnitType.SOLDIER &&
                    rc.getLocation().distanceSquaredTo(ally.getLocation()) <= 20) {
                    if (rc.canSendMessage(ally.getLocation(), storedSRPMsg)) {
                        rc.sendMessage(ally.getLocation(), storedSRPMsg);
                        storedSRPMsg = -1;
                        break;
                    }
                }
            }
        }

        // Forward enemy tile to nearby splasher 
        if (enemyTileHint != null) {
            RobotInfo[] nearbyAllies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : nearbyAllies) {
                if (ally.getType() == UnitType.SPLASHER &&
                    rc.getLocation().distanceSquaredTo(ally.getLocation()) <= 20) {
                    int encoded = -(enemyTileHint.x * 100 + enemyTileHint.y + 1);
                    if (rc.canSendMessage(ally.getLocation(), encoded)) {
                        rc.sendMessage(ally.getLocation(), encoded);
                        enemyTileHint = null;
                        break;
                    }
                }
            }
        }

        // Attack nearest enemy
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            MapLocation enemyLoc = enemies[0].getLocation();
            if (rc.canAttack(enemyLoc)) {
                rc.attack(enemyLoc);
            }
        }
        
        // Upgrade tower if enough chips
        if (rc.getChips() > 3500 && rc.canUpgradeTower(rc.getLocation())) {
            rc.upgradeTower(rc.getLocation());
        }

        // Count ally soldiers, moppers and splashers
        int soldierCount = 0;
        int mopperCount = 0;
        int splasherCount = 0;
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.SOLDIER) soldierCount++;
            if (ally.getType() == UnitType.MOPPER)  mopperCount++;
            if (ally.getType() == UnitType.SPLASHER) splasherCount++;
        }

        // Calculate paint coverage around tower area
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        int totalTiles = 0;
        int paintedTiles = 0;
        for (MapInfo tile : nearbyTiles) {
            if (!tile.isWall() && !tile.hasRuin()) {
                totalTiles++;
                if (tile.getPaint().isAlly()) paintedTiles++;
            }
        }
        double coverage = totalTiles > 0 ? (double) paintedTiles / totalTiles * 100 : 0;

        // Stop spawning if paint reserve too low
        if (rc.getPaint() < 200) return;
        // Only paint tower can spawning
        if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER ||
            rc.getType() == UnitType.LEVEL_TWO_PAINT_TOWER ||
            rc.getType() == UnitType.LEVEL_THREE_PAINT_TOWER){
            // Greedy spawn decision
            boolean spawnMopper = false;
            boolean spawnSplasher = false;
            if (helpRequested && splasherCount < 1) {
                spawnSplasher = true;
                helpRequested = false;
            } else if (enemyTileHint != null && splasherCount < 2) {
                spawnSplasher = true;
            } else if (coverage >= 15 && soldierCount >= 4 && mopperCount < 2) {
                spawnMopper = true;
            } else if (coverage >= 5 && soldierCount >= 2 && mopperCount < 1) {
                spawnMopper = true;
            }

            // Try to spawn in each direction
            for (Direction dir : directions) {
                MapLocation spawnLoc = rc.getLocation().add(dir);
                if (spawnSplasher) {
                    if (rc.getPaint() > 200 && rc.canBuildRobot(UnitType.SPLASHER, spawnLoc)) {
                        rc.buildRobot(UnitType.SPLASHER, spawnLoc);
                        // Send enemy tile information
                        if (enemyTileHint != null) {
                            int encoded = -(enemyTileHint.x * 100 + enemyTileHint.y + 1);
                            if (rc.canSendMessage(spawnLoc, encoded)) {
                                rc.sendMessage(spawnLoc, encoded);
                            }
                        }
                        System.out.println("Built a SPLASHER");
                        break;
                    }
                } else if (spawnMopper) {
                    if (rc.getPaint() > 200 && rc.canBuildRobot(UnitType.MOPPER, spawnLoc)) {
                        rc.buildRobot(UnitType.MOPPER, spawnLoc);
                        System.out.println("Built a MOPPER");
                        break;
                    }
                } else {
                    if (rc.getPaint() > 200 && rc.canBuildRobot(UnitType.SOLDIER, spawnLoc)) {
                        rc.buildRobot(UnitType.SOLDIER, spawnLoc);
                        System.out.println("Built a SOLDIER");
                        break;
                    }
                }
            }
        }
    }

    // Soldier logic
    public static void runSoldier(RobotController rc) throws GameActionException {
        // Save tower location on first turn
        if (towerLocation == null) {
            RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : allies) {
                if (ally.getType() == UnitType.LEVEL_ONE_PAINT_TOWER ||
                    ally.getType() == UnitType.LEVEL_TWO_PAINT_TOWER ||
                    ally.getType() == UnitType.LEVEL_THREE_PAINT_TOWER) {
                    towerLocation = ally.getLocation();
                    break;
                }
            }
        }

        // Decide builder role on first turn only
        if (turnCount == 1) {
            RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
            int lowerIDCount = 0;
            for (RobotInfo ally : allies) {
                if (ally.getType() == UnitType.SOLDIER && ally.getID() < rc.getID()) {
                    lowerIDCount++;
                }
            }
            isBuilder = (lowerIDCount == 1);
        }

        int maxPaint = rc.getType().paintCapacity;
        int currentPaint = rc.getPaint();
        int paintPercent = currentPaint * 100 / maxPaint;

        // Greedy Decision
        reportRuinToTower(rc);
        reportEnemyTileToTower(rc);
        reportSRPToTower(rc);
        if (paintPercent < 60) {
            rc.setIndicatorString("Low paint! Retreating to refill");
            retreatToRefill(rc);
        } else if (isBuilder) {
            runBuilder(rc);
        } else {
            rc.setIndicatorString("Painting! Paint: " + currentPaint);
            paintNearest(rc, rc.getID());
        }
    }
    // Builder logic (subjob soldier)
    static void runBuilder(RobotController rc) throws GameActionException {
        // Searching : walk to tower, wait for ruin order
        if (builderState == 0) {
            rc.setIndicatorString("Builder: waiting for orders");
            // Check message from tower with ruin location
            Message[] messages = rc.readMessages(-1);
            for (Message msg : messages) {
                if (msg.getBytes() >= 10000) {
                    int decoded = msg.getBytes() - 10000;
                    int x = decoded / 1000;
                    int y = decoded % 1000;
                    srpCenter = new MapLocation(x, y);
                    idleTurns = 0;
                    builderState = 3;
                } else if (msg.getBytes() > 0) {
                    int x = (msg.getBytes()-1) / 100;
                    int y = (msg.getBytes()-1) % 100;
                    targetRuinLocation = new MapLocation(x, y);
                    idleTurns = 0;
                    builderState = 1;

                }
            }

            // Check if ruin already visible
            if (builderState == 0) {
                MapInfo[] nearby = rc.senseNearbyMapInfos();
                for (MapInfo tile : nearby) {
                    if (tile.hasRuin() && rc.senseRobotAtLocation(tile.getMapLocation()) == null) {
                        MapLocation ruinLoc = tile.getMapLocation();
                        targetRuinLocation = ruinLoc;
                        idleTurns = 0;
                        builderState = 2;
                        break;
                    }
                }
            }

            // Walk back to tower while waiting
            if (builderState == 0 && towerLocation != null &&
                rc.getLocation().distanceSquaredTo(towerLocation) > 2) {
                moveToward(rc, towerLocation);
            } else if (builderState == 0) {
                // Paint nearby tiles while waiting
                paintNearest(rc, rc.getID());
                idleTurns++;
                if (idleTurns >= 10) {
                    idleTurns = 0;
                    builderState = 3;
                }
            }
            return;
        }

        // Traveling : walk toward known ruin
        if (builderState == 1) {
            rc.setIndicatorString("Builder: traveling to ruin");
            if (targetRuinLocation == null) { builderState = 0; return; }

            // Check if ruin already has tower
            MapInfo[] nearby = rc.senseNearbyMapInfos();
            for (MapInfo tile : nearby) {
                if (tile.hasRuin() && tile.getMapLocation().equals(targetRuinLocation)) {
                    if (rc.senseRobotAtLocation(tile.getMapLocation()) != null) {
                        targetRuinLocation = null;
                        builderState = 0;
                        return;
                    }
                    builderState = 2;
                    break;
                }
            }
            moveToward(rc, targetRuinLocation);
            return;
        }

        // Building Tower : paint and complete tower
        if (builderState == 2) {
            rc.setIndicatorString("Builder: building tower!");
            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            if (enemies.length > 0) {
                needsHelp = true;
            }
            boolean stillBuilding = findAndBuildTower(rc);
            if (!stillBuilding) {
                targetRuinLocation = null;
                patternMarked = false;
                builderState = 0;
                chosenTowerType = null;
            }
        }
        // Building SRP : paint and complete SRP
        if (builderState == 3) {
            rc.setIndicatorString("Builder: building SRP!");

            // Find valid SRP center if not set yet
            if (srpCenter == null) {
                if (rc.canMarkResourcePattern(rc.getLocation()) && !hasMarksNearby(rc, rc.getLocation())) {
                    srpCenter = rc.getLocation();
                } else {
                    for (Direction dir : directions) {
                        MapLocation candidate = rc.getLocation().add(dir);
                        if (rc.canMarkResourcePattern(candidate) && !hasMarksNearby(rc, candidate)) {
                            srpCenter = candidate;
                            break;
                        }
                    }
                }
                if (srpCenter == null) {
                    builderState = 0;
                    return;
                }
            }

            if (rc.getLocation().distanceSquaredTo(srpCenter) > 2) {
                moveToward(rc, srpCenter);
                return;
            }

            // Mark pattern if not marked yet
            if (!srpMarked && rc.canMarkResourcePattern(srpCenter)) {
                rc.markResourcePattern(srpCenter);
                srpMarked = true;
                return;
            }

            // Paint all tiles where mark doesn't match paint
            boolean allPainted = true;
            for (MapInfo tile : rc.senseNearbyMapInfos(srpCenter, 8)) {
                if (tile.getMark() != PaintType.EMPTY &&
                    tile.getMark() != tile.getPaint()) {
                    allPainted = false;
                    if (!rc.canAttack(tile.getMapLocation())) {
                        moveToward(rc, tile.getMapLocation());
                        return;
                    }
                    boolean useSecondary = tile.getMark() == PaintType.ALLY_SECONDARY;
                    if (rc.isActionReady()) {
                        rc.attack(tile.getMapLocation(), useSecondary);
                    }
                    return;
                }
            }

            // Complete SRP if all painted and enough chips
            if (allPainted) {
                if (rc.getChips() >= 200 && rc.canCompleteResourcePattern(srpCenter)) {
                    rc.completeResourcePattern(srpCenter);
                    System.out.println("Completed SRP at " + srpCenter);
                    srpCenter = null;
                    srpMarked = false;
                    builderState = 0;
                }
            }
        }
    }

    // Soldier retreats to tower for refill
    static void retreatToRefill(RobotController rc) throws GameActionException {
        int maxPaint = rc.getType().paintCapacity;
        int currentPaint = rc.getPaint();

        if (towerLocation != null) {
            if (rc.getLocation().distanceSquaredTo(towerLocation) <= 2) {
                if (currentPaint < maxPaint * 9 / 10) {
                    int needed = maxPaint - currentPaint;
                    if (rc.canTransferPaint(towerLocation, -needed)) {
                        rc.transferPaint(towerLocation, -needed);
                        rc.setIndicatorString("Refilling at tower...");
                    }
                }
                // Send help signal if needed
                if (needsHelp) {
                    if (rc.canSendMessage(towerLocation, -99999)) {
                        rc.sendMessage(towerLocation, -99999);
                        needsHelp = false;
                        targetRuinLocation = null;
                        patternMarked = false;
                        chosenTowerType = null;
                        srpCenter = null;
                        srpMarked = false;
                        builderState = 0;
                    }
                }
                return;
            }

            // Paint current tile first to maintain paint connection to tower
            MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
            if (!currentTile.getPaint().isAlly() && rc.isActionReady() && rc.canAttack(rc.getLocation())) {
                rc.attack(rc.getLocation());
            }
            moveToward(rc, towerLocation);
        }
    }

    // Soldier finds nearest unpainted tile and paints it
    static void paintNearest(RobotController rc, int robotID) throws GameActionException {
    // Each soldier picks a quadrant based on their ID
    int mapWidth = rc.getMapWidth();
    int mapHeight = rc.getMapHeight();
    int quadrant = robotID % 4;
    
    // Target different corners based on quadrant
    MapLocation target;
    switch (quadrant) {
        case 0: target = new MapLocation(mapWidth - 1, mapHeight - 1); break;
        case 1: target = new MapLocation(0, mapHeight - 1); break;
        case 2: target = new MapLocation(mapWidth - 1, 0); break;
        default: target = new MapLocation(mapWidth / 2, mapHeight / 2); break;
    }
    
    // Find unpainted tile closest to their assigned corner
    MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
    MapLocation nearest = null;
    int minDist = Integer.MAX_VALUE;
    for (MapInfo tile : nearbyTiles) {
        if (!tile.isWall() && !tile.hasRuin() && !tile.getPaint().isAlly() && !tile.getPaint().isEnemy()) {
            int dist = target.distanceSquaredTo(tile.getMapLocation());
            if (dist < minDist) {
                minDist = dist;
                nearest = tile.getMapLocation();
            }
        }
    }

        if (nearest != null) {
            // Attack/paint the nearest unpainted tile
            if (rc.isActionReady() && rc.canAttack(nearest)) {
                rc.attack(nearest);
            } else if (rc.isMovementReady()) {
                // Move toward it
                moveToward(rc, nearest);
                // Paint tile under feet while moving
                MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
                if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
                    rc.attack(rc.getLocation());
                }
            }
        } else {
            // No unpainted tile visible do random walk
            if (exploreDir == null || !rc.canMove(exploreDir)) {
                // Pick new random direction when no direction set or blocked
                Direction newDir = directions[rng.nextInt(directions.length)];
                int attempts = 0;
                while (!rc.canMove(newDir) && attempts < 8) {
                    newDir = directions[rng.nextInt(directions.length)];
                    attempts++;
                }
                exploreDir = newDir;
            }
            if (rc.isMovementReady() && rc.canMove(exploreDir)) {
                rc.move(exploreDir);
            }
            rc.setIndicatorString("Exploring: " + exploreDir);

            // Always paint under feet
            MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
            if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
                rc.attack(rc.getLocation());
            }
        }
    }
    // Tower construction process   
    static boolean findAndBuildTower(RobotController rc) throws GameActionException {
        // Check the ruin location
        if (targetRuinLocation == null) return false;
        MapLocation ruinLoc = targetRuinLocation;
        if (rc.canSenseLocation(ruinLoc) && rc.senseRobotAtLocation(ruinLoc) != null) {
            targetRuinLocation = null;
            patternMarked = false;
            chosenTowerType = null;
            return false;
        }

        // Move to ruin
        if (rc.getLocation().distanceSquaredTo(ruinLoc) > 2) {
            moveToward(rc, ruinLoc);
            return true;
        }

        // Decide tower type
        if (chosenTowerType == null) {
            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            boolean enemyNearby = enemies.length > 0;
            if (enemyNearby) {
                chosenTowerType = UnitType.LEVEL_ONE_DEFENSE_TOWER;
            } else if (rc.getID() % 5 == 0) {
                chosenTowerType = UnitType.LEVEL_ONE_MONEY_TOWER;
            } else {
                chosenTowerType = UnitType.LEVEL_ONE_PAINT_TOWER;
            }
        }           
        UnitType towerType = chosenTowerType;

        // Mark pattern if not marked
        if (!patternMarked) {
            if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
                rc.markTowerPattern(towerType, ruinLoc);
                patternMarked = true;
                return true;
            }
        }

        // Paint unpainted/wrong tiles
        boolean allPainted = true;
        for (MapInfo tile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (tile.getMark() != PaintType.EMPTY && 
                tile.getMark() != tile.getPaint()) {
                allPainted = false;
                if (!rc.canAttack(tile.getMapLocation())) {
                    moveToward(rc, tile.getMapLocation());
                    return true;
                }
                boolean useSecondary = tile.getMark() == PaintType.ALLY_SECONDARY;
                if (rc.isActionReady()) {
                    rc.attack(tile.getMapLocation(), useSecondary);
                }
                return true;
            }
        }

        // Complete tower
        if (allPainted && rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
            patternMarked = false;
            chosenTowerType = null;
            System.out.println("Built " + towerType + " at " + ruinLoc);
            return false;
        }
        paintNearest(rc, rc.getID());
        return true;
    }
    // Splasher logic
    public static void runSplasher(RobotController rc) throws GameActionException {
        if (towerLocation == null) {
            RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : allies) {
                if (ally.getType() == UnitType.LEVEL_ONE_PAINT_TOWER ||
                    ally.getType() == UnitType.LEVEL_TWO_PAINT_TOWER ||
                    ally.getType() == UnitType.LEVEL_THREE_PAINT_TOWER) {
                    towerLocation = ally.getLocation();
                    break;
                }
            }
        }

        int maxPaint = rc.getType().paintCapacity;
        int currentPaint = rc.getPaint();
        int paintPercent = currentPaint * 100 / maxPaint;

        // State 3: Refilling
        if (splasherState == 3) {
            rc.setIndicatorString("Splasher: refilling");
            if (towerLocation != null) {
                if (rc.getLocation().distanceSquaredTo(towerLocation) <= 2) {
                    // Refill paint
                    if (currentPaint < maxPaint * 9 / 10) {
                        int needed = maxPaint - currentPaint;
                        if (rc.canTransferPaint(towerLocation, -needed)) {
                            rc.transferPaint(towerLocation, -needed);
                        }
                    }
                    // Read hint from tower
                    Message[] messages = rc.readMessages(-1);
                    for (Message msg : messages) {
                        if (msg.getBytes() < 0 && msg.getBytes() != -99999) {
                            int x = (-msg.getBytes() - 1) / 100;
                            int y = (-msg.getBytes() - 1) % 100;
                            enemyTileHint = new MapLocation(x, y);
                            splasherState = 1;
                            return;
                        }
                    }
                    // No hint -> go explore
                    if (currentPaint >= maxPaint * 9 / 10) {
                        splasherState = 0;
                    }
                } else {
                    moveToward(rc, towerLocation);
                }
            }
            return;
        }

        // Retreat if paint low
        if (paintPercent < 30) {
            splasherState = 3;
            rc.setIndicatorString("Splasher: low paint retreating");
            return;
        }

        // State 0: Exploring
        if (splasherState == 0) {
            rc.setIndicatorString("Splasher: exploring");
            // Read hint from tower
            Message[] messages = rc.readMessages(-1);
            for (Message msg : messages) {
                if (msg.getBytes() < 0 && msg.getBytes() != -99999) {
                    int x = (-msg.getBytes() - 1) / 100;
                    int y = (-msg.getBytes() - 1) % 100;
                    enemyTileHint = new MapLocation(x, y);
                    splasherState = 1;
                    return;
                }
            }
            // Check if enemy tiles visible
            MapInfo[] nearby = rc.senseNearbyMapInfos();
            for (MapInfo tile : nearby) {
                if (tile.getPaint().isEnemy()) {
                    splasherState = 2;
                    return;
                }
            }
            // Explore randomly
            if (exploreDir == null || !rc.canMove(exploreDir)) {
                Direction newDir = directions[rng.nextInt(directions.length)];
                int attempts = 0;
                while (!rc.canMove(newDir) && attempts < 8) {
                    newDir = directions[rng.nextInt(directions.length)];
                    attempts++;
                }
                exploreDir = newDir;
            }
            if (rc.isMovementReady() && rc.canMove(exploreDir)) {
                rc.move(exploreDir);
            }
            return;
        }

        // State 1: Moving toward hint
        if (splasherState == 1) {
            rc.setIndicatorString("Splasher: moving to hint");
            // Check if enemy tiles visible
            MapInfo[] nearby = rc.senseNearbyMapInfos();
            for (MapInfo tile : nearby) {
                if (tile.getPaint().isEnemy()) {
                    splasherState = 2;
                    return;
                }
            }
            if (enemyTileHint == null) {
                splasherState = 0;
                return;
            }
            // Arrived at hint area but no enemy tiles
            if (rc.getLocation().distanceSquaredTo(enemyTileHint) <= 4) {
                enemyTileHint = null;
                splasherState = 0;
                return;
            }
            moveToward(rc, enemyTileHint);
            return;
        }

        // State 2: Attacking
        if (splasherState == 2) {
            rc.setIndicatorString("Splasher: attacking enemy tiles!");
            // Find best center maximizing enemy tiles
            MapInfo[] nearby = rc.senseNearbyMapInfos();
            MapLocation bestCenter = null;
            int maxEnemyCount = 0;
            for (MapInfo candidate : nearby) {
                if (rc.getLocation().distanceSquaredTo(candidate.getMapLocation()) > 4) continue;
                if (!rc.canAttack(candidate.getMapLocation())) continue;
                int count = 0;
                for (MapInfo tile : nearby) {
                    if (candidate.getMapLocation().distanceSquaredTo(tile.getMapLocation()) <= 2
                        && tile.getPaint().isEnemy()) {
                        count++;
                    }
                }
                if (count > maxEnemyCount) {
                    maxEnemyCount = count;
                    bestCenter = candidate.getMapLocation();
                }
            }
            if (bestCenter != null && maxEnemyCount > 0) {
                if (rc.isActionReady() && rc.canAttack(bestCenter)) {
                    rc.attack(bestCenter);
                } else {
                    moveToward(rc, bestCenter);
                }
            } else {
                // No attackable center found 
                MapLocation nearestEnemy = null;
                int minDist = Integer.MAX_VALUE;
                for (MapInfo tile : nearby) {
                    if (tile.getPaint().isEnemy()) {
                        int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                        if (dist < minDist) {
                            minDist = dist;
                            nearestEnemy = tile.getMapLocation();
                        }
                    }
                }
                if (nearestEnemy != null) {
                    moveToward(rc, nearestEnemy);
                } else {
                    splasherState = 0;
                }
            }
        }
    }

    // Mopper logic
    public static void runMopper(RobotController rc) throws GameActionException {
        // Save tower location on spawn
        if (towerLocation == null) {
            RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : allies) {
                if (ally.getType() == UnitType.LEVEL_ONE_PAINT_TOWER ||
                    ally.getType() == UnitType.LEVEL_TWO_PAINT_TOWER ||
                    ally.getType() == UnitType.LEVEL_THREE_PAINT_TOWER) {
                    towerLocation = ally.getLocation();
                    break;
                }
            }
        }

        int maxPaint     = rc.getType().paintCapacity;
        int currentPaint = rc.getPaint();
        int paintPercent = currentPaint * 100 / maxPaint;

        // Greedy decision
        reportRuinToTower(rc);
        reportEnemyTileToTower(rc);
        reportSRPToTower(rc);
        if (paintPercent < 30) {
            // Own paint low do retreat to tower
            rc.setIndicatorString("Mopper retreating to refill");
            mopperRefill(rc);
            return;
        }

        // Find furthest painter soldier to pair with
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo pairedSoldier = null;
        int maxDist = Integer.MIN_VALUE;
        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.SOLDIER) {
                int dist = towerLocation != null ?
                    towerLocation.distanceSquaredTo(ally.getLocation()) : 0;
                if (dist > maxDist) {
                    maxDist = dist;
                    pairedSoldier = ally;
                }
            }
        }
        // Mop nearest enemy paint tile
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapLocation enemyPaint = null;
        int minEnemyDist = Integer.MAX_VALUE;
        for (MapInfo tile : nearbyTiles) {
            if (tile.getPaint().isEnemy()) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < minEnemyDist) {
                    minEnemyDist = dist;
                    enemyPaint = tile.getMapLocation();
                }
            }
        }
        if (enemyPaint != null) {
            rc.setIndicatorString("Mopping enemy paint!");
            if (rc.isActionReady() && rc.canAttack(enemyPaint)) {
                rc.attack(enemyPaint);
            } else {
                moveToward(rc, enemyPaint);
            }
            return;
        }

        // Transfer paint to paired soldier if low
        if (pairedSoldier != null) {
            int soldierPaint   = pairedSoldier.getPaintAmount();
            int soldierMax     = UnitType.SOLDIER.paintCapacity;
            int soldierPercent = soldierPaint * 100 / soldierMax;

            if (soldierPercent < 60) {
                int transferAmount = Math.min(
                    rc.getPaint() - 30,
                    soldierMax - soldierPaint
                );
                if (transferAmount > 0 &&
                    rc.canTransferPaint(pairedSoldier.getLocation(), transferAmount)) {
                    rc.transferPaint(pairedSoldier.getLocation(), transferAmount);
                    rc.setIndicatorString("Transferred paint to paired soldier!");
                    return;
                }
            }

            // Follow paired soldier
            rc.setIndicatorString("Following paired soldier");
            moveToward(rc, pairedSoldier.getLocation());
            return;
        }

        // No soldier found do random walk to find one
            rc.setIndicatorString("Searching for soldier to pair");
            if (exploreDir == null || !rc.canMove(exploreDir)) {
                Direction newDir = directions[rng.nextInt(directions.length)];
                int attempts = 0;
                while (!rc.canMove(newDir) && attempts < 8) {
                    newDir = directions[rng.nextInt(directions.length)];
                    attempts++;
                }
                exploreDir = newDir;
            }
            if (rc.isMovementReady() && rc.canMove(exploreDir)) {
                rc.move(exploreDir);
            }
    }
        // Mopper Goes Back to Tower to Refill Paint
        static void mopperRefill(RobotController rc) throws GameActionException {
            // Tower location unknown
            if (towerLocation == null) {
                moveRandom(rc);
                RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
                for (RobotInfo ally : allies) {
                    if (ally.getType() == UnitType.LEVEL_ONE_PAINT_TOWER ||
                        ally.getType() == UnitType.LEVEL_TWO_PAINT_TOWER ||
                        ally.getType() == UnitType.LEVEL_THREE_PAINT_TOWER) {
                        towerLocation = ally.getLocation();
                    }
                }
                return;
            }

            int maxPaint     = rc.getType().paintCapacity;
            int currentPaint = rc.getPaint();
            // Tower location known, already adjacent
            if (rc.getLocation().distanceSquaredTo(towerLocation) <= 2) {
                if (currentPaint < maxPaint * 9 / 10) {
                    int needed = maxPaint - currentPaint;
                    if (rc.canTransferPaint(towerLocation, -needed)) {
                        rc.transferPaint(towerLocation, -needed);
                    }
                }
            // Tower location known, not there yet
            } else {
                moveToward(rc, towerLocation);
            }
        }

    // HELPER
    static void reportRuinToTower(RobotController rc) throws GameActionException {
        if (towerLocation == null) return;

        // Save ruin location whenever visible
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        for (MapInfo tile : nearby) {
            if (tile.hasRuin() && rc.senseRobotAtLocation(tile.getMapLocation()) == null) {
                storedRuinToReport = tile.getMapLocation();
                break;
            }
        }

        // Send stored ruin location only when near tower
        if (storedRuinToReport != null &&
            rc.getLocation().distanceSquaredTo(towerLocation) <= 20) {
            int encoded = storedRuinToReport.x * 100 + storedRuinToReport.y+1;
            if (rc.canSendMessage(towerLocation, encoded)) {
                rc.sendMessage(towerLocation, encoded);
                storedRuinToReport = null;
            }
        }
    }
    // Move toward a target location
    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        Direction dir = rc.getLocation().directionTo(target);

        if (rc.canMove(dir)) {
            wallSlideDir = null;
            rc.move(dir);
            return;
        }

        // Direct direction blocked, try wall sliding
        // If no slide direction set yet, pick perpendicular to current direction
        if (wallSlideDir == null || !rc.canMove(wallSlideDir)) {
            // Try rotating left twice (perpendicular)
            Direction slideLeft = dir.rotateLeft().rotateLeft();
            Direction slideRight = dir.rotateRight().rotateRight();
            if (rc.canMove(slideLeft)) {
                wallSlideDir = slideLeft;
            } else if (rc.canMove(slideRight)) {
                wallSlideDir = slideRight;
            } else {
                // Both perpendiculars blocked, try all directions
                for (Direction d : directions) {
                    if (rc.canMove(d)) {
                        wallSlideDir = d;
                        break;
                    }
                }
            }
        }

        // Move in slide direction
        if (wallSlideDir != null && rc.canMove(wallSlideDir)) {
            rc.move(wallSlideDir);
        }
    }

    // Move in a random valid direction
    static void moveRandom(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        Direction dir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(dir)) {
            rc.move(dir);
        }
    }
    // Report enemy paint tile location to our tower
    static void reportEnemyTileToTower(RobotController rc) throws GameActionException {
        if (towerLocation == null) return;

        // Save enemy tile location whenever visible
        if (storedEnemyTileToReport == null) {
            MapInfo[] nearby = rc.senseNearbyMapInfos();
            for (MapInfo tile : nearby) {
                if (tile.getPaint().isEnemy()) {
                    storedEnemyTileToReport = tile.getMapLocation();
                    break;
                }
            }
        }

        // Send stored enemy tile location only when near tower
        if (storedEnemyTileToReport != null &&
            rc.getLocation().distanceSquaredTo(towerLocation) <= 20) {
            int encoded = -(storedEnemyTileToReport.x * 100 + storedEnemyTileToReport.y + 1);
            if (rc.canSendMessage(towerLocation, encoded)) {
                rc.sendMessage(towerLocation, encoded);
                storedEnemyTileToReport = null;
            }
        }
    }
    static boolean hasMarksNearby(RobotController rc, MapLocation center) throws GameActionException {
        // Check if this center is already a completed SRP, allow building elsewhere
        MapInfo centerInfo = rc.senseMapInfo(center);
        if (centerInfo.isResourcePatternCenter()) return false;

        for (MapInfo tile : rc.senseNearbyMapInfos(center, 8)) {
            if (tile.getMark() != PaintType.EMPTY) {
                return true;
            }
        }
        return false;
    }
    static void reportSRPToTower(RobotController rc) throws GameActionException {
        if (towerLocation == null) return;

        // Save valid SRP location whenever visible
        if (storedSRPToReport == null) {
            MapInfo[] nearby = rc.senseNearbyMapInfos();
            for (MapInfo tile : nearby) {
                if (rc.canMarkResourcePattern(tile.getMapLocation()) &&
                    !hasMarksNearby(rc, tile.getMapLocation()) &&
                    !tile.isResourcePatternCenter()) {
                    storedSRPToReport = tile.getMapLocation();
                    break;
                }
            }
        }

        // Send stored SRP location only when near tower
        if (storedSRPToReport != null &&
            rc.getLocation().distanceSquaredTo(towerLocation) <= 20) {
            int encoded = 10000 + storedSRPToReport.x * 1000 + storedSRPToReport.y;
            if (rc.canSendMessage(towerLocation, encoded)) {
                rc.sendMessage(towerLocation, encoded);
                storedSRPToReport = null;
            }
        }
    }
}