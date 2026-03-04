package benibot;

import battlecode.common.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;


/**
 * RobotPlayer is the class that describes your main robot strategy.
 * The run() method inside this class is like your main function: this is what we'll call once your robot
 * is created!
 */
public class RobotPlayer {
    /**
     * We will use this variable to count the number of turns this robot has been alive.
     * You can use static variables like this to save any information you want. Keep in mind that even though
     * these variables are static, in Battlecode they aren't actually shared between your robots.
     */
    static int turnCount = 0;

    /**
     * A random number generator.
     * We will use this RNG to make some random moves. The Random class is provided by the java.util.Random
     * import at the top of this file. Here, we *seed* the RNG with a constant number (6147); this makes sure
     * we get the same sequence of numbers every time this code is run. This is very useful for debugging!
     */
    static final Random rng = new Random(6147);

    static int builderState = 0; // Control builder job (0 = searching, 1 = traveling, 2 = building)
    static boolean waitingForOrders = false; // Flag to track if builder is idle waiting for tower message
    static int storedRuinMsg = -1; // Stores the last ruin location message received from soldiers/moppers, encoded as x*100 + y
    static MapLocation targetRuinLocation = null; // Stores the MapLocation of the ruin it's currently assigned to build
    static boolean isBuilder = false; // True = this soldier is the dedicated builder, False = this soldier is a painter
    static boolean patternMarked = false; // Used by builder to track if it already called markTowerPattern()
    static int ruinsFound = 0; // Used to decide tower type: every 3rd ruin (ruinsFound % 3 == 2) builds money tower
    static MapLocation towerLocation = null; // Stores the MapLocation of the home paint tower
    static Direction exploreDir = null; // Remembers current exploration direction

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
        // Hello world! Standard output is very useful for debugging.
        // Everything you say here will be directly viewable in your terminal when you run a match!
        System.out.println("I'm alive");

        // You can also use indicators to save debug notes in replays.
        rc.setIndicatorString("Hello world!");

        while (true) {
            // This code runs during the entire lifespan of the robot, which is why it is in an infinite
            // loop. If we ever leave this loop and return from run(), the robot dies! At the end of the
            // loop, we call Clock.yield(), signifying that we've done everything we want to do.

            turnCount += 1;  // We have now been alive for one more turn!

            // Try/catch blocks stop unhandled exceptions, which cause your robot to explode.
            try {
                // The same run() function is called for every robot on your team, even if they are
                // different types. Here, we separate the control depending on the UnitType, so we can
                // use different strategies on different robots. If you wish, you are free to rewrite
                // this into a different control structure!
                switch (rc.getType()){
                    case SOLDIER: runSoldier(rc); break; 
                    case MOPPER: runMopper(rc); break;
                    case SPLASHER: break; // Consider upgrading examplefuncsplayer to use splashers!
                    default: runTower(rc); break;
                    }
                }
             catch (GameActionException e) {
                // Oh no! It looks like we did something illegal in the Battlecode world. You should
                // handle GameActionExceptions judiciously, in case unexpected events occur in the game
                // world. Remember, uncaught exceptions cause your robot to explode!
                System.out.println("GameActionException");
                e.printStackTrace();

            } catch (Exception e) {
                // Oh no! It looks like our code tried to do something bad. This isn't a
                // GameActionException, so it's more likely to be a bug in our code.
                System.out.println("Exception");
                e.printStackTrace();

            } finally {
                // Signify we've done everything we want to do, thereby ending our turn.
                // This will make our code wait until the next turn, and then perform this loop again.
                Clock.yield();
            }
            // End of loop: go back to the top. Clock.yield() has ended, so it's time for another turn!
        }

