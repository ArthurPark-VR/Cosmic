/*
    Proposes to one of the bot cast, and undoes it.

    A command rather than a conversation, deliberately. Everything else about these characters is
    meant to be talked to rather than typed at, but marriage is a single irreversible state change,
    and inferring "was that a proposal?" from free text would either miss real ones or trigger on a
    joke. The talking is still what earns it - the proposal is refused until you have actually had
    the conversations.
*/
package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.bot.BotMarriage;
import server.bot.BotWorld;

public class ProposeCommand extends Command {
    {
        setDescription("Propose to someone. Usage: @propose <name>   (@propose -divorce to undo)");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();

        if (params.length == 0) {
            player.yellowMessage("Syntax: @propose <name>   -   or @propose -divorce");
            return;
        }

        if ("-divorce".equalsIgnoreCase(params[0])) {
            if (BotMarriage.divorce(player)) {
                player.dropMessage(6, "It is over. You are no longer married.");
            } else {
                player.yellowMessage("You are not married to anyone who can be divorced this way.");
            }
            return;
        }

        String name = params[0];
        switch (BotMarriage.propose(player, name)) {
            case MARRIED -> {
                player.dropMessage(6, name + " said yes.");
                player.dropMessage(5, "You are married. Spouse chat is open, and they are wearing the ring.");
            }
            case DISABLED -> player.yellowMessage("Marriage to the cast is turned off (BOT_CAN_MARRY).");
            case NOT_A_BOT -> player.yellowMessage("There is nobody by that name to propose to.");
            case ALREADY_MARRIED -> player.yellowMessage("You are already married. @propose -divorce first.");
            case BOT_ALREADY_MARRIED -> player.yellowMessage(name + " is already married to somebody else.");
            case NOT_CLOSE_ENOUGH -> {
                int remaining = BotMarriage.exchangesRemaining(resolveName(name), player.getName());
                player.yellowMessage(name + " does not know you well enough for that.");
                player.dropMessage(5, "About " + remaining + " more conversations before they would say yes.");
            }
            case FAILED -> player.yellowMessage("That did not work - check the server log.");
        }
    }

    /** Uses the cast's own spelling of the name, so the count is read against the right row. */
    private static String resolveName(String typed) {
        Character bot = BotWorld.get(typed);
        return bot != null ? bot.getName() : typed;
    }
}
