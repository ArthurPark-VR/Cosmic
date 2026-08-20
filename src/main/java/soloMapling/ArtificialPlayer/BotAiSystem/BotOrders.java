/*
    Standing orders a player has given a bot, held outside the bot's own state machine.

    They live here rather than as fields on BotSM because re-typing a bot builds a whole new BotSM
    (see BotTypeManager.convertBotType), and the orders have to outlive that. "Follow me and attack"
    is two commands that convert the bot in between; if the combat order lived on the old FSM it
    would be thrown away by the conversion the follow command just triggered.

    Keyed by character id, cleared on despawn.
*/
package soloMapling.ArtificialPlayer.BotAiSystem;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BotOrders {

    /** Bots told to fight alongside their leader rather than just tail them. */
    private static final Set<Integer> fighting = ConcurrentHashMap.newKeySet();

    /** botId -> the player who commands it, so an order can be attributed and revoked. */
    private static final Map<Integer, Integer> commander = new ConcurrentHashMap<>();

    private BotOrders() {
    }

    public static void setFighting(int botId, boolean on) {
        if (on) {
            fighting.add(botId);
        } else {
            fighting.remove(botId);
        }
    }

    public static boolean isFighting(int botId) {
        return fighting.contains(botId);
    }

    public static void setCommander(int botId, int playerId) {
        commander.put(botId, playerId);
    }

    /** The player commanding this bot, or -1. */
    public static int commanderOf(int botId) {
        return commander.getOrDefault(botId, -1);
    }

    /** Every bot currently taking orders from this player. */
    public static Set<Integer> commandedBy(int playerId) {
        Set<Integer> out = ConcurrentHashMap.newKeySet();
        commander.forEach((botId, owner) -> {
            if (owner == playerId) {
                out.add(botId);
            }
        });
        return out;
    }

    /** Drop everything held for a bot. Called on despawn and when a companion is dismissed. */
    public static void clear(int botId) {
        fighting.remove(botId);
        commander.remove(botId);
    }
}
