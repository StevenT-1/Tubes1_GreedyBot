package mainbot;

import battlecode.common.*;

public class Nav {
    static MapLocation target;
    static boolean followLeft = true;
    static Direction followDir = null;
    static int stuckTurns = 0;
    static int bestDist = Integer.MAX_VALUE;
    static MapLocation recent1 = null;
    static MapLocation recent2 = null;

    static void moveTo(RobotController rc, MapLocation newTarget) throws GameActionException {
        if (!rc.isMovementReady() || newTarget == null) {
            return;
        }

        if (target == null || !target.equals(newTarget)) {
            reset(newTarget);
        }

        MapLocation me = rc.getLocation();
        int dist = me.distanceSquaredTo(newTarget);
        if (dist < bestDist) {
            bestDist = dist;
            stuckTurns = 0;
        } else {
            stuckTurns++;
        }

        Direction direct = me.directionTo(newTarget);
        if (direct != Direction.CENTER && rc.canMove(direct)) {
            step(rc, direct);
            followDir = direct;
            return;
        }

        Direction wall = pickWallFollowMove(rc, direct, followLeft);
        if (wall != null) {
            step(rc, wall);
            followDir = wall;
            return;
        }

        Direction opposite = pickWallFollowMove(rc, direct, !followLeft);
        if (opposite != null) {
            followLeft = !followLeft;
            step(rc, opposite);
            followDir = opposite;
            return;
        }

        Direction fallback = pickAnyLegalMove(rc);
        if (fallback != null) {
            step(rc, fallback);
            followDir = fallback;
        }

        if (stuckTurns >= 3) {
            followLeft = !followLeft;
            followDir = null;
            stuckTurns = 0;
        }
    }

    static void reset(MapLocation newTarget) {
        target = newTarget;
        followDir = null;
        stuckTurns = 0;
        bestDist = Integer.MAX_VALUE;
        recent1 = null;
        recent2 = null;
    }

    static Direction pickWallFollowMove(RobotController rc, Direction toward, boolean left) {
        Direction base = followDir != null ? followDir : toward;
        if (base == null || base == Direction.CENTER) {
            base = Direction.NORTH;
        }

        Direction candidate = base;
        for (int i = 0; i < 8; i++) {
            candidate = left ? candidate.rotateLeft() : candidate.rotateRight();
            if (!rc.canMove(candidate)) continue;
            return candidate;
        }
        return null;
    }

    static Direction pickAnyLegalMove(RobotController rc) {
        for (Direction d : RobotPlayer.DIRECTIONS) {
            if (rc.canMove(d)) {
                return d;
            }
        }
        return null;
    }

    static boolean isRecent(MapLocation loc) {
        return (recent1 != null && recent1.equals(loc))
            || (recent2 != null && recent2.equals(loc));
    }

    static void step(RobotController rc, Direction d) throws GameActionException {
        MapLocation before = rc.getLocation();
        rc.move(d);
        recent2 = recent1;
        recent1 = before;
    }
}
