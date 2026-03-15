package greedybot;

import battlecode.common.*;

public class Tower {
    static int builtCount = 0;

    static final UnitType[] EARLY_CYCLE = {
        UnitType.SOLDIER, UnitType.SOLDIER, UnitType.SOLDIER, UnitType.SOLDIER,
        UnitType.SPLASHER, UnitType.MOPPER
    };

    static final UnitType[] MID_CYCLE = {
        UnitType.SOLDIER, UnitType.SOLDIER, UnitType.SPLASHER, UnitType.SPLASHER, UnitType.MOPPER
    };

    static final UnitType[] LATE_CYCLE = {
        UnitType.SPLASHER, UnitType.SPLASHER, UnitType.SOLDIER, UnitType.SPLASHER
    };

    static void run(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, RobotPlayer.enemyTeam);
        if (enemies.length > 0) {
            RobotPlayer.towerLastEnemyRound = rc.getRoundNum();
        }

        RobotInfo target = chooseBestAttackTarget(rc, enemies);
        if (target != null && rc.canAttack(target.getLocation())) {
            rc.attack(target.getLocation());
        }

        UnitType spawnType = chooseSpawnType(rc);
        boolean spawned = trySpawn(rc, spawnType);
        if (spawned) {
            builtCount++;
            return;
        }

        tryUpgrade(rc);
    }

    static RobotInfo chooseBestAttackTarget(RobotController rc, RobotInfo[] enemies) {
        RobotInfo best = null;
        int bestHp = Integer.MAX_VALUE;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : enemies) {
            MapLocation loc = enemy.getLocation();
            if (!rc.canAttack(loc)) {
                continue;
            }
            int hp = enemy.getHealth();
            int d = rc.getLocation().distanceSquaredTo(loc);
            if (hp < bestHp || (hp == bestHp && d < bestDist)) {
                bestHp = hp;
                bestDist = d;
                best = enemy;
            }
        }
        return best;
    }

    static UnitType chooseSpawnType(RobotController rc) {
        int phase = RobotPlayer.determinePhase(rc);
        if (phase == RobotPlayer.PHASE_EARLY) {
            return EARLY_CYCLE[builtCount % EARLY_CYCLE.length];
        }
        if (phase == RobotPlayer.PHASE_MID) {
            return MID_CYCLE[builtCount % MID_CYCLE.length];
        }
        return LATE_CYCLE[builtCount % LATE_CYCLE.length];
    }

    static boolean trySpawn(RobotController rc, UnitType type) throws GameActionException {
        if (rc.getPaint() < type.paintCost) {
            return false;
        }
        int start = (rc.getRoundNum() + rc.getID()) & 7;
        for (int i = 0; i < 8; i++) {
            Direction dir = RobotPlayer.DIRECTIONS[(start + i) & 7];
            MapLocation loc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(type, loc)) {
                rc.buildRobot(type, loc);
                return true;
            }
        }
        return false;
    }

    static boolean tryUpgrade(RobotController rc) throws GameActionException {
        if (!rc.getType().canUpgradeType()) {
            return false;
        }
        UnitType next = rc.getType().getNextLevel();
        if (next == null) {
            return false;
        }
        if (rc.getRoundNum() - RobotPlayer.towerLastEnemyRound < 10) {
            return false;
        }
        if (rc.getChips() < next.moneyCost + 1500) {
            return false;
        }
        if (rc.canUpgradeTower(rc.getLocation())) {
            rc.upgradeTower(rc.getLocation());
            return true;
        }
        return false;
    }
}
