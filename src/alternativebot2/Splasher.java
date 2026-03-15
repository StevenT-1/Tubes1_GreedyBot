package alternativebot2;

import battlecode.common.*;

public class Splasher {

    static final int SPLASH_THRESHOLD     = 12;
    static final int SPLASH_THRESHOLD_LOW = 8;
    static final int REFILL_CRITICAL      = 20;
    static final int REFILL_NO_TARGET     = 40;
    // If a splash clears >= this fraction of all sensed enemy tiles, allow lower threshold
    static final int CLEANUP_EXCEPTION_THRESHOLD = 4;

    static void run(RobotController rc) throws GameActionException {
        RobotPlayer.rememberEnemyTowerIfVisible(rc);
        RobotPlayer.markSectorVisited(rc);

        // Pre-move splash
        SplashResult preSplash = evaluateBestSplash(rc);
        if (preSplash != null && preSplash.score >= SPLASH_THRESHOLD && rc.canAttack(preSplash.loc)) {
            rc.attack(preSplash.loc);
            tryMoveToSafeTile(rc);
            return;
        }

        // Refill
        int pp = RobotPlayer.paintPercent(rc);
        if (pp <= REFILL_CRITICAL && RobotPlayer.handleRefill(rc)) return;
        if (preSplash == null && pp <= REFILL_NO_TARGET && RobotPlayer.handleRefill(rc)) return;

        // Move toward best target
        MapLocation moveTarget = chooseMoveTarget(rc);
        if (moveTarget != null) {
            RobotPlayer.greedyMove(rc, moveTarget);
        } else {
            RobotPlayer.executeExplore(rc);
        }

        // Post-move splash (lower threshold)
        if (rc.isActionReady()) {
            SplashResult postSplash = evaluateBestSplash(rc);
            if (postSplash != null && postSplash.score >= SPLASH_THRESHOLD_LOW
                && rc.canAttack(postSplash.loc)) {
                rc.attack(postSplash.loc);
                return;
            }
        }

        // Paint underfoot
        RobotPlayer.paintUnderfoot(rc);
    }
    static class SplashResult {
        MapLocation loc;
        int score;
        int enemyCount;
        SplashResult(MapLocation loc, int score, int enemyCount) {
            this.loc = loc; this.score = score; this.enemyCount = enemyCount;
        }
    }

    static SplashResult evaluateBestSplash(RobotController rc) throws GameActionException {
        MapInfo[] allTiles = rc.senseNearbyMapInfos();
        SplashResult best = null;
        MapLocation[] nearRuins = rc.senseNearbyRuins(-1);
        int checked = 0;

        // Count total enemy paint in sensor range for cleanup exception
        int totalEnemyPaint = 0;
        for (MapInfo t : allTiles) {
            if (t.getPaint().isEnemy()) totalEnemyPaint++;
        }

        for (MapInfo centerTile : allTiles) {
            // Bytecode guard
            if (Clock.getBytecodeNum() > 12000 || checked >= 20) break;

            MapLocation center = centerTile.getMapLocation();
            if (!rc.canAttack(center)) continue;
            if (centerTile.isWall() || centerTile.hasRuin()) continue;

            // Safety: skip centers inside known enemy tower range unless tower itself
            if (RobotPlayer.isInKnownEnemyTowerRange(rc, center)) continue;

            checked++;
            int enemyPaint = 0, emptyTiles = 0, allyPaint = 0, obstacles = 0;

            for (MapInfo tile : allTiles) {
                if (tile.getMapLocation().distanceSquaredTo(center) > 4) continue;
                if (tile.isWall() || tile.hasRuin()) { obstacles++; continue; }
                PaintType paint = tile.getPaint();
                if (paint.isEnemy())             enemyPaint++;
                else if (paint == PaintType.EMPTY) emptyTiles++;
                else if (paint.isAlly())          allyPaint++;
            }

            // Skip mostly ally or too obstructed
            if (allyPaint > enemyPaint + emptyTiles) continue;
            if (obstacles >= 5) continue;

            int score = enemyPaint * 8 + emptyTiles * 5 - allyPaint * 5 - obstacles * 4;

            // Ruin proximity bonus
            for (MapLocation ruin : nearRuins) {
                if (center.distanceSquaredTo(ruin) <= 8) { score += 10; break; }
            }

            // Enemy tower proximity bonus
            if (RobotPlayer.isEnemyTowerFresh(rc)) {
                int d = center.distanceSquaredTo(RobotPlayer.knownEnemyTower);
                if (d <= 50) score += 8;
            }

            // Cleanup exception: if this splash would clear most local enemy paint
            int effectiveThreshold = SPLASH_THRESHOLD;
            if (totalEnemyPaint > 0 && enemyPaint >= totalEnemyPaint - CLEANUP_EXCEPTION_THRESHOLD) {
                effectiveThreshold = SPLASH_THRESHOLD_LOW - 2; // allow lower threshold
            }

            if (enemyPaint + emptyTiles < 3) continue; // minimum coverage

            if (best == null || score > best.score) {
                best = new SplashResult(center, score, enemyPaint);
            }
        }

        return best;
    }

