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

    static MapLocation towerLocation = null;
    static MapLocation mopperPost = null;
    static boolean returningToPost = false;
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
        // Coverage percentage in visible area
        double coverage = totalTiles > 0 ? (double) paintedTiles / totalTiles * 100 : 0;

        // GREEDY SPAWN DECISION 
        // Spawn mopper only when coverage and soldier count thresholds are met
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
                if (rc.canBuildRobot(UnitType.MOPPER, spawnLoc)) {
                    rc.buildRobot(UnitType.MOPPER, spawnLoc);
                    System.out.println("Built a MOPPER");
                    break;
                }
            } else {
                if (rc.canBuildRobot(UnitType.SOLDIER, spawnLoc)) {
                    rc.buildRobot(UnitType.SOLDIER, spawnLoc);
                    System.out.println("Built a SOLDIER");
                    break;
                }
            }
        }
    }


    /**
     * Run a single turn for a Soldier.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
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

        int maxPaint = rc.getType().paintCapacity;   // 200 for soldier
        int currentPaint = rc.getPaint();
        int paintPercent = currentPaint * 100 / maxPaint;

        // ---- GREEDY DECISION ----
        if (paintPercent < 40) {
            // Paint is low → go refill
            rc.setIndicatorString("Low paint! Retreating to refill");
            retreatToRefill(rc);
        } else {
            // Paint is fine → go paint nearest unpainted tile
            rc.setIndicatorString("Painting! Paint: " + currentPaint);
            paintNearest(rc, rc.getID());
        }
    }

    // Soldier retreats to mopper post or tower for refill
    static void retreatToRefill(RobotController rc) throws GameActionException {
        int maxPaint = rc.getType().paintCapacity;
        int currentPaint = rc.getPaint();

        if (towerLocation != null && 
            rc.getLocation().distanceSquaredTo(towerLocation) <= 2) {
            int needed = rc.getType().paintCapacity - rc.getPaint();
            if (rc.canTransferPaint(towerLocation, -needed)) {
                rc.transferPaint(towerLocation, -needed);
            }
            return;
        }
        // Check if mopper is nearby → wait for transfer
        RobotInfo[] allies = rc.senseNearbyRobots(2, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.MOPPER) {
                // Mopper is nearby, stay and wait for transfer
                // Paint under current tile while waiting
                MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
                if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
                    rc.attack(rc.getLocation());
                }
                return;
            }
        }

        // No mopper nearby → go to tower
        if (towerLocation != null) {
            // If at tower, withdraw paint until FULL (>= 90%)
            if (rc.getLocation().distanceSquaredTo(towerLocation) <= 2) {
                if (currentPaint < maxPaint * 9 / 10) {
                    // Withdraw from tower - negative value means withdraw
                    int needed = maxPaint - currentPaint;
                    if (rc.canTransferPaint(towerLocation, -needed)) {
                        rc.transferPaint(towerLocation, -needed);
                        rc.setIndicatorString("Refilling at tower...");
                    }
                }
                // Only leave when paint >= 90% FULL
                return;
            }
            // Move toward tower
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
        if (!tile.isWall() && !tile.hasRuin() && !tile.getPaint().isAlly()) {
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
            // All visible tiles painted → explore randomly
            moveRandom(rc);
            // Always paint under feet
            MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
            if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
                rc.attack(rc.getLocation());
            }
        }
    }


    /**
     * Run a single turn for a Mopper.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
    public static void runMopper(RobotController rc) throws GameActionException {
        // Save tower location on first turn and calculate fixed post
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

        // Calculate fixed post = 1/4 of map from tower toward center
        if (mopperPost == null && towerLocation != null) {
            int mapWidth  = rc.getMapWidth();
            int mapHeight = rc.getMapHeight();
            int centerX   = mapWidth  / 2;
            int centerY   = mapHeight / 2;

            // Move 1/4 of the way from tower to center
            int postX = towerLocation.x + (centerX - towerLocation.x) / 2;
            int postY = towerLocation.y + (centerY - towerLocation.y) / 2;
            postX = Math.max(0, Math.min(mapWidth - 1, postX));
            postY = Math.max(0, Math.min(mapHeight - 1, postY));
            mopperPost = new MapLocation(postX, postY);
            System.out.println("Mopper post set at: " + mopperPost);
        }

        int maxPaint    = rc.getType().paintCapacity; // 100 for mopper
        int currentPaint = rc.getPaint();
        int paintPercent = currentPaint * 100 / maxPaint;

        // ---- GREEDY DECISION ----
        if (paintPercent < 30) {
            // Own paint low → go back to tower to refill
            rc.setIndicatorString("Mopper low paint! Going to tower");
            mopperRefill(rc);
        } else if (returningToPost) {
            // Just refilled → return to post first
            rc.setIndicatorString("Returning to post");
            if (mopperPost != null) {
                if (rc.getLocation().equals(mopperPost) ||
                    rc.getLocation().distanceSquaredTo(mopperPost) <= 2) {
                    returningToPost = false;
                } else {
                    moveToward(rc, mopperPost);
                }
            }
        } else {
            // At post or going to post → check for low paint soldiers
            rc.setIndicatorString("Mopper on duty at post");
            mopperDuty(rc);
        }
    }

    // Mopper refills at tower then returns to post
    static void mopperRefill(RobotController rc) throws GameActionException {
        if (towerLocation == null) {
            // Tower not found yet → move randomly to search for it
            moveRandom(rc);
            // Try to find tower while exploring
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

        int maxPaint    = rc.getType().paintCapacity;
        int currentPaint = rc.getPaint();

        if (rc.getLocation().distanceSquaredTo(towerLocation) <= 2) {
            // At tower → withdraw until FULL (>= 90%)
            if (currentPaint < maxPaint * 9 / 10) {
                int needed = maxPaint - currentPaint;
                if (rc.canTransferPaint(towerLocation, -needed)) {
                    rc.transferPaint(towerLocation, -needed);
                    rc.setIndicatorString("Mopper refilling...");
                }
            } else {
                // Fully refilled → return to post
                returningToPost = true;
            }
        } else {
            // Walk to tower
            moveToward(rc, towerLocation);
        }
    }

    // Mopper stays at post and transfers paint to low soldiers
    static void mopperDuty(RobotController rc) throws GameActionException {
        // First check if any adjacent soldier needs paint
        RobotInfo[] allies = rc.senseNearbyRobots(2, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.SOLDIER) {
                int soldierMaxPaint = UnitType.SOLDIER.paintCapacity; // 200
                int soldierPaint    = ally.getPaintAmount();
                int soldierPercent  = soldierPaint * 100 / soldierMaxPaint;

                if (soldierPercent < 90) {
                    // Transfer paint to soldier
                    int transferAmount = Math.min(
                        rc.getPaint() - 40,          // keep 20 for mopper itself
                        soldierMaxPaint - soldierPaint // fill soldier up
                    );
                    if (transferAmount > 0 &&
                        rc.canTransferPaint(ally.getLocation(), transferAmount)) {
                        rc.transferPaint(ally.getLocation(), transferAmount);
                        rc.setIndicatorString("Transferred paint to soldier!");
                        return;
                    }
                }
            }
        }

        // No soldiers need help → make sure we're at our post
        if (mopperPost != null) {
            if (rc.getLocation().distanceSquaredTo(mopperPost) > 2) {
                moveToward(rc, mopperPost);
            }
            // else → already at post, just wait (do nothing)
        }
    }

    // HELPER
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
