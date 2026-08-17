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
    STOP,
    NONE;

    private static final String[] FOLLOW_PHRASES = {
            "follow me", "come with me", "come along", "let's go", "lets go", "come on",
            "stick with me", "follow along", "walk with me", "this way",
    };

    private static final String[] STOP_PHRASES = {
            "stop following", "stop follow", "wait here", "stay here", "stay there",
            "hold on", "stop there", "wait there", "stop moving", "don't follow", "dont follow",
    };

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
        for (String phrase : FOLLOW_PHRASES) {
            if (lower.contains(phrase)) {
                return FOLLOW;
            }
        }
        return NONE;
    }
}
