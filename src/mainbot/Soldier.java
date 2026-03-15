package mainbot;

import battlecode.common.*;

public class Soldier {
    enum SoldierRole {
        BUILDER,
        ATTACKER
    }

    static boolean refilling = false;
    static MapLocation lastRuinTarget = null;
    static int ruinStallTurns = 0;
    static int ruinNavTurns = 0;
    static int ruinBestDist = Integer.MAX_VALUE;
    static final int RUIN_STALL_LIMIT = 5;
    static final int RUIN_NAV_LIMIT = 120;
    static final int BLOCKED_RUIN_COOLDOWN = 60;
    static MapLocation blockedRuin = null;
    static int blockedRuinUntilRound = -1;
    static MapLocation buildRuinTarget = null;
    static UnitType buildTowerType = UnitType.LEVEL_ONE_PAINT_TOWER;
    static boolean buildPatternMarked = false;
    static int noPaintCounter = 0;
    static final int NO_PAINT_TOWER_THRESHOLD = 6;

    static final int SRP_STICKY_WINDOW = 20;
    static final int SRP_STALL_LIMIT = 8;
    static final int SRP_NO_PAINT_LIMIT = 4;
    static MapLocation stickySrpTarget = null;
    static int stickySrpUntilRound = -1;
    static int srpStallTurns = 0;
    static int srpNoPaintTurns = 0;

    static final UnitType[] TOWER_TYPES = {
        UnitType.LEVEL_ONE_PAINT_TOWER,
        UnitType.LEVEL_ONE_MONEY_TOWER,
        UnitType.LEVEL_ONE_DEFENSE_TOWER,
    };

    static SoldierRole getAssignedRole(RobotController rc) {
        return (rc.getID() % 3 == 0) ? SoldierRole.ATTACKER : SoldierRole.BUILDER;
    }

    static void runBuilderWaterfall(RobotController rc) throws GameActionException {
        if (tryBuildRuin(rc)) {
            return;
        }
        if (trySrpCompletionOrWork(rc)) {
            return;
        }
        tryPressureTower(rc);
    }

    static void runAttackerWaterfall(RobotController rc) throws GameActionException {
        if (tryPressureTower(rc)) {
            return;
        }
        if (trySrpCompletionOrWork(rc)) {
            return;
        }
        if (rc.getRoundNum() < 250 || rc.getNumberTowers() < 6) {
            tryBuildRuin(rc);
        }
    }

    static void run(RobotController rc) throws GameActionException {
        rememberEnemyTower(rc);

        tryCompletionPass(rc);

        if (tryRefill(rc)) {
            return;
        }

        SoldierRole role = getAssignedRole(rc);
        if (role == SoldierRole.BUILDER) {
            runBuilderWaterfall(rc);
        } else {
            runAttackerWaterfall(rc);
        }

        explore(rc);
        RobotPlayer.tryPaintUnderfoot(rc);
    }

    static void rememberEnemyTower(RobotController rc) throws GameActionException {
        RobotInfo t = RobotPlayer.nearestEnemyTower(rc);
        if (t != null) {
            RobotPlayer.rememberEnemyTower(t.getLocation(), rc.getRoundNum());
        }
    }

