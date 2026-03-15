package bernbot;

import battlecode.common.*;

public class Mopper {

    static void run(RobotController rc) throws GameActionException {
        RobotPlayer.rememberEnemyTowerIfVisible(rc);
        RobotPlayer.markSectorVisited(rc);
        int pPercent = RobotPlayer.paintPercent(rc);

        // Self-refill if critical
        if (pPercent <= 10 && RobotPlayer.handleRefill(rc))
            return;

        // Tower-defense override: if enemies or enemy paint threaten allied tower
        if (rc.isActionReady() && pPercent >= 15) {
            if (handleTowerDefense(rc))
                return;
        }

        // Combat
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0 && pPercent >= 15) {
            if (executeCombat(rc, enemies))
                return;
        }

        // Defend ruins: clean enemy paint near ruins
        if (rc.isActionReady() && pPercent > 20) {
            MapLocation ruinCleanup = findRuinCleanupTarget(rc);
            if (ruinCleanup != null) {
                if (rc.canAttack(ruinCleanup)) {
                    rc.attack(ruinCleanup);
                    return;
                }
                RobotPlayer.greedyMove(rc, ruinCleanup);
                if (rc.isActionReady() && rc.canAttack(ruinCleanup))
                    rc.attack(ruinCleanup);
                return;
            }
        }

        // Transfer paint to low-paint allies
        if (pPercent >= 30) {
            RobotInfo refillAlly = findAllyNeedingPaint(rc);
            if (refillAlly != null) {
                MapLocation allyLoc = refillAlly.getLocation();
                int allyNeed = refillAlly.getType().paintCapacity - refillAlly.getPaintAmount();
                int give = Math.min(Math.max(0, allyNeed), Math.max(0, rc.getPaint() - 15));
                if (give > 0 && rc.canTransferPaint(allyLoc, give)) {
                    rc.transferPaint(allyLoc, give);
                    return;
                }
                RobotPlayer.greedyMove(rc, allyLoc);
                if (give > 0 && rc.canTransferPaint(allyLoc, give))
                    rc.transferPaint(allyLoc, give);
                return;
            }
        }

        // General cleanup
        if (rc.isActionReady() && pPercent > 20) {
            MapLocation cleanup = findBestCleanupTarget(rc);
            if (cleanup != null) {
                if (rc.canAttack(cleanup)) {
                    rc.attack(cleanup);
                    return;
                }
                RobotPlayer.greedyMove(rc, cleanup);
                if (rc.isActionReady() && rc.canAttack(cleanup))
                    rc.attack(cleanup);
                RobotPlayer.paintUnderfoot(rc);
                return;
            }
        }

        // Refill if medium-low
        if (pPercent <= 30 && RobotPlayer.handleRefill(rc))
            return;

