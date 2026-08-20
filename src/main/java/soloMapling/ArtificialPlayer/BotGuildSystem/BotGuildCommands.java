/*
    A bot accepting a guild invitation.

    The client-driven path (GuildOperationHandler case 0x06) is unreachable for a bot: it is
    triggered by a packet a bot will never send, and the reply it expects would go to the shared
    no-op BotClient anyway. This mirrors that handler's server-side half - answer the invite, seat
    the member, tell the map - and diverges from it in exactly two places, both deliberate:

      - saveGuildStatus() is skipped. It is an UPDATE against `characters` keyed on the character
        id, and an in-memory bot has no row there, so it would update nothing and report success.
        Persistence for bots lives in CompanionRegistry instead.

      - the GuildCharacter is rebuilt before seating. The one the bot carries was built inside
        loadCharFromDB, from the template row, BEFORE the clone was renamed and re-levelled - so
        seating it would put "fmbot" in the guild window at the template's level.
*/
package soloMapling.ArtificialPlayer.BotGuildSystem;

import client.Character;
import net.server.Server;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import net.server.guild.GuildPackets;

import static soloMapling.DebugUtilities.debugprint;

public final class BotGuildCommands {

    private BotGuildCommands() {
    }

    /**
     * Takes a pending guild invitation from {@code inviter} and seats the bot in their guild.
     *
     * <p>The guild is taken from the inviter rather than looked up from the invite record: the
     * invite the bot is answering is theirs by definition, and the coordinator's lookup is keyed on
     * exactly that pair anyway.
     *
     * @return true if the bot is now a member.
     */
    public static boolean botAcceptGuildInvite(Character bot, Character inviter) {
        if (bot == null || inviter == null) {
            return false;
        }
        int guildId = inviter.getGuildId();
        if (guildId <= 0) {
            debugprint("botAcceptGuildInvite: inviter has no guild");
            return false;
        }
        if (bot.getGuildId() > 0) {
            debugprint("botAcceptGuildInvite: " + bot.getName() + " is already in a guild");
            return false;
        }
        if (!Guild.answerInvitation(bot.getId(), bot.getName(), guildId, true)) {
            debugprint("botAcceptGuildInvite: no live invite for " + bot.getName());
            return false;
        }
        if (!seatInGuild(bot, guildId, 5, 5)) {
            debugprint("botAcceptGuildInvite: guild full or missing, guildId=" + guildId);
            return false;
        }
        announce(bot);
        return true;
    }

    /**
     * Seats a bot in a guild with no invitation at all - the restore path, and the GM command.
     * Everything the accept path does apart from consuming the invite.
     */
    public static boolean botJoinGuild(Character bot, int guildId, int rank, int allianceRank) {
        if (bot == null || guildId <= 0 || bot.getGuildId() > 0) {
            return false;
        }
        if (!seatInGuild(bot, guildId, rank, allianceRank)) {
            return false;
        }
        announce(bot);
        return true;
    }

    /** Removes a bot from its guild, in memory and in the companion record. */
    public static void botLeaveGuild(Character bot) {
        if (bot == null || bot.getGuildId() <= 0 || bot.getMGC() == null) {
            return;
        }
        Server.getInstance().leaveGuild(bot.getMGC());
        bot.getMGC().setGuildId(0);
        bot.getMGC().setGuildRank(5);
    }

    /**
     * Puts a live bot into a guild's in-memory roster.
     *
     * <p>The GuildCharacter is rebuilt from the bot as it is NOW. The one the Character carries was
     * built inside loadCharFromDB, from the template row, before the clone was renamed and
     * re-levelled - seating that would put "fmbot" in the guild window at the template's level.
     */
    public static boolean seatInGuild(Character bot, int guildId, int rank, int allianceRank) {
        if (bot == null || guildId <= 0) {
            return false;
        }
        // The world-aware lookup, not getGuild(id): at boot a guild is only in memory once one of
        // its members has logged in, and a companion restoring before its owner would otherwise
        // find nothing. This form loads the guild from the database on a miss.
        Guild guild = Server.getInstance().getGuild(guildId, bot.getWorld());
        if (guild == null) {
            return false;
        }
        bot.setMGC(new GuildCharacter(bot));
        bot.getMGC().setGuildId(guildId);
        bot.getMGC().setGuildRank(rank);
        bot.getMGC().setAllianceRank(allianceRank);
        return Server.getInstance().addGuildMember(bot.getMGC(), bot) != 0;
    }

    /** Repaint the bot's guild name and emblem for everyone standing near it. */
    private static void announce(Character bot) {
        Guild guild = bot.getGuild();
        if (guild == null || bot.getMap() == null) {
            return;
        }
        bot.getMap().broadcastPacket(bot, GuildPackets.guildNameChanged(bot.getId(), guild.getName()));
        bot.getMap().broadcastPacket(bot, GuildPackets.guildMarkChanged(bot.getId(), guild));
    }
}
