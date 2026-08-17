/*
    Seeds a bot persona so it can be whispered, and reads back one that already exists.

    Administrative setup, not the interaction itself - once a persona exists you talk to it by
    whispering the name, exactly as you would a real player. Generates a coherent randomised
    identity rather than asking for one, because typing a paragraph into a chat box is miserable
    and the point is to get to a conversation quickly. Edit bot_persona directly for finer control.

    Naming an existing persona reads it back rather than rerolling it. The hand-written cast lives
    in the same table as rolled ones, and a single mistyped command would otherwise replace a
    written character with random filler and no warning. Overwriting needs -force.
*/
package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.bot.BotDialogue;
import server.bot.BotPersona;
import server.bot.Familiarity;
import server.bot.LlmClient;
import tools.Randomizer;

import java.util.Optional;

public class BotPersonaCommand extends Command {
    private static final String[] TEMPERAMENTS = {
            "blunt and a little impatient", "relentlessly cheerful", "dry and sarcastic",
            "anxious and over-apologetic", "earnest and eager to help", "weary, seen it all before",
            "boastful about your gear", "quiet, answers in as few words as possible",
    };
    private static final String[] HAUNTS = {
            "Henesys", "Ludibrium", "Kerning City", "Orbis", "Leafre", "Ellinia",
            "Perion", "Sleepywood", "El Nath", "Lith Harbor", "Nautilus", "Aquarium",
    };
    private static final String[] FOODS = {
            "fried chicken", "tteokbokki", "apple pie", "instant ramen", "shaved ice", "orange juice",
    };
    private static final String[] OPINIONS = {
            "you think Free Market merchants overcharge for everything",
            "you are convinced your drop luck is cursed",
            "you resent how crowded your training map has become",
            "you insist potions are a waste of mesos and you would rather just not get hit",
            "you are still bitter about a scroll that failed months ago",
            "you think jump quests were designed purely to torment people",
    };
    private static final String[][] GOALS = {
            {"LEVEL", "reaching your 4th job advancement", "120"},
            {"GEAR", "saving up for a better weapon", "0"},
            {"ITEM", "hunting a mastery book that will not drop", "0"},
            {"LEVEL", "grinding out the last few levels before a boss run", "0"},
    };

    {
        setDescription("Create or inspect a bot persona. Usage: @botpersona <name> [-force]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();

        if (params.length == 0) {
            player.yellowMessage("Syntax: @botpersona <name> [-force]   - then just whisper that name.");
            return;
        }

        String name = params[0];
        if (name.length() > 13) {
            player.yellowMessage("Names are at most 13 characters.");
            return;
        }

        boolean force = params.length > 1 && "-force".equalsIgnoreCase(params[1]);
        Optional<BotPersona> existing = BotDialogue.loadPersona(name);
        if (existing.isPresent() && !force) {
            describe(player, existing.get());
            return;
        }

        String temperament = pick(TEMPERAMENTS);
        String haunt = pick(HAUNTS);
        String food = pick(FOODS);
        String opinion = pick(OPINIONS);
        String[] goal = GOALS[Randomizer.nextInt(GOALS.length)];

        String backstory = "You mostly play around " + haunt + ", and " + opinion;
        // Stable facts, kept as JSON so more can be added later without a migration.
        String traits = "{\"favourite_food\":\"" + food + "\",\"hangout\":\"" + haunt + "\"}";
        String speechStyle = "casual game chat, lowercase is fine, no punctuation fuss";

        if (!BotDialogue.savePersona(name, temperament, speechStyle, backstory, traits)) {
            player.yellowMessage("Could not save persona - check the server log.");
            return;
        }
        BotDialogue.setGoal(name, goal[0], goal[1], Integer.parseInt(goal[2]));

        player.dropMessage(6, "Created bot '" + name + "' - " + temperament + ", hangs around " + haunt + ".");
        player.dropMessage(5, "Whisper them to talk. Their favourite food is " + food
                + ", so that is a fair thing to test consistency with.");

        if (!LlmClient.isEnabled()) {
            player.yellowMessage("Note: USE_BOT_LLM is false, so they will only give canned replies.");
        }
    }

    /**
     * Readout for a persona that already exists. Shows the two things that are not visible from
     * the character's own replies: how well it currently knows you, and - for the ones that grow -
     * what level it has reached. The bots are never told these numbers themselves.
     */
    private static void describe(Character player, BotPersona persona) {
        int met = BotDialogue.interactionCount(persona.name(), player.getName());
        Familiarity familiarity = Familiarity.of(met);

        player.dropMessage(6, persona.name() + " already exists - " + persona.temperament() + ".");

        Integer level = BotDialogue.levelOf(persona.name());
        if (level != null) {
            player.dropMessage(5, "Level " + level + " (grows on its own over time).");
        }

        int next = familiarity.nextThreshold();
        String standing = familiarity.name().toLowerCase() + ", " + met
                + (met == 1 ? " exchange" : " exchanges") + " with you";
        if (next > 0) {
            standing += " (" + (next - met) + " more to warm up)";
        }
        player.dropMessage(5, "Standing: " + standing + ".");

        if (persona.goalText() != null && !persona.goalText().isBlank()) {
            player.dropMessage(5, "Working towards: " + persona.goalText() + ".");
        }
        player.yellowMessage("Whisper them to talk. Use -force to overwrite with a random persona.");
    }

    private static String pick(String[] pool) {
        return pool[Randomizer.nextInt(pool.length)];
    }
}
