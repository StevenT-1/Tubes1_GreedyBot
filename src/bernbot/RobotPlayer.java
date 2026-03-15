package bernbot;

import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {
    static final Direction[] DIRECTIONS = {
            Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
            Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };
    static final Direction[] CARDINAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    static final int OBJ_EXPLORE = 0;
    static final int OBJ_BUILD_RUIN = 1;
    static final int OBJ_BUILD_SRP = 2;
    static final int OBJ_PAINT_TILE = 3;
    static final int OBJ_PRESSURE_TOWER = 4;
    static final int OBJ_REMEMBERED_RUIN = 5;
    static final int PHASE_EARLY = 0;
    static final int PHASE_MID = 1;
    static final int PHASE_LATE = 2;
    static final int EARLY_ROUND_LIMIT = 400;
    static final int MID_ROUND_LIMIT = 1100;
    static final int HEALTHY_TOWER_COUNT = 4;

    // Anti-stall
    static final int STALL_ABANDON_TURNS = 7;
    static Random rng;
    static boolean initialised = false;
    static int turnCount = 0;

    static MapLocation homeTower;
    static MapLocation returnTarget;

    // Enemy tower memory
    static MapLocation knownEnemyTower;
    static int knownEnemyTowerRound = -9999;
    static int towerLastEnemySeen = -9999;

    // Sticky objective tracking
    static int objectiveType = OBJ_EXPLORE;
    static MapLocation objectiveLoc;
    static int objectiveDist = Integer.MAX_VALUE;
    static int objectiveStallTurns = 0;
    static int objectiveLastRound = -9999;

    // Map symmetry guess
    static int symmetryGuess = 1;
    // SECTOR EXPLORATION STATE

    static int sectorW, sectorH; // grid dimensions (3-5)
    static int sCellW, sCellH; // pixel width/height of each sector
    static int[][] sectorLastVisited; // round each sector was last visited
    static int currentSectorX, currentSectorY;
    static int currentWaypointIdx; // 0-2 within sector
    static int sectorStallTurns;
    static int lastSectorChangeRound = -9999;

    // Ruin memory
    static MapLocation lastSeenEmptyRuin;
    static int lastSeenEmptyRuinRound = -9999;
    static class Candidate {
        final int type;
        final MapLocation loc;
        final int score;

        Candidate(int type, MapLocation loc, int score) {
            this.type = type;
            this.loc = loc;
            this.score = score;
        }
    }
    // ENTRY POINT

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            turnCount++;
            try {
                initialise(rc);
                switch (rc.getType()) {
                    case SOLDIER:
                        Soldier.run(rc);
                        break;
                    case MOPPER:
                        Mopper.run(rc);
                        break;
                    case SPLASHER:
                        Splasher.run(rc);
                        break;
                    default:
                        Tower.run(rc);
                        break;
                }
            } catch (GameActionException e) {
                System.out.println("GAE");
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("EX");
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }
    static void initialise(RobotController rc) throws GameActionException {
        if (initialised)
            return;
        initialised = true;
        rng = new Random(rc.getID() * 71L + 6147L);

        if (!rc.getType().isTowerType()) {
            homeTower = findNearestAllyTower(rc);
            guessSymmetry(rc);
            initSectorGrid(rc);
            chooseInitialSector(rc);
        }
    }
    // SECTOR GRID SETUP

    static void initSectorGrid(RobotController rc) {
        int w = rc.getMapWidth(), h = rc.getMapHeight();
        int area = w * h;

        // Dynamic grid size based on map
        if (area <= 900) {
            sectorW = 3;
            sectorH = 3;
        } // <=30x30
        else if (area <= 2500) {
            sectorW = 4;
            sectorH = 4;
        } // <=50x50
        else {
            sectorW = 5;
            sectorH = 5;
        } // large maps

        sCellW = w / sectorW;
        sCellH = h / sectorH;

        sectorLastVisited = new int[sectorW][sectorH];
        // Initialize all to -9999 so every sector starts "stale"
        for (int i = 0; i < sectorW; i++)
            for (int j = 0; j < sectorH; j++)
                sectorLastVisited[i][j] = -9999;
    }

    static void chooseInitialSector(RobotController rc) {
        int id = rc.getID();

        // Use robot ID to distribute among corner sectors first
        int cornerIdx = Math.floorMod(id, 4);
        switch (cornerIdx) {
            case 0:
                currentSectorX = 0;
                currentSectorY = 0;
                break;
            case 1:
                currentSectorX = sectorW - 1;
                currentSectorY = 0;
                break;
            case 2:
                currentSectorX = 0;
                currentSectorY = sectorH - 1;
                break;
            default:
                currentSectorX = sectorW - 1;
                currentSectorY = sectorH - 1;
                break;
        }

        // If bot ID is beyond first 4, use edge/interior sectors
        int batch = Math.floorMod(id / 4, sectorW * sectorH);
        if (batch > 0) {
            currentSectorX = batch % sectorW;
            currentSectorY = batch / sectorW % sectorH;
        }

        currentWaypointIdx = 0;
        sectorStallTurns = 0;
        lastSectorChangeRound = rc.getRoundNum();
    }
    // SECTOR HELPERS

    static MapLocation sectorCenter(RobotController rc, int sx, int sy) {
        int cx = sx * sCellW + sCellW / 2;
        int cy = sy * sCellH + sCellH / 2;
        return clampToMap(rc, cx, cy);
    }

    static MapLocation sectorWaypoint(RobotController rc, int sx, int sy, int wpIdx) {
        int x0 = sx * sCellW, y0 = sy * sCellH;
        int x1 = x0 + sCellW - 1, y1 = y0 + sCellH - 1;
        int mx = (x0 + x1) / 2, my = (y0 + y1) / 2;

        switch (wpIdx % 3) {
            case 0:
                return clampToMap(rc, x0 + sCellW / 4, y0 + sCellH / 4); // near-corner
            case 1:
                return clampToMap(rc, x1 - sCellW / 4, y1 - sCellH / 4); // far-corner
            default:
                return clampToMap(rc, mx + (rng.nextInt(3) - 1), my + (rng.nextInt(3) - 1)); // center jitter
        }
    }

    static MapLocation clampToMap(RobotController rc, int x, int y) {
        return new MapLocation(
                Math.max(1, Math.min(rc.getMapWidth() - 2, x)),
                Math.max(1, Math.min(rc.getMapHeight() - 2, y)));
    }

    static boolean isSectorCorner(int sx, int sy) {
        return (sx == 0 || sx == sectorW - 1) && (sy == 0 || sy == sectorH - 1);
    }

    static boolean isSectorEdge(int sx, int sy) {
        return sx == 0 || sy == 0 || sx == sectorW - 1 || sy == sectorH - 1;
    }

    static boolean isSectorEnemySide(RobotController rc, int sx, int sy) {
        if (homeTower == null)
            return false;
        MapLocation center = sectorCenter(rc, sx, sy);
        MapLocation enemyGuess = guessEnemyTowerLocation(rc);
        if (enemyGuess == null)
            return false;
        return center.distanceSquaredTo(enemyGuess) < center.distanceSquaredTo(homeTower);
    }

    static void markSectorVisited(RobotController rc) {
        int sx = locToSectorX(rc.getLocation());
        int sy = locToSectorY(rc.getLocation());
        if (sx >= 0 && sx < sectorW && sy >= 0 && sy < sectorH) {
            sectorLastVisited[sx][sy] = rc.getRoundNum();
        }
    }

    static int locToSectorX(MapLocation loc) {
        return Math.min(loc.x / Math.max(1, sCellW), sectorW - 1);
    }

    static int locToSectorY(MapLocation loc) {
        return Math.min(loc.y / Math.max(1, sCellH), sectorH - 1);
    }
    // SECTOR SCORING + SELECTION

    static void chooseExploreSector(RobotController rc) {
        int bestScore = Integer.MIN_VALUE;
        int bestSX = currentSectorX, bestSY = currentSectorY;
        int round = rc.getRoundNum();
        int phase = getPhase(rc);
        int mapArea = rc.getMapWidth() * rc.getMapHeight();
        boolean largeMap = mapArea > 1600;

        for (int sx = 0; sx < sectorW; sx++) {
            for (int sy = 0; sy < sectorH; sy++) {
                int age = round - sectorLastVisited[sx][sy];
                MapLocation center = sectorCenter(rc, sx, sy);
                int dist = rc.getLocation().distanceSquaredTo(center);

                int score = 0;

                // Staleness: older sectors are more valuable
                score += Math.min(age, 500);

                // Corner bonus: critical on large maps early
                if (isSectorCorner(sx, sy)) {
                    score += largeMap ? 120 : 50;
                    if (phase == PHASE_EARLY)
                        score += 60;
                }
                // Edge bonus
                else if (isSectorEdge(sx, sy)) {
                    score += largeMap ? 40 : 15;
                }

                // Enemy-side bonus mid/late
                if (phase != PHASE_EARLY && isSectorEnemySide(rc, sx, sy)) {
                    score += 30;
                }

                // Distance penalty
                score -= dist / 5;

                // Sticky bonus: prefer current sector if we haven't swept it yet
                if (sx == currentSectorX && sy == currentSectorY
                        && currentWaypointIdx < 3) {
                    score += 35;
                }

                // Penalty if just visited
                if (age < 30)
                    score -= 80;

                if (score > bestScore) {
                    bestScore = score;
                    bestSX = sx;
                    bestSY = sy;
                }
            }
        }

        if (bestSX != currentSectorX || bestSY != currentSectorY) {
            currentSectorX = bestSX;
            currentSectorY = bestSY;
            currentWaypointIdx = 0;
            sectorStallTurns = 0;
            lastSectorChangeRound = rc.getRoundNum();
        }
    }

    static int scoreExplore(RobotController rc) {
        MapLocation wp = sectorWaypoint(rc, currentSectorX, currentSectorY, currentWaypointIdx);
        int dist = rc.getLocation().distanceSquaredTo(wp);
        int age = rc.getRoundNum() - sectorLastVisited[currentSectorX][currentSectorY];
        int score = 25 + Math.min(age / 5, 40) - dist / 8;
        return score;
    }
    // EXPLORATION EXECUTION

    static void executeExplore(RobotController rc) throws GameActionException {
        // Mark current tile's sector as visited
        markSectorVisited(rc);

        MapLocation wp = sectorWaypoint(rc, currentSectorX, currentSectorY, currentWaypointIdx);
        int dist = rc.getLocation().distanceSquaredTo(wp);

        // Close to waypoint → advance
        if (dist <= 8) {
            currentWaypointIdx++;
            if (currentWaypointIdx >= 3) {
                // Finished sweeping this sector, pick a new one
                sectorLastVisited[currentSectorX][currentSectorY] = rc.getRoundNum();
                chooseExploreSector(rc);
                wp = sectorWaypoint(rc, currentSectorX, currentSectorY, currentWaypointIdx);
            } else {
                wp = sectorWaypoint(rc, currentSectorX, currentSectorY, currentWaypointIdx);
            }
            sectorStallTurns = 0;
        }

        // Anti-stall check
        if (objectiveType == OBJ_EXPLORE) {
            int newDist = rc.getLocation().distanceSquaredTo(wp);
            if (newDist >= objectiveDist) {
                sectorStallTurns++;
            } else {
                sectorStallTurns = Math.max(0, sectorStallTurns - 1);
            }
            objectiveDist = newDist;

            // Escalating anti-stall: waypoint → adjacent → full resample
            if (sectorStallTurns >= 5) {
                currentWaypointIdx++;
                if (currentWaypointIdx >= 3) {
                    // Try adjacent sector
                    switchToAdjacentSector(rc);
                }
                sectorStallTurns = 0;
                wp = sectorWaypoint(rc, currentSectorX, currentSectorY, currentWaypointIdx);
            }
            if (sectorStallTurns >= 10) {
                chooseExploreSector(rc);
                sectorStallTurns = 0;
                wp = sectorWaypoint(rc, currentSectorX, currentSectorY, currentWaypointIdx);
            }
        }

        // Move with zig-zag bias
        greedyMoveWithZigZag(rc, wp);
    }

    static void switchToAdjacentSector(RobotController rc) {
        int bestAge = -1;
        int bestSX = currentSectorX, bestSY = currentSectorY;
        int round = rc.getRoundNum();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0)
                    continue;
                int nx = currentSectorX + dx, ny = currentSectorY + dy;
                if (nx < 0 || nx >= sectorW || ny < 0 || ny >= sectorH)
                    continue;
                int age = round - sectorLastVisited[nx][ny];
                if (age > bestAge) {
                    bestAge = age;
                    bestSX = nx;
                    bestSY = ny;
                }
            }
        }
        currentSectorX = bestSX;
        currentSectorY = bestSY;
        currentWaypointIdx = 0;
        sectorStallTurns = 0;
        lastSectorChangeRound = rc.getRoundNum();
    }
    // RUIN MEMORY

    static void rememberEmptyRuin(MapLocation ruin, int round) {
        // Only remember if more recent or closer than existing memory
        if (lastSeenEmptyRuin == null || round - lastSeenEmptyRuinRound > 10) {
            lastSeenEmptyRuin = ruin;
            lastSeenEmptyRuinRound = round;
        }
    }

    static boolean hasFreshRememberedRuin(RobotController rc) {
        return lastSeenEmptyRuin != null
                && rc.getRoundNum() - lastSeenEmptyRuinRound <= 40;
    }

    static void validateRuinMemory(RobotController rc) throws GameActionException {
        if (lastSeenEmptyRuin == null)
            return;
        if (rc.canSenseLocation(lastSeenEmptyRuin)) {
            if (rc.canSenseRobotAtLocation(lastSeenEmptyRuin)) {
                RobotInfo occ = rc.senseRobotAtLocation(lastSeenEmptyRuin);
                if (occ != null) {
                    lastSeenEmptyRuin = null;
                    lastSeenEmptyRuinRound = -9999;
                }
            }
        }
    }
    // GREEDY MOVEMENT

    static void greedyMove(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady() || target == null)
            return;

        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;
        MapLocation cur = rc.getLocation();

        for (Direction d : DIRECTIONS) {
            if (!rc.canMove(d))
                continue;
            MapLocation next = cur.add(d);

            int score = (cur.distanceSquaredTo(target) - next.distanceSquaredTo(target)) * 7;

            if (rc.canSenseLocation(next)) {
                PaintType paint = rc.senseMapInfo(next).getPaint();
                if (paint.isAlly())
                    score += 6;
                else if (paint == PaintType.EMPTY)
                    score += 1;
                else if (paint.isEnemy())
                    score -= 8;
            }

            score -= rc.senseNearbyRobots(next, 2, rc.getTeam()).length * 3;

            if (score > bestScore || (score == bestScore && rng.nextInt(2) == 0)) {
                bestScore = score;
                bestDir = d;
            }
        }

        if (bestDir != null && rc.canMove(bestDir))
            rc.move(bestDir);
    }

    static void greedyMoveWithZigZag(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady() || target == null)
            return;

        Direction toTarget = rc.getLocation().directionTo(target);
        // Perpendicular directions for zig-zag
        Direction perpCW = toTarget.rotateRight().rotateRight();
        Direction perpCCW = toTarget.rotateLeft().rotateLeft();
        boolean zigPhase = (rc.getRoundNum() % 6) < 3; // flip every 3 rounds

        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;
        MapLocation cur = rc.getLocation();

        for (Direction d : DIRECTIONS) {
            if (!rc.canMove(d))
                continue;
            MapLocation next = cur.add(d);

            int score = (cur.distanceSquaredTo(target) - next.distanceSquaredTo(target)) * 6;

            if (rc.canSenseLocation(next)) {
                PaintType paint = rc.senseMapInfo(next).getPaint();
                if (paint.isAlly())
                    score += 5;
                else if (paint == PaintType.EMPTY)
                    score += 1;
                else if (paint.isEnemy())
                    score -= 7;
            }

            score -= rc.senseNearbyRobots(next, 2, rc.getTeam()).length * 3;

            // Zig-zag: small perpendicular bias
            if (zigPhase && (d == perpCW || d == toTarget.rotateRight()))
                score += 3;
            if (!zigPhase && (d == perpCCW || d == toTarget.rotateLeft()))
                score += 3;

            if (score > bestScore || (score == bestScore && rng.nextInt(2) == 0)) {
                bestScore = score;
                bestDir = d;
            }
        }

        if (bestDir != null && rc.canMove(bestDir))
            rc.move(bestDir);
    }

    static void greedyMoveAway(RobotController rc, MapLocation danger) throws GameActionException {
        if (!rc.isMovementReady() || danger == null)
            return;
        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;
        for (Direction d : DIRECTIONS) {
            if (!rc.canMove(d))
                continue;
            MapLocation next = rc.getLocation().add(d);
            int score = next.distanceSquaredTo(danger) * 6;
            if (rc.canSenseLocation(next)) {
                PaintType p = rc.senseMapInfo(next).getPaint();
                if (p.isEnemy())
                    score -= 7;
                else if (p.isAlly())
                    score += 3;
            }
            score -= rc.senseNearbyRobots(next, 2, rc.getTeam()).length * 2;
            if (score > bestScore || (score == bestScore && rng.nextInt(2) == 0)) {
                bestScore = score;
                bestDir = d;
            }
        }
        if (bestDir != null && rc.canMove(bestDir))
            rc.move(bestDir);
    }
    // PAINT REFILL

    static boolean handleRefill(RobotController rc) throws GameActionException {
        if (!isLowPaint(rc))
            return false;
        MapLocation tower = findNearestAllyTower(rc);
        if (tower == null)
            tower = homeTower;
        if (tower == null)
            return false;

        int need = rc.getType().paintCapacity - rc.getPaint();
        int take = -Math.min(need, 80);
        if (rc.canTransferPaint(tower, take)) {
            rc.transferPaint(tower, take);
            return true;
        }
        greedyMove(rc, tower);
        if (rc.canTransferPaint(tower, take))
            rc.transferPaint(tower, take);
        return true;
    }

    static boolean isLowPaint(RobotController rc) {
        return rc.getPaint() * 4 < rc.getType().paintCapacity;
    }

    static int paintPercent(RobotController rc) {
        return (rc.getPaint() * 100) / Math.max(1, rc.getType().paintCapacity);
    }
    // PAINT UNDERFOOT

    static void paintUnderfoot(RobotController rc) throws GameActionException {
        if (!rc.isActionReady())
            return;
        MapLocation here = rc.getLocation();
        if (!rc.canAttack(here))
            return;
        MapInfo info = rc.senseMapInfo(here);
        if (!info.getPaint().isAlly()) {
            rc.attack(here, chooseSecondary(here));
        }
    }
    // ENEMY TOWER MEMORY

    static void rememberEnemyTowerIfVisible(RobotController rc) throws GameActionException {
        for (RobotInfo enemy : rc.senseNearbyRobots(-1, rc.getTeam().opponent())) {
            if (enemy.getType().isTowerType()) {
                knownEnemyTower = enemy.getLocation();
                knownEnemyTowerRound = rc.getRoundNum();
                return;
            }
        }
    }

    static boolean isEnemyTowerFresh(RobotController rc) {
        return knownEnemyTower != null && rc.getRoundNum() - knownEnemyTowerRound <= 25;
    }

    static boolean isInKnownEnemyTowerRange(RobotController rc, MapLocation loc) {
        if (!isEnemyTowerFresh(rc))
            return false;
        return loc.distanceSquaredTo(knownEnemyTower) <= UnitType.LEVEL_ONE_DEFENSE_TOWER.actionRadiusSquared;
    }
    // STICKY OBJECTIVE + ANTI-STALL

    static Candidate applySticky(RobotController rc, Candidate c) {
        if (c == null || c.loc == null)
            return resetToExplore(rc);

        int dist = rc.getLocation().distanceSquaredTo(c.loc);

        if (c.type == objectiveType && c.loc.equals(objectiveLoc)) {
            if (dist >= objectiveDist)
                objectiveStallTurns++;
            else
                objectiveStallTurns = Math.max(0, objectiveStallTurns - 1);
        } else {
            objectiveType = c.type;
            objectiveLoc = c.loc;
            objectiveStallTurns = 0;
        }
        objectiveDist = dist;
        objectiveLastRound = rc.getRoundNum();

        if (objectiveStallTurns >= STALL_ABANDON_TURNS) {
            return resetToExplore(rc);
        }
        return c;
    }

    static Candidate resetToExplore(RobotController rc) {
        chooseExploreSector(rc);
        objectiveType = OBJ_EXPLORE;
        MapLocation wp = sectorWaypoint(rc, currentSectorX, currentSectorY, currentWaypointIdx);
        objectiveLoc = wp;
        objectiveDist = Integer.MAX_VALUE;
        objectiveStallTurns = 0;
        objectiveLastRound = rc.getRoundNum();
        return new Candidate(OBJ_EXPLORE, wp, scoreExplore(rc));
    }
    // TOWER / GAME-STATE HELPERS

    static int getPhase(RobotController rc) {
        int r = rc.getRoundNum();
        if (r <= EARLY_ROUND_LIMIT)
            return PHASE_EARLY;
        if (r <= MID_ROUND_LIMIT)
            return PHASE_MID;
        return PHASE_LATE;
    }

    static MapLocation findNearestAllyTower(RobotController rc) throws GameActionException {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo a : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!a.getType().isTowerType())
                continue;
            int d = rc.getLocation().distanceSquaredTo(a.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = a.getLocation();
            }
        }
        return best;
    }

    static UnitType chooseGreedyTowerType(RobotController rc) throws GameActionException {
        int paintCount = 0, moneyCount = 0;
        for (RobotInfo a : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!a.getType().isTowerType())
                continue;
            UnitType base = a.getType().getBaseType();
            if (base == UnitType.LEVEL_ONE_PAINT_TOWER)
                paintCount++;
            else if (base == UnitType.LEVEL_ONE_MONEY_TOWER)
                moneyCount++;
        }
        int total = rc.getNumberTowers();
        if (paintCount == 0 && total >= 1)
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        if (getPhase(rc) == PHASE_EARLY) {
            if (total <= 2)
                return UnitType.LEVEL_ONE_PAINT_TOWER;
            return moneyCount <= paintCount ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
        }
        return paintCount <= moneyCount ? UnitType.LEVEL_ONE_PAINT_TOWER : UnitType.LEVEL_ONE_MONEY_TOWER;
    }

    static void guessSymmetry(RobotController rc) {
        int w = rc.getMapWidth(), h = rc.getMapHeight();
        if (w > h + 5)
            symmetryGuess = 2;
        else if (h > w + 5)
            symmetryGuess = 3;
        else
            symmetryGuess = 1;
    }

    static MapLocation guessEnemyTowerLocation(RobotController rc) {
        if (homeTower == null)
            return null;
        int w = rc.getMapWidth(), h = rc.getMapHeight();
        int ex, ey;
        if (symmetryGuess == 2) {
            ex = w - 1 - homeTower.x;
            ey = homeTower.y;
        } else if (symmetryGuess == 3) {
            ex = homeTower.x;
            ey = h - 1 - homeTower.y;
        } else {
            ex = w - 1 - homeTower.x;
            ey = h - 1 - homeTower.y;
        }
        return new MapLocation(ex, ey);
    }

    static boolean chooseSecondary(MapLocation loc) {
        return ((loc.x + loc.y) & 1) == 0;
    }
}