        // Idle drift: move toward interesting locations
        MapLocation driftTarget = chooseIdleDriftTarget(rc);
        if (driftTarget != null) {
            RobotPlayer.greedyMove(rc, driftTarget);
        } else {
            RobotPlayer.executeExplore(rc);
        }
        RobotPlayer.paintUnderfoot(rc);
    }
    // TOWER DEFENSE OVERRIDE

    static boolean handleTowerDefense(RobotController rc) throws GameActionException {
        MapLocation allyTower = RobotPlayer.findNearestAllyTower(rc);
        if (allyTower == null)
            return false;
        int towerDist = rc.getLocation().distanceSquaredTo(allyTower);
        if (towerDist > 36)
            return false; // only defend towers we're close to

        // Check for enemy soldiers near our tower
        RobotInfo[] enemies = rc.senseNearbyRobots(allyTower, 16, rc.getTeam().opponent());
        if (enemies.length == 0)
            return false;

        // Find the most dangerous enemy (prefer soldiers)
        RobotInfo bestTarget = null;
        int bestScore = Integer.MIN_VALUE;
        for (RobotInfo e : enemies) {
            int score = 0;
            if (e.getType() == UnitType.SOLDIER)
                score += 50;
            else if (e.getType() == UnitType.SPLASHER)
                score += 30;
            score += 100 - e.getHealth();
            score -= rc.getLocation().distanceSquaredTo(e.getLocation()) * 2;
            if (score > bestScore) {
                bestScore = score;
                bestTarget = e;
            }
        }

        if (bestTarget == null)
            return false;

        MapLocation targetLoc = bestTarget.getLocation();
        if (rc.canAttack(targetLoc)) {
            rc.attack(targetLoc);
            return true;
        }

        // Try swing if multiple enemies
        Direction bestSwing = chooseBestSwing(rc, enemies);
        if (bestSwing != null && rc.canMopSwing(bestSwing)) {
            rc.mopSwing(bestSwing);
            return true;
        }

        // Move toward threat
        RobotPlayer.greedyMove(rc, targetLoc);
        if (rc.isActionReady() && rc.canAttack(targetLoc)) {
            rc.attack(targetLoc);
        }
        return true;
    }
    // COMBAT (improved target scoring)

    static boolean executeCombat(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        if (!rc.isActionReady())
            return false;

        // Try swing first if >= 2 targets
        Direction bestSwing = chooseBestSwing(rc, enemies);
        if (bestSwing != null && rc.canMopSwing(bestSwing)) {
            int hitCount = countSwingHits(rc, bestSwing, enemies);
            if (hitCount >= 2) {
                rc.mopSwing(bestSwing);
                retreatFromNearest(rc, enemies);
                return true;
            }
        }

        // Score each enemy for normal mop
        RobotInfo bestTarget = null;
        int bestScore = Integer.MIN_VALUE;
        for (RobotInfo e : enemies) {
            int score = 0;
            // Type priority
            if (e.getType() == UnitType.SOLDIER)
                score += 40;
            else if (e.getType() == UnitType.SPLASHER)
                score += 25;
            else
                score += 10;
            // Low HP bonus
            score += Math.max(0, 80 - e.getHealth());
            // Low paint = less productive, prioritize
            score += Math.max(0, 30 - e.getPaintAmount() / 3);
            // Distance penalty
            score -= rc.getLocation().distanceSquaredTo(e.getLocation()) * 2;
            // Can attack now bonus
            if (rc.canAttack(e.getLocation()))
                score += 30;

            if (score > bestScore) {
                bestScore = score;
                bestTarget = e;
            }
        }

        if (bestTarget == null)
            return false;

        MapLocation targetLoc = bestTarget.getLocation();
        if (rc.canAttack(targetLoc)) {
            rc.attack(targetLoc);
            RobotPlayer.greedyMoveAway(rc, targetLoc);
            return true;
        }

        // Swing fallback for out-of-mop-range targets
        if (bestSwing != null && rc.canMopSwing(bestSwing)) {
            if (isInSwingRange(rc.getLocation(), bestSwing, targetLoc)) {
                rc.mopSwing(bestSwing);
                return true;
            }
        }

        // Move closer
        RobotPlayer.greedyMove(rc, targetLoc);
        if (rc.isActionReady() && rc.canAttack(targetLoc))
            rc.attack(targetLoc);
        return true;
    }

    static Direction chooseBestSwing(RobotController rc, RobotInfo[] enemies) {
        Direction best = null;
        int bestCount = 0;
        for (Direction dir : RobotPlayer.CARDINAL) {
            int count = countSwingHits(rc, dir, enemies);
            if (count > bestCount) {
                bestCount = count;
                best = dir;
            }
        }
        return best;
    }

    static int countSwingHits(RobotController rc, Direction dir, RobotInfo[] enemies) {
        int count = 0;
        for (RobotInfo e : enemies) {
            if (isInSwingRange(rc.getLocation(), dir, e.getLocation()))
                count++;
        }
        return count;
    }

    static boolean isInSwingRange(MapLocation myLoc, Direction dir, MapLocation target) {
        int dx = target.x - myLoc.x;
        int dy = target.y - myLoc.y;
        if (dir == Direction.NORTH)
            return dy > 0 && dy <= 2 && Math.abs(dx) <= 1;
        if (dir == Direction.SOUTH)
            return dy < 0 && dy >= -2 && Math.abs(dx) <= 1;
        if (dir == Direction.EAST)
            return dx > 0 && dx <= 2 && Math.abs(dy) <= 1;
        if (dir == Direction.WEST)
            return dx < 0 && dx >= -2 && Math.abs(dy) <= 1;
        return false;
    }

    static void retreatFromNearest(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        if (!rc.isMovementReady() || enemies.length == 0)
            return;
        RobotInfo closest = enemies[0];
        for (RobotInfo e : enemies) {
            if (rc.getLocation().distanceSquaredTo(e.getLocation()) < rc.getLocation()
                    .distanceSquaredTo(closest.getLocation())) {
                closest = e;
            }
        }
        RobotPlayer.greedyMoveAway(rc, closest.getLocation());
    }

    static MapLocation findRuinCleanupTarget(RobotController rc) throws GameActionException {
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;
        for (MapLocation ruin : rc.senseNearbyRuins(-1)) {
            for (MapInfo tile : rc.senseNearbyMapInfos(ruin, 8)) {
                if (!tile.getPaint().isEnemy())
                    continue;
                MapLocation loc = tile.getMapLocation();
                int score = 150 - rc.getLocation().distanceSquaredTo(loc) * 3;
                if (score > bestScore) {
                    bestScore = score;
                    best = loc;
                }
            }
        }
        return best;
    }

    static RobotInfo findAllyNeedingPaint(RobotController rc) throws GameActionException {
        RobotInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (ally.getType().isTowerType() || ally.getType() == UnitType.MOPPER)
                continue;
            int percent = (ally.getPaintAmount() * 100) / Math.max(1, ally.getType().paintCapacity);
            if (percent >= 25)
                continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = ally;
            }
        }
        return best;
    }

    static MapLocation findBestCleanupTarget(RobotController rc) throws GameActionException {
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (Clock.getBytecodeNum() > 12000)
                break;
            if (tile.isWall() || tile.hasRuin())
                continue;
            if (!tile.getPaint().isEnemy())
                continue;
            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            int score = 100 - dist * 3;
            for (MapInfo nearby : rc.senseNearbyMapInfos(loc, 2)) {
                if (nearby.getPaint().isEnemy())
                    score += 6;
            }
            if (score > bestScore) {
                bestScore = score;
                best = loc;
            }
        }
        return best;
    }

    static MapLocation chooseIdleDriftTarget(RobotController rc) throws GameActionException {
        // Drift toward enemy tower front
        if (RobotPlayer.isEnemyTowerFresh(rc)) {
            return RobotPlayer.knownEnemyTower;
        }

        // Drift toward contaminated ruins
        for (MapLocation ruin : rc.senseNearbyRuins(-1)) {
            if (rc.canSenseRobotAtLocation(ruin) && rc.senseRobotAtLocation(ruin) != null)
                continue;
            for (MapInfo t : rc.senseNearbyMapInfos(ruin, 8)) {
                if (t.getPaint().isEnemy())
                    return ruin;
            }
        }

        return null; // fall through to exploration
    }
}