        // Your code should never reach here (unless it's intentional)! Self-destruction imminent...
    }

    /**
     * Run a single turn for towers.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
    public static void runTower(RobotController rc) throws GameActionException {
        // Only paint tower spawns robots
        if (rc.getType() == UnitType.LEVEL_ONE_MONEY_TOWER ||
            rc.getType() == UnitType.LEVEL_TWO_MONEY_TOWER ||
            rc.getType() == UnitType.LEVEL_THREE_MONEY_TOWER) {
            return;
        }
        // Read incoming ruin reports from soldiers/moppers
        Message[] incomingMsgs = rc.readMessages(-1);
        for (Message msg : incomingMsgs) {
            if (msg.getBytes() > 0) {
                storedRuinMsg = msg.getBytes();
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
                        break;
                    }
                }
            }
        }
        // Attack nearest enemy if possible
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            MapLocation enemyLoc = enemies[0].getLocation();
            if (rc.canAttack(enemyLoc)) {
                rc.attack(enemyLoc);
            }
        }

        // Count ally soldiers and moppers
        int soldierCount = 0;
        int mopperCount = 0;
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.SOLDIER) soldierCount++;
            if (ally.getType() == UnitType.MOPPER)  mopperCount++;
        }

        // Calculate paint coverage around tower area
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        int totalTiles = 0;
        int paintedTiles = 0;
        for (MapInfo tile : nearbyTiles) {
            if (!tile.isWall()) {
                totalTiles++;
                if (tile.getPaint().isAlly()) paintedTiles++;
            }
        }
        double coverage = totalTiles > 0 ? (double) paintedTiles / totalTiles * 100 : 0;

        // Stop spawning if paint reserve too low
        if (rc.getPaint() < 200) return;

        // Greedy spawn decision
        boolean spawnMopper = false;
        if (coverage >= 15 && soldierCount >= 4 && mopperCount < 2) {
            spawnMopper = true;
        } else if (coverage >= 5 && soldierCount >= 2 && mopperCount < 1) {
            spawnMopper = true;
        }

        // Try to spawn in each direction
        for (Direction dir : directions) {
            MapLocation spawnLoc = rc.getLocation().add(dir);
            if (spawnMopper) {
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
    static void runBuilder(RobotController rc) throws GameActionException {
        // Searching : walk to tower, wait for ruin order
        if (builderState == 0) {
            rc.setIndicatorString("Builder: waiting for orders");

            // Check message from tower with ruin location
            Message[] messages = rc.readMessages(-1);
            for (Message msg : messages) {
                if (msg.getBytes() > 0) {
                    int x = msg.getBytes() / 100;
                    int y = msg.getBytes() % 100;
                    targetRuinLocation = new MapLocation(x, y);
                    builderState = 1;
                    waitingForOrders = false;
                }
            }

            // Check if ruin already visible
            if (builderState == 0) {
                MapInfo[] nearby = rc.senseNearbyMapInfos();
                for (MapInfo tile : nearby) {
                    if (tile.hasRuin() && rc.senseRobotAtLocation(tile.getMapLocation()) == null) {
                        targetRuinLocation = tile.getMapLocation();
                        builderState = 2;
                        waitingForOrders = false;
                        break;
                    }
                }
            }

            // Walk back to tower while waiting
            if (builderState == 0 && towerLocation != null &&
                rc.getLocation().distanceSquaredTo(towerLocation) > 4) {
                moveToward(rc, towerLocation);
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

        // Building : paint and complete tower
        if (builderState == 2) {
            rc.setIndicatorString("Builder: building tower!");
            boolean stillBuilding = findAndBuildTower(rc);
            if (!stillBuilding) {
                targetRuinLocation = null;
                patternMarked = false;
                builderState = 0;
                waitingForOrders = true;
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
                return;
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
        // Find nearest ruin
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo targetRuin = null;
        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin() && rc.senseRobotAtLocation(tile.getMapLocation()) == null) {
                targetRuin = tile;
                break;
            }
        }
        
        // No ruin found go paint normally
        if (targetRuin == null) return false;
        
        // Save ruin location
        MapLocation ruinLoc = targetRuin.getMapLocation();
        targetRuinLocation = ruinLoc;
        
        // Decide tower type
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        boolean enemyNearby = enemies.length > 0;
        UnitType towerType;
        if (enemyNearby) {
            towerType = UnitType.LEVEL_ONE_DEFENSE_TOWER;
        } else if (ruinsFound % 3 == 2) {
            towerType = UnitType.LEVEL_ONE_MONEY_TOWER;
        } else {
            towerType = UnitType.LEVEL_ONE_PAINT_TOWER;
        }

        // Move to ruin
        if (rc.getLocation().distanceSquaredTo(ruinLoc) > 2) {
            moveToward(rc, ruinLoc);
            return true;
        }

        // Mark pattern if not marked
        if (!patternMarked && rc.canMarkTowerPattern(towerType, ruinLoc)) {
            rc.markTowerPattern(towerType, ruinLoc);
            patternMarked = true;
            return true;
        }

        // Paint unpainted/wrong tiles in 5x5 area
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
            ruinsFound++;
            System.out.println("Built " + towerType + " at " + ruinLoc);
            return false;
        }

        return true;
    }

    /**
     * Run a single turn for a Mopper.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
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

        // No soldier found do persistent random walk to find one
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
        if (rc.getLocation().distanceSquaredTo(towerLocation) > 20) return;

        MapInfo[] nearby = rc.senseNearbyMapInfos();
        for (MapInfo tile : nearby) {
            if (tile.hasRuin() && rc.senseRobotAtLocation(tile.getMapLocation()) == null) {
                int encoded = tile.getMapLocation().x * 100 + tile.getMapLocation().y;
                if (rc.canSendMessage(towerLocation, encoded)) {
                    rc.sendMessage(towerLocation, encoded);
                }
                break;
            }
        }
    }
    // Move toward a target location
    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        Direction dir = rc.getLocation().directionTo(target);
        if (rc.canMove(dir)) {
            rc.move(dir);
        } else {
            // Try rotated directions if blocked
            Direction left  = dir.rotateLeft();
            Direction right = dir.rotateRight();
            if (rc.canMove(left)) {
                rc.move(left);
            } else if (rc.canMove(right)) {
                rc.move(right);
            } else {
                for (Direction d : directions) {
                    if (rc.canMove(d)) {
                        rc.move(d);
                        break;
                    }
                }
            }
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
}
