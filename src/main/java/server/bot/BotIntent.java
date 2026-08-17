/*
    Spotting the few things a player says that are instructions rather than conversation.

    Deliberately keyword matching and not the language model. The model is what makes these
    characters worth talking to, but it is the wrong tool for this: it is seconds slow, it is
    occasionally creative, and "follow me" has to work every single time or the player stops
    trusting it and goes looking for a command. Keywords are instant and boring, which is exactly
    what an instruction should be.

    Anything not matched here falls through to ordinary conversation, so the cost of missing a
    phrasing is a reply instead of an action - never a wrong action.
*/
package server.bot;

import java.util.Locale;

public enum BotIntent {
    FOLLOW,
    FIGHT,
    STOP,
    NONE;

    private static final String[] FIGHT_PHRASES = {
            "help me fight", "help me kill", "fight with me", "attack", "kill it", "kill them",
            "get it", "fight them", "help me", "cover me", "let's fight", "lets fight",
            "train with me", "grind with me",
    };

    private static final String[] FOLLOW_PHRASES = {
            "follow me", "come with me", "come along", "let's go", "lets go", "come on",
            "stick with me", "follow along", "walk with me", "this way",
    };

    private static final String[] STOP_PHRASES = {
            "stop following", "stop follow", "wait here", "stay here", "stay there",
            "hold on", "stop there", "wait there", "stop moving", "don't follow", "dont follow",
            "stop attacking", "stop fighting", "don't attack", "dont attack", "hold back",
            "stand down", "leave it", "stop it",
    };

    /**
     * Single words that are an instruction on their own. Matched on word boundaries rather than
     * as substrings, so "stop" is heard but "I stopped by the shop" is not.
     */
    private static final String[] STOP_WORDS = {"stop", "wait", "halt", "enough", "stay"};
    private static final String[] FIGHT_WORDS = {"fight", "attack", "kill"};

    public static BotIntent of(String message) {
        if (message == null || message.isBlank()) {
            return NONE;
        }
        String lower = message.toLowerCase(Locale.ROOT);

        // Stop is checked first: "stop following me" contains "follow me", and reading it as a
        // follow instruction would do the exact opposite of what was asked.
        for (String phrase : STOP_PHRASES) {
            if (lower.contains(phrase)) {
                return STOP;
            }
        }
        if (containsWord(lower, STOP_WORDS)) {
            return STOP;
        }
        // Fight before follow: "help me fight" contains neither follow phrase, but "let's go"
        // said mid-fight should not silently downgrade an attack order to a walk.
        for (String phrase : FIGHT_PHRASES) {
            if (lower.contains(phrase)) {
                return FIGHT;
            }
        }
        if (containsWord(lower, FIGHT_WORDS)) {
            return FIGHT;
        }
        for (String phrase : FOLLOW_PHRASES) {
            if (lower.contains(phrase)) {
                return FOLLOW;
            }
        }
        return NONE;
    }

    private static boolean containsWord(String lower, String[] words) {
        for (String word : words) {
            int at = lower.indexOf(word);
            while (at >= 0) {
                boolean startsClean = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1));
                int end = at + word.length();
                boolean endsClean = end == lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
                if (startsClean && endsClean) {
                    return true;
                }
                at = lower.indexOf(word, at + 1);
            }
        }
        return false;
    }
}
