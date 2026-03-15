package bernbot;

import battlecode.common.*;

public class Tower {

    // Track last round enemies were seen near this tower
    static int lastEnemySeenRound = -9999;

    static void run(RobotController rc) throws GameActionException {

        // Sense enemies and remember
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            lastEnemySeenRound = rc.getRoundNum();
            RobotPlayer.towerLastEnemySeen = rc.getRoundNum();
        }

        // Attack weakest/most-killable enemy
        if (enemies.length > 0) {
            RobotInfo weakest = null;
            int bestScore = Integer.MIN_VALUE;
            for (RobotInfo e : enemies) {
                if (!rc.canAttack(e.getLocation()))
                    continue;
                int score = 2000 - e.getHealth() * 10;
                if (e.getType() == UnitType.SOLDIER)
                    score += 5;
                if (e.getType() == UnitType.SPLASHER)
                    score += 3;
                if (score > bestScore) {
                    bestScore = score;
                    weakest = e;
                }
            }
            if (weakest != null && rc.canAttack(weakest.getLocation())) {
                rc.attack(weakest.getLocation());
            }
        }

        // Spawn
        UnitType spawnType = chooseSpawnType(rc, enemies);
        boolean spawned = tryDirectedSpawn(rc, spawnType);

        // Upgrade (with recent-danger check)
        if (!spawned)
            tryUpgrade(rc);
    }

    static UnitType chooseSpawnType(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        int phase = RobotPlayer.getPhase(rc);
        boolean underRush = enemies.length >= 2;

        // Check for contaminated ruins
        boolean contaminatedRuin = false;
        for (MapLocation ruin : rc.senseNearbyRuins(-1)) {
            if (rc.canSenseRobotAtLocation(ruin) && rc.senseRobotAtLocation(ruin) != null)
                continue;
            for (MapInfo t : rc.senseNearbyMapInfos(ruin, 8)) {
                if (t.getPaint().isEnemy()) {
                    contaminatedRuin = true;
                    break;
                }
            }
            if (contaminatedRuin)
                break;
        }

        // Rush or contamination → mopper
        if (underRush || contaminatedRuin)
            return UnitType.MOPPER;

        // Count nearby unit types
        int soldiers = 0, splashers = 0, moppers = 0;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (ally.getType() == UnitType.SOLDIER)
                soldiers++;
            else if (ally.getType() == UnitType.SPLASHER)
                splashers++;
            else if (ally.getType() == UnitType.MOPPER)
                moppers++;
        }

        // Phase-based ratios
        int solTarget, splTarget, mopTarget;
        if (phase == RobotPlayer.PHASE_EARLY) {
            solTarget = 5;
            splTarget = 1;
            mopTarget = 1;
        } else if (phase == RobotPlayer.PHASE_MID) {
            solTarget = 2;
            splTarget = 3;
            mopTarget = 2;
        } else {
            solTarget = 1;
            splTarget = 4;
            mopTarget = 2;
        }

        // First unit always soldier
        if (soldiers + splashers + moppers == 0)
            return UnitType.SOLDIER;

        // Gap-based selection
        int total = soldiers + splashers + moppers;
        int totalT = solTarget + splTarget + mopTarget;
        int solGap = total * solTarget - soldiers * totalT;
        int splGap = total * splTarget - splashers * totalT;
        int mopGap = total * mopTarget - moppers * totalT;

        UnitType best = UnitType.SOLDIER;
        int bestGap = solGap;
        if (splGap > bestGap) {
            bestGap = splGap;
            best = UnitType.SPLASHER;
        }
        if (mopGap > bestGap) {
            bestGap = mopGap;
            best = UnitType.MOPPER;
        }
        return best;
    }

    static boolean tryDirectedSpawn(RobotController rc, UnitType type) throws GameActionException {
        // Determine bias target
        MapLocation bias;
        if (RobotPlayer.getPhase(rc) == RobotPlayer.PHASE_EARLY) {
            // Bias toward enemy side
            bias = RobotPlayer.guessEnemyTowerLocation(rc);
            if (bias == null)
                bias = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        } else {
            bias = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        }

        MapLocation bestLoc = null;
        int bestScore = Integer.MIN_VALUE;

        for (MapLocation loc : rc.getAllLocationsWithinRadiusSquared(rc.getLocation(), 4)) {
            if (!rc.canBuildRobot(type, loc))
                continue;

            int score = -loc.distanceSquaredTo(bias);
            if (rc.canSenseLocation(loc)) {
                PaintType p = rc.senseMapInfo(loc).getPaint();
                if (p.isAlly())
                    score += 5;
                if (p.isEnemy())
                    score -= 4;
            }
            score += RobotPlayer.rng.nextInt(3);

            if (score > bestScore) {
                bestScore = score;
                bestLoc = loc;
            }
        }

        if (bestLoc != null) {
            rc.buildRobot(type, bestLoc);
            return true;
        }
        return false;
    }

    static void tryUpgrade(RobotController rc) throws GameActionException {
        if (!rc.getType().canUpgradeType())
            return;
        UnitType next = rc.getType().getNextLevel();
        if (next == null)
            return;

        // Don't upgrade if enemies were seen recently (within 12 rounds)
        if (rc.getRoundNum() - lastEnemySeenRound < 12)
            return;
        // Need substantial money buffer
        if (rc.getMoney() < next.moneyCost + 1500)
            return;
        // Only upgrade if tower is being used (nearby allies)
        if (rc.senseNearbyRobots(-1, rc.getTeam()).length < 2)
            return;

        if (rc.canUpgradeTower(rc.getLocation())) {
            rc.upgradeTower(rc.getLocation());
        }
    }
}