    static MapLocation chooseMoveTarget(RobotController rc) throws GameActionException {
        MapLocation bestReclaim = null;
        int bestReclaimScore = Integer.MIN_VALUE;
        MapLocation bestExpand = null;
        int bestExpandScore = Integer.MIN_VALUE;

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (Clock.getBytecodeNum() > 13000) break;
            if (tile.isWall() || tile.hasRuin()) continue;
            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);

            if (tile.getPaint().isEnemy()) {
                int score = 80 - dist * 3;
                for (MapInfo nearby : rc.senseNearbyMapInfos(loc, 2)) {
                    if (nearby.getPaint().isEnemy()) score += 6;
                }
                if (score > bestReclaimScore) { bestReclaimScore = score; bestReclaim = loc; }
            } else if (tile.getPaint() == PaintType.EMPTY) {
                int score = 50 - dist * 2;
                for (MapInfo nearby : rc.senseNearbyMapInfos(loc, 2)) {
                    if (nearby.getPaint().isAlly()) score += 2;
                    if (nearby.getPaint().isEnemy()) score += 4;
                }
                if (score > bestExpandScore) { bestExpandScore = score; bestExpand = loc; }
            }
        }

        if (bestReclaim != null) return bestReclaim;
        if (bestExpand != null) return bestExpand;

        // Safe pressure near known enemy tower
        if (RobotPlayer.isEnemyTowerFresh(rc)) {
            return chooseSafePressureTarget(rc, RobotPlayer.knownEnemyTower);
        }

        // Fallback: stale sector waypoint
        RobotPlayer.chooseExploreSector(rc);
        return RobotPlayer.sectorWaypoint(rc, RobotPlayer.currentSectorX, RobotPlayer.currentSectorY, RobotPlayer.currentWaypointIdx);
    }

    static MapLocation chooseSafePressureTarget(RobotController rc, MapLocation tower) {
        int dangerR2 = UnitType.LEVEL_ONE_DEFENSE_TOWER.actionRadiusSquared;
        int safeMin = dangerR2 + 2;

        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Direction d : RobotPlayer.DIRECTIONS) {
            MapLocation loc = rc.getLocation().add(d);
            int distTower = loc.distanceSquaredTo(tower);
            if (distTower < safeMin || distTower > safeMin + 25) continue;
            int score = -distTower;
            if (score > bestScore) { bestScore = score; best = loc; }
        }
        return best;
    }

    static void tryMoveToSafeTile(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        MapLocation here = rc.getLocation();
        if (rc.canSenseLocation(here) && !rc.senseMapInfo(here).getPaint().isEnemy()) return;

        for (Direction d : RobotPlayer.DIRECTIONS) {
            if (!rc.canMove(d)) continue;
            MapLocation next = here.add(d);
            if (rc.canSenseLocation(next) && rc.senseMapInfo(next).getPaint().isAlly()) {
                rc.move(d); return;
            }
        }
    }
}
