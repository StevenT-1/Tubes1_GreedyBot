package bernbot;

import battlecode.common.*;

public class Soldier {

    static final int STICKY_BONUS = 25;

    static void run(RobotController rc) throws GameActionException {

        // Remember enemy towers + validate ruin memory
        RobotPlayer.rememberEnemyTowerIfVisible(rc);
        RobotPlayer.validateRuinMemory(rc);
        RobotPlayer.markSectorVisited(rc);

        // Scan for empty ruins and remember them
        scanAndRememberRuins(rc);

        // Sense enemies
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        // Combat / tower-pressure
        if (enemies.length > 0) {
            if (handleCombat(rc, enemies))
                return;
        }

        // Refill — only if truly low AND no completable work
        if (RobotPlayer.isLowPaint(rc) && !canFinishNearbyWork(rc)) {
            if (RobotPlayer.returnTarget == null) {
                RobotPlayer.returnTarget = rc.getLocation();
            }
            if (RobotPlayer.handleRefill(rc))
                return;
        }

        // Resume interrupted task
        if (RobotPlayer.returnTarget != null) {
            if (rc.getLocation().distanceSquaredTo(RobotPlayer.returnTarget) <= 2) {
                RobotPlayer.returnTarget = null;
            } else {
                RobotPlayer.greedyMove(rc, RobotPlayer.returnTarget);
                RobotPlayer.paintUnderfoot(rc);
                return;
            }
        }

        // Paint underfoot
        RobotPlayer.paintUnderfoot(rc);

        // Score all candidate objectives
        RobotPlayer.Candidate best = chooseBestObjective(rc);

        // Apply sticky / anti-stall
        best = RobotPlayer.applySticky(rc, best);

        // Execute
        switch (best.type) {
            case RobotPlayer.OBJ_BUILD_RUIN:
                executeRuinBuild(rc, best.loc);
                break;
            case RobotPlayer.OBJ_BUILD_SRP:
                executeSRP(rc, best.loc);
                break;
            case RobotPlayer.OBJ_PAINT_TILE:
                executePaint(rc, best.loc);
                break;
            case RobotPlayer.OBJ_PRESSURE_TOWER:
                executeTowerPressure(rc, best.loc);
                break;
            case RobotPlayer.OBJ_REMEMBERED_RUIN:
                executeRememberedRuin(rc, best.loc);
                break;
            default:
                RobotPlayer.executeExplore(rc);
                break;
        }

        // Final underfoot paint
        RobotPlayer.paintUnderfoot(rc);
    }
    // RUIN MEMORY

    static void scanAndRememberRuins(RobotController rc) throws GameActionException {
        for (MapLocation ruin : rc.senseNearbyRuins(-1)) {
            if (rc.canSenseRobotAtLocation(ruin)) {
                RobotInfo occ = rc.senseRobotAtLocation(ruin);
                if (occ != null) {
                    // Remember enemy towers
                    if (occ.getTeam() != rc.getTeam() && occ.getType().isTowerType()) {
                        RobotPlayer.knownEnemyTower = ruin;
                        RobotPlayer.knownEnemyTowerRound = rc.getRoundNum();
                    }
                    continue;
                }
            }
            // Empty ruin found — remember it
            RobotPlayer.rememberEmptyRuin(ruin, rc.getRoundNum());
        }
    }
    // COMBAT / TOWER PRESSURE

    static boolean handleCombat(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        int allyCount = rc.senseNearbyRobots(-1, rc.getTeam()).length;
        boolean outnumbered = enemies.length > allyCount + 1;
        boolean lowPaint = RobotPlayer.paintPercent(rc) < 20;

        if (outnumbered && lowPaint) {
            RobotInfo nearest = enemies[0];
            for (RobotInfo e : enemies) {
                if (rc.getLocation().distanceSquaredTo(e.getLocation()) < rc.getLocation()
                        .distanceSquaredTo(nearest.getLocation())) {
                    nearest = e;
                }
            }
            RobotPlayer.greedyMoveAway(rc, nearest.getLocation());
            return true;
        }

        RobotInfo bestTarget = null;
        int bestScore = Integer.MIN_VALUE;

        for (RobotInfo e : enemies) {
            int score = 0;
            if (e.getType().isTowerType())
                score += 200;
            else if (e.getType() == UnitType.SOLDIER)
                score += 100;
            else
                score += 60;
            score += Math.max(0, 100 - e.getHealth());
            if (rc.canAttack(e.getLocation()))
                score += 50;
            score -= rc.getLocation().distanceSquaredTo(e.getLocation()) * 2;
            if (RobotPlayer.isInKnownEnemyTowerRange(rc, e.getLocation())
                    && !e.getType().isTowerType())
                score -= 80;

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
            if (!bestTarget.getType().isTowerType() && rc.isMovementReady()) {
                RobotPlayer.greedyMoveAway(rc, targetLoc);
            }
            return true;
        }

        if (bestScore > 80) {
            RobotPlayer.greedyMove(rc, targetLoc);
            if (rc.isActionReady() && rc.canAttack(targetLoc))
                rc.attack(targetLoc);
            return true;
        }
        return false;
    }
    // OBJECTIVE SELECTION

