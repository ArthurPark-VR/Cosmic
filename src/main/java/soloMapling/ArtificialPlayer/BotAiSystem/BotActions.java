/*
    Carrying out what a player told a bot to do.

    Everything here is deterministic server code. The language model never reaches this file; it can
    only produce a BotIntent, and so can the keyword table, and from here on the two are
    indistinguishable. That separation is the safety property: a model that hallucinates cannot
    invent an action, only pick a wrong one from a list of eight, and every one of those eight is
    something the player could have asked for in plain words anyway.

    Most of the work is already built. SoloMapling has a follow engine that crosses maps and
    survives a relog, a party system that knows how to seat a bot, and an attack driver that picks
    targets and swings. What was missing was a way for the player to ASK - the existing entry points
    are all behind OPQ scripting or a dice roll in a social routine. This is that way in.
*/
package soloMapling.ArtificialPlayer.BotAiSystem;

import client.Character;
import config.YamlConfig;
import net.server.world.Party;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotGuildSystem.BotGuildCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById;
import static soloMapling.DebugUtilities.debugprint;

public final class BotActions {

    private BotActions() {
    }

    /** What happened, so the caller can tell the player in the bot's own voice. */
    public enum Outcome {
        /** The bot did it. */
        DONE,
        /** The bot understood but could not - full party, no invite pending, cap reached. */
        REFUSED,
        /** Nothing to do; the bot was already in that state. */
        ALREADY
    }

    /**
     * Applies one intent to one bot.
     *
     * <p>Never throws: this runs off a chat packet and off an LLM completion, and neither is a
     * place a stray exception should surface.
     */
    public static Outcome apply(Character bot, Character player, BotIntent intent) {
        if (bot == null || player == null || intent == null) {
            return Outcome.REFUSED;
        }
        try {
            return switch (intent) {
                case FOLLOW -> follow(bot, player);
                case STAY -> stay(bot, player);
                case PARTY -> party(bot, player);
                case GUILD -> guild(bot, player);
                case FIGHT -> fight(bot, player);
                case HOLD -> hold(bot);
                case COME -> come(bot, player);
                case RELEASE, NONE -> Outcome.ALREADY;
            };
        } catch (RuntimeException e) {
            debugprint("BotActions." + intent + " failed: " + e);
            return Outcome.REFUSED;
        }
    }

    // ── Follow ───────────────────────────────────────────────────────────────

    /**
     * How many bots one player may have tailing them.
     *
     * <p>SoloMapling's own FOLLOWER_CAP is 30, sized for a populated server. Followers tick at a
     * pinned 750ms and are exempt from the load governor - that exemption is correct for
     * foreground content and expensive in bulk, so a solo server wants a much smaller number.
     */
    private static int companionLimit() {
        int configured = YamlConfig.config.server.BOT_COMPANION_LIMIT;
        return Math.max(1, Math.min(configured, BotRecruitManager.FOLLOWER_CAP));
    }

    private static Outcome follow(Character bot, Character player) {
        if (isFollowing(bot)) {
            BotOrders.setCommander(bot.getId(), player.getId());
            return Outcome.ALREADY;
        }
        if (followerCount(player) >= companionLimit()) {
            return Outcome.REFUSED;
        }
        BotOrders.setCommander(bot.getId(), player.getId());
        // How every other follow in the framework starts: park the leader id where the fresh
        // FollowerBot's INIT phase will find it, then re-type the bot in place.
        BotRecruitManager.setPendingLeader(bot.getId(), player.getId());
        return BotTypeManager.convertBotType(bot, BotTypeManager.BotType.FOLLOWER_BOT)
                ? Outcome.DONE : Outcome.REFUSED;
    }

    private static Outcome stay(Character bot, Character player) {
        if (!isFollowing(bot)) {
            return Outcome.ALREADY;
        }
        BotOrders.clear(bot.getId());
        BotRecruitManager.clearArmed(bot.getId());
        GCMovement.stop(bot);
        // Same landing FollowerBot picks for itself when a ride ends: grind where there are mobs,
        // loiter where there aren't.
        boolean grindable = MapMobIndex.level(bot.getMapId()) >= 0;
        BotTypeManager.convertBotType(bot, grindable
                ? BotTypeManager.BotType.TRAINING_BOT : BotTypeManager.BotType.SOCIAL_BOT);
        return Outcome.DONE;
    }

    // ── Party ────────────────────────────────────────────────────────────────