    static boolean tryRefill(RobotController rc) throws GameActionException {
        if (!refilling && RobotPlayer.isLowPaint(rc, RobotPlayer.SOLDIER_REFILL_ENTRY_PERCENT)) {
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
        int take = -Math.min(need, 80);
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

    static boolean tryPressureTower(RobotController rc) throws GameActionException {
        RobotInfo target = RobotPlayer.nearestEnemyTower(rc);
        if (target == null) {
            MapLocation known = RobotPlayer.getKnownEnemyTower(rc.getRoundNum());
            if (known != null) {
                Nav.moveTo(rc, known);
                return true;
            }
            if (rc.getRoundNum() >= 1500) {
                Nav.moveTo(rc, RobotPlayer.estimatedEnemyBase(rc));
                return true;
            }
            return false;
        }

        MapLocation loc = target.getLocation();
        int d = rc.getLocation().distanceSquaredTo(loc);
        boolean defense = target.getType().getBaseType() == UnitType.LEVEL_ONE_DEFENSE_TOWER;

        if (defense && d <= UnitType.LEVEL_ONE_DEFENSE_TOWER.actionRadiusSquared) {
            RobotPlayer.moveAwayFrom(rc, loc);
            return true;
        }

        if (d <= UnitType.SOLDIER.actionRadiusSquared) {
            if (rc.canAttack(loc)) {
                rc.attack(loc);
            }
            RobotPlayer.moveAwayFrom(rc, loc);
            return true;
        }

        Nav.moveTo(rc, loc);
        if (rc.canAttack(loc)) {
            rc.attack(loc);
        }
        return true;
    }

    static void tryCompletionPass(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) {
            return;
        }
        for (MapLocation ruin : rc.senseNearbyRuins(-1)) {
            if (!rc.canSenseLocation(ruin)) {
                continue;
            }
            RobotInfo occupant = rc.senseRobotAtLocation(ruin);
            if (occupant != null) {
                continue;
            }
            for (UnitType type : TOWER_TYPES) {
                if (rc.canCompleteTowerPattern(type, ruin)) {
                    rc.completeTowerPattern(type, ruin);
                    if (blockedRuin != null && blockedRuin.equals(ruin)) {
                        blockedRuin = null;
                        blockedRuinUntilRound = -1;
                    }
                    ruinStallTurns = 0;
                    lastRuinTarget = null;
                    return;
                }
            }
        }
    }

    static UnitType chooseBuildTowerType(RobotController rc, MapLocation ruin) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(ruin, 8, rc.getTeam().opponent());
        if (enemies.length > 0) {
            return UnitType.LEVEL_ONE_DEFENSE_TOWER;
        }
        if (rc.getID() % 5 == 0) {
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        }
        return UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    static boolean tryBuildRuin(RobotController rc) throws GameActionException {
        if (rc.getNumberTowers() >= 25) {
            return false;
        }

        if (buildRuinTarget != null) {
            if (blockedRuin != null && buildRuinTarget.equals(blockedRuin) && rc.getRoundNum() <= blockedRuinUntilRound) {
                buildRuinTarget = null;
                buildPatternMarked = false;
                noPaintCounter = 0;
            } else if (rc.canSenseLocation(buildRuinTarget)) {
                RobotInfo occupant = rc.senseRobotAtLocation(buildRuinTarget);
                if (occupant != null) {
                    buildRuinTarget = null;
                    buildPatternMarked = false;
                    noPaintCounter = 0;
                }
            }
        }

        if (buildRuinTarget == null) {
            int bestDist = Integer.MAX_VALUE;
            for (MapLocation ruin : rc.senseNearbyRuins(-1)) {
                if (!rc.canSenseLocation(ruin)) {
                    continue;
                }
                if (blockedRuin != null && ruin.equals(blockedRuin) && rc.getRoundNum() <= blockedRuinUntilRound) {
                    continue;
                }
                RobotInfo occupant = rc.senseRobotAtLocation(ruin);
                if (occupant != null) {
                    continue;
                }
                int d = rc.getLocation().distanceSquaredTo(ruin);
                if (d < bestDist) {
                    bestDist = d;
                    buildRuinTarget = ruin;
                }
            }
            if (buildRuinTarget == null) {
                return false;
            }
            buildTowerType = chooseBuildTowerType(rc, buildRuinTarget);
            buildPatternMarked = false;
            noPaintCounter = 0;
            ruinBestDist = rc.getLocation().distanceSquaredTo(buildRuinTarget);
            ruinNavTurns = 0;
            ruinStallTurns = 0;
        }

        MapLocation targetRuin = buildRuinTarget;

        if (rc.canCompleteTowerPattern(buildTowerType, targetRuin)) {
            rc.completeTowerPattern(buildTowerType, targetRuin);
            if (blockedRuin != null && blockedRuin.equals(targetRuin)) {
                blockedRuin = null;
                blockedRuinUntilRound = -1;
            }
            buildRuinTarget = null;
            buildPatternMarked = false;
            noPaintCounter = 0;
            return true;
        }
        for (UnitType type : TOWER_TYPES) {
            if (type == buildTowerType) {
                continue;
            }
            if (rc.canCompleteTowerPattern(type, targetRuin)) {
                rc.completeTowerPattern(type, targetRuin);
                if (blockedRuin != null && blockedRuin.equals(targetRuin)) {
                    blockedRuin = null;
                    blockedRuinUntilRound = -1;
                }
                buildRuinTarget = null;
                buildPatternMarked = false;
                noPaintCounter = 0;
                return true;
            }
        }

        int dist = rc.getLocation().distanceSquaredTo(targetRuin);
        if (dist > 2) {
            if (dist < ruinBestDist) {
                ruinBestDist = dist;
                ruinNavTurns = 0;
            } else {
                ruinNavTurns++;
            }
            if (ruinNavTurns > RUIN_NAV_LIMIT) {
                blockedRuin = targetRuin;
                blockedRuinUntilRound = rc.getRoundNum() + BLOCKED_RUIN_COOLDOWN;
                buildRuinTarget = null;
                buildPatternMarked = false;
                noPaintCounter = 0;
                ruinNavTurns = 0;
                ruinBestDist = Integer.MAX_VALUE;
                return false;
            }
            Nav.moveTo(rc, targetRuin);
            return true;
        }

        ruinNavTurns = 0;
        ruinBestDist = dist;

        if (!buildPatternMarked && rc.canMarkTowerPattern(buildTowerType, targetRuin)) {
            rc.markTowerPattern(buildTowerType, targetRuin);
            buildPatternMarked = true;
            return true;
        }

        MapLocation nearestWrong = null;
        int nearestWrongDist = Integer.MAX_VALUE;
        for (MapInfo info : rc.senseNearbyMapInfos(targetRuin, 8)) {
            PaintType mark = info.getMark();
            if (mark == PaintType.EMPTY || mark == info.getPaint()) {
                continue;
            }
            MapLocation tile = info.getMapLocation();
            if (rc.isActionReady() && rc.canAttack(tile)) {
                rc.attack(tile, mark == PaintType.ALLY_SECONDARY);
                noPaintCounter = 0;
                ruinStallTurns = 0;
                return true;
            }
            int d = rc.getLocation().distanceSquaredTo(tile);
            if (d < nearestWrongDist) {
                nearestWrongDist = d;
                nearestWrong = tile;
            }
        }

        if (nearestWrong != null) {
            Nav.moveTo(rc, nearestWrong);
            return true;
        }

        if (rc.isActionReady()) {
            noPaintCounter++;
            ruinStallTurns = noPaintCounter;
        }
        if (noPaintCounter >= NO_PAINT_TOWER_THRESHOLD) {
            blockedRuin = targetRuin;
            blockedRuinUntilRound = rc.getRoundNum() + BLOCKED_RUIN_COOLDOWN;
            buildRuinTarget = null;
            buildPatternMarked = false;
            noPaintCounter = 0;
            ruinStallTurns = 0;
            return false;
        }
        return true;
    }

    static boolean trySrpCompletionOrWork(RobotController rc) throws GameActionException {
        if (rc.getNumberTowers() < RobotPlayer.SRP_UNLOCK_TOWERS) {
            clearSrpSticky();
            return false;
        }

        if (!isValidSrpTarget(rc, stickySrpTarget)) {
            clearSrpSticky();
        }

        if (stickySrpTarget == null || rc.getRoundNum() > stickySrpUntilRound) {
            MapLocation candidate = chooseSrpTarget(rc);
            if (candidate == null) {
                clearSrpSticky();
                return false;
            }
            if (stickySrpTarget == null || !stickySrpTarget.equals(candidate)) {
                srpStallTurns = 0;
                srpNoPaintTurns = 0;
            }
            stickySrpTarget = candidate;
            stickySrpUntilRound = rc.getRoundNum() + SRP_STICKY_WINDOW;
        }

        MapLocation center = stickySrpTarget;
        if (center == null) {
            return false;
        }

        if (rc.canCompleteResourcePattern(center)) {
            rc.completeResourcePattern(center);
            clearSrpSticky();
            return true;
        }

        if (rc.canSenseLocation(center)
            && rc.senseMapInfo(center).getMark() == PaintType.EMPTY
            && rc.canMarkResourcePattern(center)) {
            rc.markResourcePattern(center);
        }

        MapLocation bestWork = findNearestSrpWorkTile(rc, center);
        if (bestWork != null && rc.canAttack(bestWork)) {
            PaintType mark = rc.senseMapInfo(bestWork).getMark();
            rc.attack(bestWork, mark == PaintType.ALLY_SECONDARY);
            srpNoPaintTurns = 0;
            srpStallTurns = 0;
            stickySrpUntilRound = rc.getRoundNum() + SRP_STICKY_WINDOW;
            if (rc.canCompleteResourcePattern(center)) {
                rc.completeResourcePattern(center);
                clearSrpSticky();
            }
            return true;
        }

        int beforeDist = rc.getLocation().distanceSquaredTo(center);
        Nav.moveTo(rc, bestWork != null ? bestWork : center);
        int afterDist = rc.getLocation().distanceSquaredTo(center);
        if (afterDist < beforeDist) {
            srpStallTurns = 0;
        } else {
            srpStallTurns++;
        }

        if (bestWork != null && rc.isActionReady() && rc.canAttack(bestWork)) {
            PaintType mark = rc.senseMapInfo(bestWork).getMark();
            rc.attack(bestWork, mark == PaintType.ALLY_SECONDARY);
            srpNoPaintTurns = 0;
            srpStallTurns = 0;
            stickySrpUntilRound = rc.getRoundNum() + SRP_STICKY_WINDOW;
            if (rc.canCompleteResourcePattern(center)) {
                rc.completeResourcePattern(center);
                clearSrpSticky();
            }
            return true;
        }

        if (rc.isActionReady() && rc.getLocation().distanceSquaredTo(center) <= 2) {
            srpNoPaintTurns++;
        } else {
            srpNoPaintTurns = 0;
        }

        if (srpStallTurns > SRP_STALL_LIMIT || srpNoPaintTurns > SRP_NO_PAINT_LIMIT) {
            if (rc.getRoundNum() > stickySrpUntilRound || !hasMarkedSrpWork(rc, center)) {
                clearSrpSticky();
                return false;
            }
            srpStallTurns = 0;
            srpNoPaintTurns = 0;
        }

        return true;
    }

    static MapLocation chooseSrpTarget(RobotController rc) throws GameActionException {
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        for (MapInfo info : rc.senseNearbyMapInfos(-1)) {
            MapLocation center = info.getMapLocation();
            if (info.isWall() || info.hasRuin()) {
                continue;
            }
            int dist = rc.getLocation().distanceSquaredTo(center);
            if (dist > 20) {
                continue;
            }

            boolean completable = rc.canCompleteResourcePattern(center);
            boolean markable = rc.canMarkResourcePattern(center);
            boolean hasWork = hasMarkedSrpWork(rc, center);
            if (!completable && !markable && !hasWork && !info.isResourcePatternCenter()) {
                continue;
            }

            int score = 110 - dist * 5;
            if (completable) {
                score += 180;
            }
            if (hasWork) {
                score += 80;
            }
            if (stickySrpTarget != null && stickySrpTarget.equals(center)) {
                score += 25;
            }
            if ((center.x & 3) == 1 || (center.y & 3) == 1) {
                score += 6;
            }

            if (score > bestScore) {
                bestScore = score;
                best = center;
            }
        }

        return best;
    }

    static MapLocation findNearestSrpWorkTile(RobotController rc, MapLocation center) throws GameActionException {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo info : rc.senseNearbyMapInfos(center, 8)) {
            PaintType mark = info.getMark();
            if (mark != PaintType.ALLY_PRIMARY && mark != PaintType.ALLY_SECONDARY) {
                continue;
            }
            if (info.getPaint() == mark) {
                continue;
            }
            MapLocation tile = info.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(tile);
            if (dist < bestDist) {
                bestDist = dist;
                best = tile;
            }
        }

        return best;
    }

    static boolean hasMarkedSrpWork(RobotController rc, MapLocation center) throws GameActionException {
        if (center == null || !rc.canSenseLocation(center)) {
            return false;
        }
        for (MapInfo info : rc.senseNearbyMapInfos(center, 8)) {
            PaintType mark = info.getMark();
            if ((mark == PaintType.ALLY_PRIMARY || mark == PaintType.ALLY_SECONDARY)
                && info.getPaint() != mark) {
                return true;
            }
        }
        return false;
    }

    static boolean isValidSrpTarget(RobotController rc, MapLocation center) throws GameActionException {
        if (center == null || !rc.canSenseLocation(center)) {
            return false;
        }
        MapInfo info = rc.senseMapInfo(center);
        return !info.isWall() && !info.hasRuin();
    }

    static void clearSrpSticky() {
        stickySrpTarget = null;
        stickySrpUntilRound = -1;
        srpStallTurns = 0;
        srpNoPaintTurns = 0;
    }

    static void explore(RobotController rc) throws GameActionException {
        RobotPlayer.refreshExploreTargetIfNeeded(rc, false);
        Nav.moveTo(rc, RobotPlayer.exploreTarget);
    }
}