    static RobotPlayer.Candidate chooseBestObjective(RobotController rc) throws GameActionException {
        RobotPlayer.Candidate ruin = scoreBestRuin(rc);
        RobotPlayer.Candidate remembered = scoreRememberedRuin(rc);
        RobotPlayer.Candidate srp = scoreBestSRP(rc);
        RobotPlayer.Candidate paint = scoreBestPaintTile(rc);
        RobotPlayer.Candidate pressure = scoreTowerPressure(rc);
        RobotPlayer.Candidate explore = new RobotPlayer.Candidate(
                RobotPlayer.OBJ_EXPLORE, null, RobotPlayer.scoreExplore(rc));

        RobotPlayer.Candidate best = explore;

        // Compare all candidates with sticky bonus
        best = pickBetter(best, ruin);
        best = pickBetter(best, remembered);
        best = pickBetter(best, srp);
        best = pickBetter(best, paint);
        best = pickBetter(best, pressure);

        return best;
    }

    static RobotPlayer.Candidate pickBetter(RobotPlayer.Candidate current, RobotPlayer.Candidate challenger) {
        if (challenger == null || challenger.loc == null)
            return current;
        int cs = current.score + stickyBonus(current);
        int ch = challenger.score + stickyBonus(challenger);
        return ch > cs ? challenger : current;
    }

    static int stickyBonus(RobotPlayer.Candidate c) {
        if (c.type == RobotPlayer.objectiveType
                && c.loc != null && c.loc.equals(RobotPlayer.objectiveLoc)) {
            return STICKY_BONUS;
        }
        return 0;
    }

    static boolean canFinishNearbyWork(RobotController rc) throws GameActionException {
        UnitType tt = RobotPlayer.chooseGreedyTowerType(rc);
        for (MapLocation ruin : rc.senseNearbyRuins(-1)) {
            if (rc.canCompleteTowerPattern(tt, ruin))
                return true;
        }
        for (MapInfo t : rc.senseNearbyMapInfos()) {
            if (rc.canCompleteResourcePattern(t.getMapLocation()))
                return true;
        }
        return false;
    }

    static RobotPlayer.Candidate scoreBestRuin(RobotController rc) throws GameActionException {
        RobotPlayer.Candidate best = null;

        for (MapLocation ruin : rc.senseNearbyRuins(-1)) {
            if (rc.canSenseRobotAtLocation(ruin)) {
                RobotInfo occ = rc.senseRobotAtLocation(ruin);
                if (occ != null)
                    continue;
            }

            int dist = rc.getLocation().distanceSquaredTo(ruin);
            int enemyPaint = 0, workLeft = 0;

            for (MapInfo t : rc.senseNearbyMapInfos(ruin, 8)) {
                if (t.getPaint().isEnemy())
                    enemyPaint++;
                if (t.getMark() != PaintType.EMPTY && t.getMark() != t.getPaint())
                    workLeft++;
            }

            boolean allyBuilding = false;
            for (RobotInfo ally : rc.senseNearbyRobots(ruin, 8, rc.getTeam())) {
                if (ally.getType() == UnitType.SOLDIER && ally.getID() != rc.getID()
                        && ally.getLocation().distanceSquaredTo(ruin) <= 2) {
                    allyBuilding = true;
                    break;
                }
            }
            if (allyBuilding)
                continue;

            int score = 180;
            score -= dist * 7;
            score -= enemyPaint * 12;
            score -= workLeft * 5;

            UnitType tt = RobotPlayer.chooseGreedyTowerType(rc);
            if (rc.canCompleteTowerPattern(tt, ruin))
                score += 250;

            RobotPlayer.Candidate c = new RobotPlayer.Candidate(RobotPlayer.OBJ_BUILD_RUIN, ruin, score);
            if (best == null || c.score > best.score)
                best = c;
        }
        return best;
    }

