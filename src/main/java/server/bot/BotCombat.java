/*
    Bots fighting.

    Damage here is computed and applied server-side rather than reported by a client, which is the
    one place bots have it easier than players. v83 is client-authoritative for combat: a real
    player's client works out its own damage and sends the number, and the server only checks it
    against a loose ceiling. A bot has no client to lie, so the number is simply calculated and
    handed to the same map.damageMonster every player attack ends at. Everything downstream -
    aggro, drops, experience, death, boss revives - therefore behaves exactly as it does for a
    player, because it is the same call.

    Two rules keep this from being obnoxious:

    Bots stop for reflect. Damage reflect is the one boss mechanic in this era that punishes
    mindless attacking, and a party member that keeps swinging into it is worse than no party
    member. It is checked every swing.

    Bots do not die. They are not the difficulty - they are who you brought with you, and a run
    that fails because an ally you cannot control stood in the wrong place is not an interesting
    failure. They heal on their own tick and are floored above zero.
*/
package server.bot;

import client.Character;
import client.status.MonsterStatus;
import config.YamlConfig;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class BotCombat {
    private static final Logger log = LoggerFactory.getLogger(BotCombat.class);

    /** Swing interval. Roughly a fast weapon; slow enough that a party is not a strobe light. */
    private static final long TICK_MS = 900;

    /** How far a bot will look for something to hit. */
    private static final int SEARCH_RANGE = 500;

    /** How close it needs to be to swing. Beyond this it walks in first. */
    private static final int MELEE_RANGE = 160;

    /** Ranged and magic classes attack from where they stand. */
    private static final int RANGED_RANGE = 450;

    /** Bots drift back to their leader if they have wandered this far chasing things. */
    private static final int LEASH = 1200;

    private static final Map<String, ScheduledFuture<?>> fighting = new ConcurrentHashMap<>();

    private BotCombat() {
    }

    public static boolean isFighting(String botName) {
        return botName != null && fighting.containsKey(botName.toLowerCase());
    }

    /**
     * Puts a bot into combat, fighting whatever is near the player it is with.
     */
    public static void engage(Character bot, Character leader) {
        if (!YamlConfig.config.server.BOT_COMBAT || bot == null || !bot.isBot()) {
            return;
        }
        final String botName = bot.getName();
        final String leaderName = leader.getName();
        disengage(botName);

        ScheduledFuture<?> task = TimerManager.getInstance().register(() -> {
            try {
                tick(botName, leaderName);
            } catch (Exception e) {
                log.warn("Combat tick failed for '{}'", botName, e);
                disengage(botName);
            }
        }, TICK_MS, TICK_MS);

        fighting.put(botName.toLowerCase(Locale.ROOT), task);
    }

    public static void disengage(String botName) {
        ScheduledFuture<?> previous = fighting.remove(botName.toLowerCase(Locale.ROOT));
        if (previous != null) {
            previous.cancel(false);
        }
    }

    public static void stopAll() {
        for (String name : fighting.keySet()) {
            disengage(name);
        }
    }

    private static void tick(String botName, String leaderName) {
        Character bot = BotWorld.get(botName);
        if (bot == null) {
            disengage(botName);
            return;
        }
        Character leader = bot.getClient().getWorldServer()
                .getPlayerStorage().getCharacterByName(leaderName);
        if (leader == null || leader.getMap() == null) {
            disengage(botName);
            return;
        }

        // Stay with the leader. A bot that chased something three screens away and kept fighting
        // there is not helping, it is missing.
        if (bot.getMapId() != leader.getMapId()) {
            BotMovement.warpTo(bot, leader.getMap(), leader.getPosition());
            return;
        }
        Point botAt = bot.getPosition();
        Point leaderAt = leader.getPosition();
        if (botAt != null && leaderAt != null && botAt.distance(leaderAt) > LEASH) {
            BotMovement.warpTo(bot, leader.getMap(), leaderAt);
            return;
        }

        keepAlive(bot);

        Monster target = nearestMonster(bot);
        if (target == null) {
            // Nothing to fight. Close the gap to the leader so the bot is in position when
            // something does spawn, rather than standing where the last fight ended.
            if (botAt != null && leaderAt != null && botAt.distance(leaderAt) > 140) {
                BotMovement.stepTowards(bot, leaderAt);
            }
            return;
        }

        if (isReflecting(target)) {
            // Deliberately silent and deliberately total: no chip damage, no "one more swing".
            return;
        }

        int reach = usesRangedAttack(bot) ? RANGED_RANGE : MELEE_RANGE;
        Point at = bot.getPosition();
        if (at != null && at.distance(target.getPosition()) > reach) {
            BotMovement.stepTowards(bot, target.getPosition());
            return;
        }

        swing(bot, target);
    }

    /**
     * One attack: animation to everyone watching, damage through the ordinary map path.
     */
    private static void swing(Character bot, Monster target) {
        MapleMap map = bot.getMap();
        if (map == null || !target.isAlive()) {
            return;
        }

        int damage = damageOf(bot);

        Map<Integer, AttackTarget> targets = new HashMap<>();
        targets.put(target.getObjectId(), new AttackTarget((short) 0, List.of(damage)));

        // High nibble is how many targets were hit, low nibble how many damage lines each.
        int numAttackedAndDamage = (1 << 4) | 1;
        int stance = bot.getStance() % 2 == 0 ? 0 : 1;

        map.broadcastMessage(bot, PacketCreator.closeRangeAttack(
                bot, 0, 0, stance, numAttackedAndDamage, targets, 4, 0, 0), false, true);

        // The same call every player attack ends at, so aggro, drops, experience and death all
        // behave identically - including boss revives and Horntail's part chaining.
        map.damageMonster(bot, target, damage);
    }

    /**
     * Damage for a bot of this level. Deliberately a curve on level alone rather than derived
     * from equipment: bots wear starter gear because gear is not what they are for, and a formula
     * that read their real weapon would have them hitting for eleven.
     */
    private static int damageOf(Character bot) {
        double scale = YamlConfig.config.server.BOT_ATTACK_MULTIPLIER;
        int level = Math.max(1, bot.getLevel());
        double base = (double) level * level / 3.0 * scale;

        // Spread, so the numbers on screen do not look like a metronome.
        double roll = 0.85 + ThreadLocalRandom.current().nextDouble() * 0.3;
        long damage = Math.round(base * roll);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, damage));
    }

    private static boolean isReflecting(Monster monster) {
        return monster.isBuffed(MonsterStatus.WEAPON_REFLECT)
                || monster.isBuffed(MonsterStatus.MAGIC_REFLECT);
    }

    private static boolean usesRangedAttack(Character bot) {
        int branch = bot.getJob().getId() / 100;
        return branch == 2 || branch == 12    // magician, blaze wizard
                || branch == 3 || branch == 13; // bowman, wind archer
    }

    /**
     * Tops the bot back up. Not a potion simulation - the point is that an ally you cannot control
     * never becomes the reason a run failed.
     */
    private static void keepAlive(Character bot) {
        if (bot.getHp() < bot.getCurrentMaxHp()) {
            bot.healHpMp();
        }
    }

    private static Monster nearestMonster(Character bot) {
        MapleMap map = bot.getMap();
        Point at = bot.getPosition();
        if (map == null || at == null) {
            return null;
        }
        Rectangle box = new Rectangle(
                at.x - SEARCH_RANGE, at.y - SEARCH_RANGE, SEARCH_RANGE * 2, SEARCH_RANGE * 2);

        Monster best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MapObject object : map.getMapObjectsInBox(box, List.of(MapObjectType.MONSTER))) {
            Monster monster = (Monster) object;
            if (!monster.isAlive() || monster.getPosition() == null) {
                continue;
            }
            double distance = at.distance(monster.getPosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = monster;
            }
        }
        return best;
    }
}
