/*
    Bots answering the invitations a player sends them: party, buddy list, guild.

    Each of these follows the same shape. The existing handler does its half - validates, creates
    the invite, sends a packet the bot cannot receive - and then this runs the half a human would
    have done by clicking Accept, using the same calls the accept handler uses rather than
    reaching into the party or guild internals. That matters: joinParty and addGuildMember do
    bookkeeping (party packets to every member, guild rank, database writes) that is easy to
    half-reproduce and then spend an evening debugging.

    The delay is not decoration. An invitation accepted in the same tick it was sent reads as a
    vending machine; a couple of seconds reads as someone noticing a window and clicking it.
*/
package server.bot;

import client.BuddyList;
import client.BuddylistEntry;
import client.Character;
import config.YamlConfig;
import net.server.Server;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteResult;
import net.server.coordinator.world.InviteCoordinator.InviteResultType;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.guild.Guild;
import net.server.guild.GuildPackets;
import net.server.world.Party;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;

import java.util.concurrent.ThreadLocalRandom;

public final class BotSocial {
    private static final Logger log = LoggerFactory.getLogger(BotSocial.class);

    private static final int MIN_THINKING_MS = 1200;
    private static final int MAX_THINKING_MS = 3500;

    private BotSocial() {
    }

    public static boolean enabled() {
        return YamlConfig.config.server.BOT_ACCEPTS_INVITES;
    }

    /**
     * A bot was invited to a party. Accepts through the same path the join handler uses.
     */
    public static void onPartyInvite(Character bot, int partyId) {
        if (!enabled() || !bot.isBot()) {
            return;
        }
        final String botName = bot.getName();
        after(() -> {
            Character live = BotWorld.get(botName);
            if (live == null || live.getParty() != null) {
                return;
            }
            // answerInvite consumes the pending invite; without it joinParty would succeed but
            // leave a stale invitation behind that blocks the next one.
            InviteResult answer = InviteCoordinator.answerInvite(
                    InviteType.PARTY, live.getId(), partyId, true);
            if (answer.result != InviteResultType.ACCEPTED) {
                return;
            }
            Party.joinParty(live, partyId, false);
            log.info("Bot '{}' joined party {}", botName, partyId);
        });
    }

    /**
     * A bot received a buddy request. Accepts it and, importantly, tells the inviter's channel
     * that it is online - otherwise the bot sits greyed out in the buddy list forever, because
     * the add path leaves the channel at -1 until the other side answers.
     */
    public static void onBuddyRequest(Character bot, int fromCharacterId, String fromName, int fromChannel) {
        if (!enabled() || !bot.isBot()) {
            return;
        }
        final String botName = bot.getName();
        after(() -> {
            Character live = BotWorld.get(botName);
            if (live == null || live.getBuddylist() == null || live.getBuddylist().isFull()) {
                return;
            }
            live.getBuddylist().put(
                    new BuddylistEntry(fromName, "Default Group", fromCharacterId, fromChannel, true));

            World world = Server.getInstance().getWorld(live.getWorld());
            if (world != null) {
                world.buddyChanged(fromCharacterId, live.getId(), botName,
                        live.getClient().getChannel(), BuddyList.BuddyOperation.ADDED);
            }
            log.info("Bot '{}' accepted a buddy request from {}", botName, fromName);
        });
    }

    /**
     * A bot was invited to a guild. Mirrors the 0x06 join case of GuildOperationHandler.
     */
    public static void onGuildInvite(Character bot, int guildId) {
        if (!enabled() || !bot.isBot()) {
            return;
        }
        final String botName = bot.getName();
        after(() -> {
            Character live = BotWorld.get(botName);
            if (live == null || live.getGuildId() > 0) {
                return;
            }
            if (!Guild.answerInvitation(live.getId(), botName, guildId, true)) {
                return;
            }

            live.getMGC().setGuildId(guildId);
            live.getMGC().setGuildRank(5);
            live.getMGC().setAllianceRank(5);

            if (Server.getInstance().addGuildMember(live.getMGC(), live) == 0) {
                live.getMGC().setGuildId(0);
                log.info("Bot '{}' could not join guild {}, it is full", botName, guildId);
                return;
            }
            live.saveGuildStatus();

            if (live.getMap() != null && live.getGuild() != null) {
                live.getMap().broadcastPacket(live,
                        GuildPackets.guildNameChanged(live.getId(), live.getGuild().getName()));
                live.getMap().broadcastPacket(live,
                        GuildPackets.guildMarkChanged(live.getId(), live.getGuild()));
            }
            log.info("Bot '{}' joined guild {}", botName, guildId);
        });
    }

    private static void after(Runnable action) {
        long delay = ThreadLocalRandom.current().nextInt(MIN_THINKING_MS, MAX_THINKING_MS);
        TimerManager.getInstance().schedule(() -> {
            try {
                action.run();
            } catch (Exception e) {
                log.warn("Bot invitation handling failed", e);
            }
        }, delay);
    }
}