    static RobotPlayer.Candidate scoreRememberedRuin(RobotController rc) throws GameActionException {
        if (!RobotPlayer.hasFreshRememberedRuin(rc))
            return null;

        MapLocation ruin = RobotPlayer.lastSeenEmptyRuin;
        int dist = rc.getLocation().distanceSquaredTo(ruin);

        // Score: decays with distance but still valuable
        int score = 120 - dist / 3;

        // Bonus if paint is enough to build
        if (RobotPlayer.paintPercent(rc) >= 40)
            score += 30;
        // Bonus if a refill tower is on the way
        MapLocation allyTower = RobotPlayer.findNearestAllyTower(rc);
        if (allyTower != null) {
            int towerToRuin = allyTower.distanceSquaredTo(ruin);
            int towerToMe = allyTower.distanceSquaredTo(rc.getLocation());
            if (towerToRuin < dist && towerToMe < dist)
                score += 20; // refill en route
        }

        // Freshness bonus
        int age = rc.getRoundNum() - RobotPlayer.lastSeenEmptyRuinRound;
        score -= age / 2;

        return new RobotPlayer.Candidate(RobotPlayer.OBJ_REMEMBERED_RUIN, ruin, score);
    }

    static RobotPlayer.Candidate scoreBestSRP(RobotController rc) throws GameActionException {
        if (rc.getNumberTowers() < RobotPlayer.HEALTHY_TOWER_COUNT)
            return null;
        if (rc.getPaint() < 30)
            return null;

        if (RobotPlayer.objectiveType == RobotPlayer.OBJ_BUILD_SRP
                && RobotPlayer.objectiveLoc != null
                && rc.getRoundNum() - RobotPlayer.objectiveLastRound <= 6
                && rc.getLocation().distanceSquaredTo(RobotPlayer.objectiveLoc) <= 36) {
            int s = 140 - rc.getLocation().distanceSquaredTo(RobotPlayer.objectiveLoc) * 3;
            return new RobotPlayer.Candidate(RobotPlayer.OBJ_BUILD_SRP, RobotPlayer.objectiveLoc, s);
        }

        MapLocation nearTower = RobotPlayer.findNearestAllyTower(rc);
        MapLocation existing = findVisibleSRPCenter(rc, null);
        if (existing != null) {
            int score = 150 - rc.getLocation().distanceSquaredTo(existing) * 4;
            score += 20 * countNeededSRPWork(rc, existing);
            if (rc.canCompleteResourcePattern(existing))
                score += 200;
            return new RobotPlayer.Candidate(RobotPlayer.OBJ_BUILD_SRP, existing, score);
        }

        RobotPlayer.Candidate best = null;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (Clock.getBytecodeNum() > 11000)
                break;
            if (tile.isWall() || tile.hasRuin())
                continue;
            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist > 20)
                continue;
            if (!tile.getPaint().isAlly())
                continue;

            boolean completable = rc.canCompleteResourcePattern(loc);
            boolean markable = !completable && rc.canMarkResourcePattern(loc);
            if (!completable && !markable && !tile.isResourcePatternCenter())
                continue;
            if (!completable && !tile.isResourcePatternCenter() && !canStartFreshSRPHere(rc, loc))
                continue;

            int needed = countNeededSRPWork(rc, loc);
            if (!completable && !tile.isResourcePatternCenter() && needed < 2)
                continue;

            int score = 90 - dist * 4 + needed * 14;
            if (completable)
                score += 180;
            if (nearTower != null)
                score -= nearTower.distanceSquaredTo(loc) / 4;

