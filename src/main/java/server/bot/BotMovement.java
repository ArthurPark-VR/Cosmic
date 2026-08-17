/*
    Moving a bot around a map, and following a player between maps.

    This is the one place where having no client really costs something. Every other movement in
    this server is a relay - a player's client works out the path and the server forwards the
    bytes - so a character with nobody connected has to have its movement invented here, and the
    invention has to be good enough that the receiving client draws something plausible.

    The approach is a step at a time rather than a full path: pick a point a short distance towards
    the target, snap it to whatever foothold is underneath so the character is standing on ground
    rather than hovering, and send one absolute-move fragment. The client tweens between the last
    position it saw and this one, so a sequence of these reads as walking. It does not pathfind:
    there is no navigation graph in a v83 map, and a bot that tries to climb a rope and fails looks
    far worse than one that walks the ground it can reach.

    Map changes are not walked at all. A bot following you through a portal is teleported, because
    the alternative is modelling portal geometry for every map in the game to solve a problem the
    player will never see happening.
*/
package server.bot;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.maps.Foothold;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

public final class BotMovement {
    private static final Logger log = LoggerFactory.getLogger(BotMovement.class);

    /** Pixels per step. Roughly a walking pace at the tick rate below. */
    private static final int STEP_PIXELS = 55;

    /** How often a following bot takes a step. */
    private static final long TICK_MS = 400;

    /** Close enough to stop. Without slack a follower jitters on the spot forever. */
    private static final int ARRIVED_WITHIN = 90;

    /** Beyond this a follower gives up walking and just warps, so it cannot fall behind forever. */
    private static final int TELEPORT_BEYOND = 900;

    private static final int STANCE_WALK_RIGHT = 4;
    private static final int STANCE_WALK_LEFT = 5;
    private static final int STANCE_STAND_RIGHT = 0;
    private static final int STANCE_STAND_LEFT = 1;

    /** Bots currently following somebody, by lowercased bot name. */
    private static final Map<String, ScheduledFuture<?>> following = new ConcurrentHashMap<>();

    private BotMovement() {
    }

    public static boolean isFollowing(String botName) {
        return botName != null && following.containsKey(botName.toLowerCase());
    }

    /**
     * Sends the bot one step towards a point in its current map. Returns true once it has arrived.
     */
    public static boolean stepTowards(Character bot, Point target) {
        MapleMap map = bot.getMap();
        if (map == null || target == null) {
            return true;
        }
        Point from = bot.getPosition();
        if (from == null) {
            return true;
        }

        int dx = target.x - from.x;
        if (Math.abs(dx) <= ARRIVED_WITHIN) {
            stand(bot, dx >= 0);
            return true;
        }

        int step = Math.min(STEP_PIXELS, Math.abs(dx)) * Integer.signum(dx);
        Point next = groundAt(map, new Point(from.x + step, from.y));
        if (next == null) {
            // No foothold that way - a ledge, or a gap. Stop rather than walk into the air.
            stand(bot, dx >= 0);
            return true;
        }

        int stance = dx >= 0 ? STANCE_WALK_RIGHT : STANCE_WALK_LEFT;
        move(bot, map, from, next, stance, (int) TICK_MS);
        return false;
    }

    /**
     * Puts the bot at a point immediately, with no walk. Used across maps and to recover when a
     * follower has been left too far behind to catch up on foot.
     */
    public static void warpTo(Character bot, MapleMap map, Point target) {
        if (map == null) {
            return;
        }
        MapleMap current = bot.getMap();
        Point landing = groundAt(map, target);
        if (landing == null) {
            landing = target;
        }

        if (current != null && current.getId() != map.getId()) {
            current.removePlayer(bot);
            bot.setMap(map);
            bot.setMapId(map.getId());
            bot.setPosition(landing);
            map.addPlayer(bot);
            return;
        }

        move(bot, map, bot.getPosition(), landing, STANCE_STAND_RIGHT, 0);
    }

    /**
     * Starts the bot following a player. Cancels any previous follow for that bot.
     */
    public static void follow(Character bot, Character leader) {
        final String key = bot.getName().toLowerCase();
        stopFollowing(bot.getName());

        final String botName = bot.getName();
        final String leaderName = leader.getName();
        ScheduledFuture<?> task = TimerManager.getInstance().register(() -> {
            try {
                Character live = BotWorld.get(botName);
                if (live == null) {
                    stopFollowing(botName);
                    return;
                }
                Character target = live.getClient().getWorldServer()
                        .getPlayerStorage().getCharacterByName(leaderName);
                if (target == null || target.getMap() == null) {
                    stopFollowing(botName);
                    return;
                }

                if (live.getMapId() != target.getMapId()) {
                    warpTo(live, target.getMap(), target.getPosition());
                    return;
                }
                Point here = live.getPosition();
                Point there = target.getPosition();
                if (here != null && there != null && here.distance(there) > TELEPORT_BEYOND) {
                    warpTo(live, target.getMap(), there);
                    return;
                }
                stepTowards(live, there);
            } catch (Exception e) {
                log.warn("Follow tick failed for '{}'", botName, e);
                stopFollowing(botName);
            }
        }, TICK_MS, TICK_MS);

        following.put(key, task);
    }

    public static void stopFollowing(String botName) {
        ScheduledFuture<?> previous = following.remove(botName.toLowerCase());
        if (previous != null) {
            previous.cancel(false);
        }
    }

    public static void stopAll() {
        for (String name : following.keySet()) {
            stopFollowing(name);
        }
    }

    private static void stand(Character bot, boolean facingRight) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        Point at = bot.getPosition();
        move(bot, map, at, at, facingRight ? STANCE_STAND_RIGHT : STANCE_STAND_LEFT, 0);
    }

    /**
     * Updates the server-side position and tells everybody watching. Both halves matter: the
     * position is what range checks and party code read, the packet is what the player sees.
     */
    private static void move(Character bot, MapleMap map, Point from, Point to, int stance, int duration) {
        if (from == null || to == null) {
            return;
        }
        bot.setPosition(to);
        bot.setStance(stance);
        map.movePlayer(bot, to);
        map.broadcastMessage(bot,
                PacketCreator.moveCharacterTo(bot.getId(), from, to, footholdAt(map, to), stance, duration),
                false);
    }

    /** Snaps a point down onto the ground below it, or null when there is nothing under it. */
    private static Point groundAt(MapleMap map, Point at) {
        try {
            return map.getGroundBelow(at);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int footholdAt(MapleMap map, Point at) {
        try {
            Foothold fh = map.getFootholds().findBelow(new Point(at.x, at.y - 14));
            return fh != null ? fh.getId() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
