package greedybot;

import battlecode.common.*;

public class Mopper {
    static final Direction[] CARDINAL_DIRECTIONS = {
        Direction.NORTH,
        Direction.EAST,
        Direction.SOUTH,
        Direction.WEST,
    };

    static boolean refilling = false;
    static MapLocation lastMopTarget = null;
    static int mopStallTurns = 0;

    static void run(RobotController rc) throws GameActionException {
        if (tryRefill(rc)) {
            return;
        }
        if (tryRefillNearbyAlly(rc)) {
            return;
        }
        if (tryMopSweep(rc)) {
            return;
        }
        if (tryMopEnemyPaint(rc)) {
            return;
        }
        if (tryAttackMobileEnemy(rc)) {
            return;
        }
        exploreOrFollowSoldier(rc);
    }

    static boolean tryMopSweep(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) {
            return false;
        }

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, RobotPlayer.enemyTeam);
        if (enemies.length < 2) {
            return false;
        }

        Direction bestSweep = chooseSweepDirection(rc, enemies);
        if (bestSweep == null || !rc.canMopSwing(bestSweep)) {
            return false;
        }

        rc.mopSwing(bestSweep);
        if (rc.isMovementReady()) {
            RobotInfo nearest = null;
            int bestDist = Integer.MAX_VALUE;
            for (RobotInfo enemy : enemies) {
                int dist = rc.getLocation().distanceSquaredTo(enemy.getLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    nearest = enemy;
                }
            }
            if (nearest != null) {
                RobotPlayer.moveAwayFrom(rc, nearest.getLocation());
            }
        }
        return true;
    }

    static Direction chooseSweepDirection(RobotController rc, RobotInfo[] enemies) {
        Direction best = null;
        int bestCount = 0;

        for (Direction dir : CARDINAL_DIRECTIONS) {
            int count = 0;
            for (RobotInfo enemy : enemies) {
                int dx = enemy.getLocation().x - rc.getLocation().x;
                int dy = enemy.getLocation().y - rc.getLocation().y;
                boolean inLane = false;
                if (dir == Direction.NORTH) {
                    inLane = dy > 0 && Math.abs(dx) <= 1;
                } else if (dir == Direction.EAST) {
                    inLane = dx > 0 && Math.abs(dy) <= 1;
                } else if (dir == Direction.SOUTH) {
                    inLane = dy < 0 && Math.abs(dx) <= 1;
                } else if (dir == Direction.WEST) {
                    inLane = dx < 0 && Math.abs(dy) <= 1;
                }
                if (inLane) {
                    count++;
                }
            }
            if (count >= 2 && count > bestCount) {
                bestCount = count;
                best = dir;
            }
        }

        return best;
    }

    static boolean tryRefill(RobotController rc) throws GameActionException {
        if (!refilling && RobotPlayer.isLowPaint(rc, RobotPlayer.MOPPER_REFILL_ENTRY_PERCENT)) {
            refilling = true;
        }
        if (refilling && RobotPlayer.isRefillRecovered(rc)) {
            refilling = false;
            return false;
        }
        if (!refilling) {
            return false;
        }

        MapLocation tower = RobotPlayer.getNearestAllyTower(rc);
        if (tower == null) {
            refilling = false;
            return false;
        }

        int need = rc.getType().paintCapacity - rc.getPaint();
        int take = -Math.min(need, 60);
        if (rc.canTransferPaint(tower, take)) {
            rc.transferPaint(tower, take);
            return true;
        }

        Nav.moveTo(rc, tower);
        if (rc.canTransferPaint(tower, take)) {
            rc.transferPaint(tower, take);
        }
        return true;
    }

    static boolean tryRefillNearbyAlly(RobotController rc) throws GameActionException {
        if (!rc.isActionReady() || rc.getPaint() <= 20) {
            return false;
        }

        // Search wider range (8) so mopper actively moves toward low-paint allies
        RobotInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : rc.senseNearbyRobots(8, RobotPlayer.myTeam)) {
            if (ally.getType().isTowerType()) {
                continue;
            }
            int allyCap = ally.getType().paintCapacity;
            if (ally.getPaintAmount() * 100 > allyCap * 20) {
                continue;
            }
            int d = rc.getLocation().distanceSquaredTo(ally.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = ally;
            }
        }
        if (best == null) {
            return false;
        }
        int allyCap = best.getType().paintCapacity;
        int give = Math.min(rc.getPaint() - 15, allyCap - best.getPaintAmount());
        if (give <= 0) {
            return false;
        }
        if (rc.canTransferPaint(best.getLocation(), give)) {
            rc.transferPaint(best.getLocation(), give);
            return true;
        }
        // Move within transfer range
        Nav.moveTo(rc, best.getLocation());
        if (rc.canTransferPaint(best.getLocation(), give)) {
            rc.transferPaint(best.getLocation(), give);
        }
        return true;
    }

    static boolean tryMopEnemyPaint(RobotController rc) throws GameActionException {
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        for (MapInfo info : rc.senseNearbyMapInfos(-1)) {
            if (!info.getPaint().isEnemy()) {
                continue;
            }
            MapLocation loc = info.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            // Score by cluster: prefer tiles with more enemy paint neighbors
            int clusterBonus = 0;
            for (MapInfo neighbor : rc.senseNearbyMapInfos(loc, 2)) {
                if (neighbor.getPaint().isEnemy()) {
                    clusterBonus += 3;
                }
            }
            int score = clusterBonus - dist;
            if (score > bestScore) {
                bestScore = score;
                best = loc;
            }
        }

        if (best == null) {
            return false;
        }

        // Stall detection: if we keep targeting the same tile and can't reach it, give up
        if (best.equals(lastMopTarget)) {
            mopStallTurns++;
        } else {
            lastMopTarget = best;
            mopStallTurns = 0;
        }
        if (mopStallTurns > 5) {
            mopStallTurns = 0;
            lastMopTarget = null;
            return false;
        }

        if (rc.canAttack(best)) {
            rc.attack(best);
            return true;
        }

        Nav.moveTo(rc, best);
        if (rc.canAttack(best)) {
            rc.attack(best);
        }
        return true;
    }

    static boolean tryAttackMobileEnemy(RobotController rc) throws GameActionException {
        RobotInfo best = null;
        int bestHp = Integer.MAX_VALUE;

        for (RobotInfo enemy : rc.senseNearbyRobots(-1, RobotPlayer.enemyTeam)) {
            if (enemy.getType().isTowerType()) {
                continue;
            }
            if (!rc.canAttack(enemy.getLocation())) {
                continue;
            }
            if (enemy.getHealth() < bestHp) {
                bestHp = enemy.getHealth();
                best = enemy;
            }
        }

        if (best != null) {
            rc.attack(best.getLocation());
            return true;
        }
        return false;
    }

    static void exploreOrFollowSoldier(RobotController rc) throws GameActionException {
        RobotInfo nearestSoldier = null;
        int bestDist = Integer.MAX_VALUE;

        for (RobotInfo ally : rc.senseNearbyRobots(-1, RobotPlayer.myTeam)) {
            if (ally.getType() != UnitType.SOLDIER) {
                continue;
            }
            int d = rc.getLocation().distanceSquaredTo(ally.getLocation());
            if (d < bestDist) {
                bestDist = d;
                nearestSoldier = ally;
            }
        }

        // Only follow soldier if meaningfully far away; avoid adjacent oscillation
        if (nearestSoldier != null && bestDist > 9) {
            Nav.moveTo(rc, nearestSoldier.getLocation());
            return;
        }

        RobotPlayer.refreshExploreTargetIfNeeded(rc, false);
        Nav.moveTo(rc, RobotPlayer.exploreTarget);
    }
}