            RobotPlayer.Candidate c = new RobotPlayer.Candidate(RobotPlayer.OBJ_BUILD_SRP, loc, score);
            if (best == null || c.score > best.score)
                best = c;
        }
        return best;
    }

    static RobotPlayer.Candidate scoreBestPaintTile(RobotController rc) throws GameActionException {
        RobotPlayer.Candidate best = null;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (Clock.getBytecodeNum() > 12000)
                break;
            if (tile.isWall() || tile.hasRuin())
                continue;
            PaintType p = tile.getPaint();
            if (p.isAlly())
                continue;

            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            int score = p.isEnemy() ? 90 : 50;
            score -= dist * 6;

            for (MapLocation ruin : rc.senseNearbyRuins(-1)) {
                if (loc.distanceSquaredTo(ruin) <= 8) {
                    score += 15;
                    break;
                }
            }

            RobotPlayer.Candidate c = new RobotPlayer.Candidate(RobotPlayer.OBJ_PAINT_TILE, loc, score);
            if (best == null || c.score > best.score)
                best = c;
        }
        return best;
    }

    static boolean isAllyMark(PaintType mark) {
        return mark == PaintType.ALLY_PRIMARY || mark == PaintType.ALLY_SECONDARY;
    }

    static boolean isProtectedSRPZone(RobotController rc, MapLocation loc) throws GameActionException {
        for (MapInfo t : rc.senseNearbyMapInfos()) {
            MapLocation m = t.getMapLocation();
            if (m.distanceSquaredTo(loc) > 20)
                continue;
            if (t.isResourcePatternCenter())
                return true;
            if (isAllyMark(t.getMark()))
                return true;
        }
        return false;
    }

    static MapLocation findVisibleSRPCenter(RobotController rc, MapLocation around) throws GameActionException {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapInfo t : rc.senseNearbyMapInfos()) {
            if (!t.isResourcePatternCenter())
                continue;
            MapLocation m = t.getMapLocation();
            if (around != null && m.distanceSquaredTo(around) > 20)
                continue;
            int d = rc.getLocation().distanceSquaredTo(m);
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    static int countNeededSRPWork(RobotController rc, MapLocation center) throws GameActionException {
        int need = 0;
        for (MapInfo t : rc.senseNearbyMapInfos(center, 8)) {
            PaintType mark = t.getMark();
            if (isAllyMark(mark) && mark != t.getPaint())
                need++;
        }
        return need;
    }

    static boolean canStartFreshSRPHere(RobotController rc, MapLocation center) throws GameActionException {
        for (MapInfo t : rc.senseNearbyMapInfos()) {
            MapLocation m = t.getMapLocation();
            if (m.equals(center))
                continue;
            int d = m.distanceSquaredTo(center);
            if (d > 20)
                continue;
            if (t.isResourcePatternCenter())
                return false;
            if (isAllyMark(t.getMark()))
                return false;
        }
        return true;
    }

    static RobotPlayer.Candidate scoreTowerPressure(RobotController rc) throws GameActionException {
        if (!RobotPlayer.isEnemyTowerFresh(rc))
            return null;
        int dist = rc.getLocation().distanceSquaredTo(RobotPlayer.knownEnemyTower);
        int score = 130 - dist * 3;
        if (RobotPlayer.isLowPaint(rc))
            score -= 60;
        int nearAllies = rc.senseNearbyRobots(RobotPlayer.knownEnemyTower, 20, rc.getTeam()).length;
        score += nearAllies * 8;
        return new RobotPlayer.Candidate(RobotPlayer.OBJ_PRESSURE_TOWER, RobotPlayer.knownEnemyTower, score);
    }
    // OBJECTIVE EXECUTION

    static void executeRuinBuild(RobotController rc, MapLocation ruin) throws GameActionException {
        if (ruin == null) {
            RobotPlayer.executeExplore(rc);
            return;
        }
        UnitType tt = RobotPlayer.chooseGreedyTowerType(rc);

        if (rc.canCompleteTowerPattern(tt, ruin)) {
            rc.completeTowerPattern(tt, ruin);
            quickRefillFromTower(rc, ruin);
            // Clear ruin memory if this was the remembered ruin
            if (ruin.equals(RobotPlayer.lastSeenEmptyRuin)) {
                RobotPlayer.lastSeenEmptyRuin = null;
            }
            return;
        }

        RobotPlayer.greedyMove(rc, ruin);
        if (rc.canMarkTowerPattern(tt, ruin))
            rc.markTowerPattern(tt, ruin);

        if (rc.isActionReady()) {
            MapLocation bestWork = null;
            int bestDist = Integer.MAX_VALUE;
            for (MapInfo t : rc.senseNearbyMapInfos(ruin, 8)) {
                PaintType mark = t.getMark();
                if ((mark == PaintType.ALLY_PRIMARY || mark == PaintType.ALLY_SECONDARY)
                        && mark != t.getPaint()) {
                    int d = rc.getLocation().distanceSquaredTo(t.getMapLocation());
                    if (d < bestDist) {
                        bestDist = d;
                        bestWork = t.getMapLocation();
                    }
                }
            }
            if (bestWork != null && rc.canAttack(bestWork)) {
                boolean sec = rc.senseMapInfo(bestWork).getMark() == PaintType.ALLY_SECONDARY;
                rc.attack(bestWork, sec);
            }
        }

        if (rc.canCompleteTowerPattern(tt, ruin)) {
            rc.completeTowerPattern(tt, ruin);
            quickRefillFromTower(rc, ruin);
            if (ruin.equals(RobotPlayer.lastSeenEmptyRuin)) {
                RobotPlayer.lastSeenEmptyRuin = null;
            }
        }
    }

    static void executeRememberedRuin(RobotController rc, MapLocation ruin) throws GameActionException {
        if (ruin == null) {
            RobotPlayer.executeExplore(rc);
            return;
        }

        // If we can now sense the ruin, switch to normal ruin build
        if (rc.canSenseLocation(ruin)) {
            if (rc.canSenseRobotAtLocation(ruin)) {
                RobotInfo occ = rc.senseRobotAtLocation(ruin);
                if (occ != null) {
                    // Occupied: invalidate memory
                    RobotPlayer.lastSeenEmptyRuin = null;
                    RobotPlayer.executeExplore(rc);
                    return;
                }
            }
            // Can see it and it's empty — treat as real ruin build
            executeRuinBuild(rc, ruin);
            return;
        }

        // Move toward remembered location
        RobotPlayer.greedyMove(rc, ruin);
        RobotPlayer.paintUnderfoot(rc);
    }

    static void quickRefillFromTower(RobotController rc, MapLocation tower) throws GameActionException {
        if (RobotPlayer.paintPercent(rc) >= 50)
            return;
        int need = rc.getType().paintCapacity - rc.getPaint();
        int take = -Math.min(need, 60);
        if (rc.canTransferPaint(tower, take))
            rc.transferPaint(tower, take);
    }

    static void executeSRP(RobotController rc, MapLocation center) throws GameActionException {
        if (center == null) {
            RobotPlayer.executeExplore(rc);
            return;
        }
        MapLocation existing = findVisibleSRPCenter(rc, center);
        if (existing != null)
            center = existing;
        if (rc.canCompleteResourcePattern(center)) {
            rc.completeResourcePattern(center);
            return;
        }
        if (!rc.senseMapInfo(center).isResourcePatternCenter() && !canStartFreshSRPHere(rc, center)) {
            RobotPlayer.executeExplore(rc);
            return;
        }
        if (rc.canMarkResourcePattern(center))
            rc.markResourcePattern(center);

        if (rc.isActionReady()) {
            MapLocation bestWork = null;
            int bestDist = Integer.MAX_VALUE;
            for (MapInfo t : rc.senseNearbyMapInfos(center, 8)) {
                PaintType mark = t.getMark();
                if ((mark == PaintType.ALLY_PRIMARY || mark == PaintType.ALLY_SECONDARY)
                        && mark != t.getPaint()) {
                    int d = rc.getLocation().distanceSquaredTo(t.getMapLocation());
                    if (d < bestDist) {
                        bestDist = d;
                        bestWork = t.getMapLocation();
                    }
                }
            }
            if (bestWork != null && rc.canAttack(bestWork)) {
                boolean sec = rc.senseMapInfo(bestWork).getMark() == PaintType.ALLY_SECONDARY;
                rc.attack(bestWork, sec);
            }
        }
        if (rc.canCompleteResourcePattern(center))
            rc.completeResourcePattern(center);
        RobotPlayer.greedyMove(rc, center);
    }

    static void executePaint(RobotController rc, MapLocation target) throws GameActionException {
        if (target == null) {
            RobotPlayer.executeExplore(rc);
            return;
        }
        if (rc.canAttack(target)) {
            rc.attack(target, RobotPlayer.chooseSecondary(target));
            return;
        }
        RobotPlayer.greedyMove(rc, target);
        if (rc.isActionReady() && rc.canAttack(target))
            rc.attack(target, RobotPlayer.chooseSecondary(target));
    }

    static void executeTowerPressure(RobotController rc, MapLocation tower) throws GameActionException {
        if (tower == null) {
            RobotPlayer.executeExplore(rc);
            return;
        }
        if (rc.canAttack(tower)) {
            rc.attack(tower);
            if (rc.isMovementReady())
                RobotPlayer.greedyMoveAway(rc, tower);
            return;
        }
        RobotPlayer.greedyMove(rc, tower);
        if (rc.isActionReady() && rc.canAttack(tower))
            rc.attack(tower);
    }
}
