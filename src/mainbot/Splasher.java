package mainbot;

import battlecode.common.*;

public class Splasher {
    static boolean refilling = false;

    static void run(RobotController rc) throws GameActionException {
        if (tryRefill(rc)) {
            return;
        }
        if (tryAvoidDefenseTower(rc)) {
            return;
        }
        // Local splash first: if there's good coverage value here, take it instead of blindly chasing a tower
        if (trySplashLocal(rc)) {
            return;
        }
        if (tryReclaimLane(rc)) {
            return;
        }
        if (tryPressureTower(rc)) {
            return;
        }
        explore(rc);
    }

    static boolean tryRefill(RobotController rc) throws GameActionException {
        if (!refilling && RobotPlayer.isLowPaint(rc, RobotPlayer.SPLASHER_REFILL_ENTRY_PERCENT)) {
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
        int take = -Math.min(need, 120);
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

    static boolean tryAvoidDefenseTower(RobotController rc) throws GameActionException {
        RobotInfo nearest = RobotPlayer.nearestEnemyTower(rc);
        if (nearest == null) {
            return false;
        }
        if (nearest.getType().getBaseType() != UnitType.LEVEL_ONE_DEFENSE_TOWER) {
            return false;
        }
        int dist = rc.getLocation().distanceSquaredTo(nearest.getLocation());
        if (dist <= UnitType.LEVEL_ONE_DEFENSE_TOWER.actionRadiusSquared) {
            RobotPlayer.moveAwayFrom(rc, nearest.getLocation());
            return true;
        }
        return false;
    }

    static boolean tryPressureTower(RobotController rc) throws GameActionException {
        RobotInfo nearest = RobotPlayer.nearestEnemyTower(rc);
        if (nearest == null) {
            if (rc.getRoundNum() >= 1500) {
                Nav.moveTo(rc, RobotPlayer.estimatedEnemyBase(rc));
                return true;
            }
            return false;
        }
        if (nearest.getType().getBaseType() == UnitType.LEVEL_ONE_DEFENSE_TOWER) {
            return false;
        }

        MapLocation target = nearest.getLocation();
        int dist = rc.getLocation().distanceSquaredTo(target);

        // Don't chase towers that are too far; let explore handle long-range navigation
        if (dist > 80) {
            return false;
        }

        if (dist <= UnitType.SPLASHER.actionRadiusSquared) {
            // In range: only fire if the splash score is worthwhile
            int score = scoreSplashCell(rc, target);
            if (score >= 2 && rc.canAttack(target)) {
                rc.attack(target);
            }
            RobotPlayer.moveAwayFrom(rc, target);
            return true;
        }

        Nav.moveTo(rc, target);
        if (rc.canAttack(target) && scoreSplashCell(rc, target) >= 2) {
            rc.attack(target);
        }
        return true;
    }

    static boolean tryReclaimLane(RobotController rc) throws GameActionException {
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;
        int checked = 0;

        for (MapInfo tile : rc.senseNearbyMapInfos(20)) {
            if (checked >= 24) {
                break;
            }
            checked++;
            if (!tile.getPaint().isEnemy() || tile.isWall() || tile.hasRuin()) {
                continue;
            }

            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            int enemyNeighbors = 0;
            int allyNeighbors = 0;
            for (MapInfo nearby : rc.senseNearbyMapInfos(loc, 2)) {
                if (nearby.getPaint().isEnemy()) {
                    enemyNeighbors++;
                } else if (nearby.getPaint().isAlly()) {
                    allyNeighbors++;
                }
            }

            int score = 90 - dist * 4 + enemyNeighbors * 8 - allyNeighbors * 2;
            if (score > bestScore) {
                bestScore = score;
                best = loc;
            }
        }

        if (best == null || bestScore < 65) {
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

    static boolean trySplashLocal(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) {
            return false;
        }

        MapLocation best = null;
        int bestScore = 0;

        for (MapInfo candidate : rc.senseNearbyMapInfos(18)) {
            MapLocation c = candidate.getMapLocation();
            if (!rc.canAttack(c)) {
                continue;
            }
            int score = scoreSplashCell(rc, c);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        // Require at least 3 score to avoid wasting paint on near-empty areas
        if (best != null && bestScore >= 3) {
            rc.attack(best);
            return true;
        }
        return false;
    }

    static int scoreSplashCell(RobotController rc, MapLocation center) throws GameActionException {
        int score = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation p = center.translate(dx, dy);
                if (!rc.canSenseLocation(p)) {
                    continue;
                }
                MapInfo info = rc.senseMapInfo(p);
                if (info.isWall()) {
                    score -= 1;
                    continue;
                }
                PaintType paint = info.getPaint();
                if (paint.isEnemy()) {
                    score += 3;
                } else if (paint == PaintType.EMPTY) {
                    score += 1;
                } else {
                    score -= 1;
                }
            }
        }
        return score;
    }

    static void explore(RobotController rc) throws GameActionException {
        RobotPlayer.refreshExploreTargetIfNeeded(rc, false);
        Nav.moveTo(rc, RobotPlayer.exploreTarget);
    }
}