    /**
     * Joins the player's party.
     *
     * <p>Takes a pending invite if there is one, so "party?" right after hitting invite does what
     * it looks like it should. With no invite outstanding the bot seats itself directly into the
     * player's existing party - the invite handshake exists to ask a human's permission, and the
     * player asking is the permission.
     *
     * <p>It will not create a party FOR the player. Party creation sends the leader a packet chain
     * their client has to be expecting; conjuring one out of a chat line is the kind of desync that
     * ends in a relog.
     */
    private static Outcome party(Character bot, Character player) {
        if (bot.getParty() != null) {
            return bot.getParty() == player.getParty() ? Outcome.ALREADY : Outcome.REFUSED;
        }
        if (BotPartyQueue.getInstance().hasPendingInvite(bot)) {
            return BotPartyCommands.botAcceptPartyInvite(bot) ? Outcome.DONE : Outcome.REFUSED;
        }
        Party party = player.getParty();
        if (party == null) {
            return Outcome.REFUSED;  // "make one and I'll join"
        }
        if (party.getMembers().size() >= 6) {
            return Outcome.REFUSED;
        }
        BotOrders.setCommander(bot.getId(), player.getId());
        return Party.joinParty(bot, party.getId(), true) ? Outcome.DONE : Outcome.REFUSED;
    }

    // ── Guild ────────────────────────────────────────────────────────────────

    /**
     * Joins the player's guild, and in doing so becomes permanent.
     *
     * <p>Adoption is deliberately bound to this and only this. It is the one thing a player does
     * that means "I want this one around", it is rare, and it is the one thing that genuinely
     * cannot work without persistence - a guild roster that empties on restart is not a guild.
     */
    private static Outcome guild(Character bot, Character player) {
        if (bot.getGuildId() > 0) {
            return bot.getGuildId() == player.getGuildId() ? Outcome.ALREADY : Outcome.REFUSED;
        }
        if (player.getGuildId() <= 0) {
            return Outcome.REFUSED;
        }
        if (!CompanionRegistry.isCompanion(bot.getName()) && CompanionRegistry.isFull()) {
            return Outcome.REFUSED;  // roster full - dismiss someone first
        }
        boolean joined = BotGuildCommands.botAcceptGuildInvite(bot, player);
        if (!joined) {
            // No invite outstanding, or it had already expired. Seat them anyway: the player asked
            // in words, which is the same consent the invite packet carries.
            joined = BotGuildCommands.botJoinGuild(bot, player.getGuildId(), 5, 5);
        }
        if (!joined) {
            return Outcome.REFUSED;
        }
        CompanionRegistry.adopt(bot, player);
        BotOrders.setCommander(bot.getId(), player.getId());
        return Outcome.DONE;
    }

    // ── Combat ───────────────────────────────────────────────────────────────

    /**
     * Fight alongside me.
     *
     * <p>A bot that fights but does not follow is useless the moment the player moves, so this
     * implies the follow. The order itself is a standing flag rather than an FSM state, because the
     * conversion to a follower rebuilds the FSM and would throw a state away.
     */
    private static Outcome fight(Character bot, Character player) {
        boolean wasFighting = BotOrders.isFighting(bot.getId());
        Outcome followed = follow(bot, player);
        if (followed == Outcome.REFUSED) {
            return Outcome.REFUSED;
        }
        BotOrders.setFighting(bot.getId(), true);
        return wasFighting ? Outcome.ALREADY : Outcome.DONE;
    }

    private static Outcome hold(Character bot) {
        if (!BotOrders.isFighting(bot.getId())) {
            return Outcome.ALREADY;
        }
        BotOrders.setFighting(bot.getId(), false);
        return Outcome.DONE;
    }

    // ── Come here ────────────────────────────────────────────────────────────

    /**
     * Get to the player's map now.
     *
     * <p>This warps rather than travels, and that is the whole point of having it separate from
     * FOLLOW. Follow walks the portal graph, which is right for ordinary movement and reads as a
     * player making their way over. It cannot reach a boss interior: those are entered through an
     * NPC or a scripted door, so no portal chain exists to walk, and a bot asked to travel there
     * would wander until it gave up.
     *
     * <p>It is also what "come here" means. A player standing at Zakum's altar is not asking for a
     * bot to set off on a two-minute journey.
     */
    private static Outcome come(Character bot, Character player) {
        if (bot.getMapId() == player.getMapId()) {
            GCMovement.move(bot, player.getPosition().x, player.getPosition().y);
            return Outcome.ALREADY;
        }
        BotOrders.setCommander(bot.getId(), player.getId());
        boolean warped = GCMovement.warpTo(bot, player.getMapId(),
                player.getPosition().x, player.getPosition().y);
        return warped ? Outcome.DONE : Outcome.REFUSED;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isFollowing(Character bot) {
        return getBotById(bot.getId()) instanceof FollowerBot;
    }

    /**
     * How many bots are actually tailing this player.
     *
     * <p>Counts followers, not commanded bots. The cap exists because followers tick fast and
     * governor-exempt; a bot that was merely partied or summoned costs nothing and must not use up
     * a follow slot.
     */
    private static int followerCount(Character player) {
        int n = 0;
        for (int botId : BotOrders.commandedBy(player.getId())) {
            if (getBotById(botId) instanceof FollowerBot) {
                n++;
            }
        }
        return n;
    }
}
