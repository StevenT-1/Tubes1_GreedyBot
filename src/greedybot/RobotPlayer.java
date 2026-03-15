package greedybot;

import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {
    static final Direction[] DIRECTIONS = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    static final int EARLY_MAX_ROUND = 500;
    static final int MID_MAX_ROUND = 1250;

    static final int SOLDIER_REFILL_ENTRY_PERCENT = 30;
    static final int SPLASHER_REFILL_ENTRY_PERCENT = 25;
    static final int MOPPER_REFILL_ENTRY_PERCENT = 20;
    static final int REFILL_EXIT_PERCENT = 75;
    static final int SRP_UNLOCK_TOWERS = 4;

    static final int PHASE_EARLY = 0;
    static final int PHASE_MID = 1;
    static final int PHASE_LATE = 2;

    static boolean initialized = false;
    static Team myTeam;
    static Team enemyTeam;
    static Random rng;

    static MapLocation spawnOrigin;
    static MapLocation exploreTarget;
    static int exploreTargetRound = -1;

    static MapLocation lastEnemyTower;
    static int lastEnemyTowerRound = -9999;
    static int towerLastEnemyRound = -9999;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            try {
                initialize(rc);

                switch (rc.getType()) {
                    case SOLDIER:
                        Soldier.run(rc);
                        break;
                    case SPLASHER:
                        Splasher.run(rc);
                        break;
                    case MOPPER:
                        Mopper.run(rc);
                        break;
                    default:
                        Tower.run(rc);
                        break;
                }

            } catch (GameActionException e) {
                // Ignore per-turn action exceptions and continue next tick.
            } catch (Exception e) {
                // Ignore per-turn runtime exceptions and continue next tick.
            } finally {
                Clock.yield();
            }
        }
    }

    static void initialize(RobotController rc) throws GameActionException {
        if (initialized) {
            return;
        }
        initialized = true;
        myTeam = rc.getTeam();
        enemyTeam = myTeam.opponent();
        rng = new Random(991L + 13L * rc.getID());

        MapLocation[] ruins = rc.senseNearbyRuins(4);
        spawnOrigin = ruins.length > 0 ? ruins[0] : rc.getLocation();
        exploreTarget = initialExploreTarget(rc);
        exploreTargetRound = rc.getRoundNum();
    }

    static int determinePhase(RobotController rc) {
        int round = rc.getRoundNum();
        if (round <= EARLY_MAX_ROUND) {
            return PHASE_EARLY;
        }
        if (round <= MID_MAX_ROUND) {
            return PHASE_MID;
        }
        return PHASE_LATE;
    }

    static boolean isLowPaint(RobotController rc, int entryPercent) {
        return rc.getPaint() * 100 < rc.getType().paintCapacity * entryPercent;
    }

    static boolean isRefillRecovered(RobotController rc) {
        return rc.getPaint() * 100 >= rc.getType().paintCapacity * REFILL_EXIT_PERCENT;
    }

    static MapLocation getNearestAllyTower(RobotController rc) throws GameActionException {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapLocation ruin : rc.senseNearbyRuins(-1)) {
            if (!rc.canSenseLocation(ruin)) {
                continue;
            }
            RobotInfo robot = rc.senseRobotAtLocation(ruin);
            if (robot == null || robot.getTeam() != myTeam || !robot.getType().isTowerType()) {
                continue;
            }
            int d = rc.getLocation().distanceSquaredTo(ruin);
            if (d < bestDist) {
                bestDist = d;
                best = ruin;
            }
        }
        return best;
    }

    static void rememberEnemyTower(MapLocation loc, int round) {
        lastEnemyTower = loc;
        lastEnemyTowerRound = round;
    }

    static MapLocation getKnownEnemyTower(int round) {
        if (lastEnemyTower == null) {
            return null;
        }
        if (round - lastEnemyTowerRound > 50) {
            return null;
        }
        return lastEnemyTower;
    }

    static void refreshExploreTargetIfNeeded(RobotController rc, boolean force) {
        if (exploreTarget == null || force
            || rc.getLocation().distanceSquaredTo(exploreTarget) <= 16
            || rc.getRoundNum() - exploreTargetRound >= 90) {
            int w = rc.getMapWidth();
            int h = rc.getMapHeight();
            exploreTarget = new MapLocation(rng.nextInt(w), rng.nextInt(h));
            exploreTargetRound = rc.getRoundNum();
        }
    }

    static MapLocation initialExploreTarget(RobotController rc) {
        MapLocation start = rc.getLocation();
        Direction dir = spawnOrigin.directionTo(start);
        if (dir == Direction.CENTER) {
            dir = DIRECTIONS[rc.getID() & 7];
        }
        MapLocation out = start;
        for (int i = 0; i < Math.max(rc.getMapWidth(), rc.getMapHeight()); i++) {
            MapLocation next = out.add(dir);
            if (!rc.onTheMap(next)) {
                break;
            }
            out = next;
        }
        return out;
    }

    static MapLocation estimatedEnemyBase(RobotController rc) {
        if (spawnOrigin == null) {
            return new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        }
        return new MapLocation(rc.getMapWidth() - 1 - spawnOrigin.x, rc.getMapHeight() - 1 - spawnOrigin.y);
    }

    static void tryPaintUnderfoot(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) {
            return;
        }
        MapLocation here = rc.getLocation();
        if (!rc.canAttack(here)) {
            return;
        }
        MapInfo info = rc.senseMapInfo(here);
        if (!info.getPaint().isAlly()) {
            rc.attack(here, useSecondaryGeneral(here));
        }
    }

    static boolean useSecondaryGeneral(MapLocation loc) {
        return ((loc.x + loc.y) & 1) == 0;
    }

    static boolean useSecondaryForPaintTower(MapLocation tile, MapLocation center) {
        int dx = Math.abs(tile.x - center.x);
        int dy = Math.abs(tile.y - center.y);
        return dx == dy;
    }

    static boolean moveAwayFrom(RobotController rc, MapLocation threat) throws GameActionException {
        if (!rc.isMovementReady() || threat == null) {
            return false;
        }
        Direction best = null;
        int bestDist = rc.getLocation().distanceSquaredTo(threat);
        for (Direction d : DIRECTIONS) {
            if (!rc.canMove(d)) {
                continue;
            }
            MapLocation next = rc.getLocation().add(d);
            int dist = next.distanceSquaredTo(threat);
            if (dist > bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        if (best != null) {
            rc.move(best);
            return true;
        }
        return false;
    }

    static RobotInfo nearestEnemyTower(RobotController rc) throws GameActionException {
        RobotInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : rc.senseNearbyRobots(-1, enemyTeam)) {
            if (!enemy.getType().isTowerType()) {
                continue;
            }
            int d = rc.getLocation().distanceSquaredTo(enemy.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = enemy;
            }
        }
        return best;
    }
}
