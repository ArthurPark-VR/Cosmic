/*
    Marrying a bot, without the wedding.

    Vanilla marriage is a long scripted errand: buy an engagement box from one NPC, propose with
    it, book a cathedral or chapel, gather guests, run a timed ceremony instance, receive rings.
    None of that is what was asked for, and half of it cannot work anyway - a wedding needs guests
    who can click things.

    So this keeps the parts that are real state and skips the parts that are logistics. What
    remains is the same end state the cathedral produces: a marriages row, a partnerId on both
    characters and a matched pair of rings. Anything that later reads marital status - spouse chat,
    the ring on the character window, the couple message - goes through those, so none of it knows
    the ceremony was skipped.

    The one thing deliberately NOT skipped is earning it. Proposing to a character you have barely
    spoken to is refused, because an attachment that can be had on the first line was never an
    attachment. The gate is the same familiarity count that governs how warmly they speak to you.
*/
package server.bot;

import client.Character;
import config.YamlConfig;
import constants.id.ItemId;
import net.server.Server;
import net.server.channel.handlers.RingActionHandler;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BotMarriage {
    private static final Logger log = LoggerFactory.getLogger(BotMarriage.class);

    /**
     * Exchanges needed before a bot will say yes. Deliberately the top familiarity stage: by the
     * time a character is speaking to you as CLOSE, the proposal reads as the next step rather
     * than as a stranger being asked to sign something.
     */
    public static final int EXCHANGES_REQUIRED = 25;

    /** Outcome of a proposal, so the caller can say something useful about a refusal. */
    public enum Result {
        MARRIED,
        DISABLED,
        NOT_A_BOT,
        ALREADY_MARRIED,
        BOT_ALREADY_MARRIED,
        NOT_CLOSE_ENOUGH,
        FAILED
    }

    private BotMarriage() {
    }

    public static boolean enabled() {
        return YamlConfig.config.server.BOT_CAN_MARRY;
    }

    /**
     * How many more exchanges are needed before this bot would accept. Zero when ready.
     */
    public static int exchangesRemaining(String botName, String playerName) {
        return Math.max(0, EXCHANGES_REQUIRED - BotDialogue.interactionCount(botName, playerName));
    }

    public static Result propose(Character player, String botName) {
        if (!enabled()) {
            return Result.DISABLED;
        }
        Character bot = BotWorld.get(botName);
        if (bot == null || !bot.isBot()) {
            return Result.NOT_A_BOT;
        }
        if (player.getPartnerId() > 0) {
            return Result.ALREADY_MARRIED;
        }
        if (bot.getPartnerId() > 0) {
            return Result.BOT_ALREADY_MARRIED;
        }
        if (exchangesRemaining(bot.getName(), player.getName()) > 0) {
            return Result.NOT_CLOSE_ENOUGH;
        }

        try {
            World world = Server.getInstance().getWorld(player.getWorld());
            if (world == null) {
                return Result.FAILED;
            }

            int relationshipId = world.createRelationship(player.getId(), bot.getId());
            if (relationshipId <= 0) {
                log.warn("Marriage to '{}' failed: no relationship row", bot.getName());
                return Result.FAILED;
            }

            player.setPartnerId(bot.getId());
            bot.setPartnerId(player.getId());

            // The same call the cathedral makes. It writes both rings, equips them and broadcasts
            // the marriage message; for the bot half every packet lands in the void, harmlessly.
            RingActionHandler.giveMarriageRings(player, bot, ItemId.WEDDING_RING_MOONSTONE);

            // Persist immediately rather than waiting for autosave. A marriage lost to a crash
            // ten minutes later would be a genuinely bad thing to have to explain.
            player.saveCharToDB(false);
            bot.saveCharToDB(false);

            log.info("{} married bot '{}'", player.getName(), bot.getName());
            return Result.MARRIED;
        } catch (Exception e) {
            log.warn("Marriage to '{}' failed", botName, e);
            return Result.FAILED;
        }
    }

    /**
     * Undoes a marriage to a bot. Present because the alternative is editing the database by hand,
     * and a system that can only be entered is a trap rather than a feature.
     */
    public static boolean divorce(Character player) {
        int partnerId = player.getPartnerId();
        if (partnerId <= 0 || !BotWorld.isBotCharacter(partnerId)) {
            return false;
        }
        try {
            World world = Server.getInstance().getWorld(player.getWorld());
            Character bot = null;
            for (Character candidate : BotWorld.all()) {
                if (candidate.getId() == partnerId) {
                    bot = candidate;
                    break;
                }
            }
            if (world != null) {
                world.deleteRelationship(player.getId(), partnerId);
            }
            player.setPartnerId(-1);
            player.setMarriageItemId(-1);
            player.saveCharToDB(false);
            if (bot != null) {
                bot.setPartnerId(-1);
                bot.setMarriageItemId(-1);
                bot.saveCharToDB(false);
            }
            return true;
        } catch (Exception e) {
            log.warn("Divorce failed for {}", player.getName(), e);
            return false;
        }
    }
}
