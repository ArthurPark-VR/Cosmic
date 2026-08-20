/*
    What the player asked a bot to DO, as opposed to what they said to it.

    There are two ways an intent gets here and they are deliberately unequal.

    The first is this file's keyword table, matched against the player's own words. It is the
    primary path: it costs nothing, it is instant, and it works with the language model switched
    off, unreachable or saturated. Everything the player can command a bot to do, they can command
    with the model down. That is the whole point of keeping actions out of the model.

    The second is a bracket tag the model may append to its reply. It exists only to catch the
    phrasings the table misses - "mind lending a hand with this thing" is a request to fight and no
    keyword table will ever know that. An 8B model cannot reliably emit a JSON tool call, but it can
    reliably append one token from a short list, so that is all we ask of it. Anything malformed,
    unknown or absent is simply dropped: a missing tag costs a command the player can repeat, a
    misparsed one would make a bot do something nobody asked for.

    Matching is word-boundary, never substring. "Stop" inside "stopwatch", or the "here" inside
    "hi there", is exactly the class of bug that makes a bot feel broken rather than dim.
*/
package soloMapling.ArtificialPlayer.BotAiSystem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum BotIntent {

    /** Come with me and keep coming - across maps, through portals. */
    FOLLOW,
    /** Stop following; hold this spot. */
    STAY,
    /** Take the party invite I just sent, or ask me for one. */
    PARTY,
    /** Take the guild invite I just sent. */
    GUILD,
    /** Fight what's in front of you. */
    FIGHT,
    /** Stop fighting. */
    HOLD,
    /** Get to where I am, however far that is - the boss-door case. */
    COME,
    /** We're done talking; go back to being scenery. */
    RELEASE,
    /** Nothing actionable was said. */
    NONE;

    /**
     * Keyword table, most specific phrase first within each intent.
     *
     * <p>Ordering across intents matters: the first intent with a hit wins, and RELEASE is
     * deliberately last so "alright, go on and kill it" reads as FIGHT rather than a goodbye.
     */
    private static final Map<BotIntent, List<String>> PHRASES = new LinkedHashMap<>();

    static {
        // Multi-word phrases are matched as phrases, so "stop following" can win over the bare
        // "stop" that HOLD claims - hence STAY before HOLD.
        PHRASES.put(STAY, List.of(
                "stop following", "quit following", "stay here", "stay put", "wait here",
                "hold on", "stay"));
        PHRASES.put(FOLLOW, List.of(
                "follow me", "come with me", "come along", "lets go", "let's go", "follow"));
        // No bare "come" - "how come" and "come on" are ordinary chat, and the phrases below
        // already cover every way a player actually calls a companion across a map.
        PHRASES.put(COME, List.of(
                "come here", "come to me", "get over here", "get in here", "warp to me",
                "teleport to me", "over here"));
        PHRASES.put(PARTY, List.of(
                "join my party", "party up", "join the party", "accept my invite",
                "accept the invite", "party with me", "party"));
        PHRASES.put(GUILD, List.of(
                "join my guild", "join the guild", "guild invite", "guild"));
        PHRASES.put(FIGHT, List.of(
                "help me fight", "help me kill", "get them", "kill them", "kill it",
                "attack", "fight", "kill", "dps", "hit it"));
        PHRASES.put(HOLD, List.of(
                "stop attacking", "stop fighting", "stand down", "hold fire", "stop", "chill"));
        PHRASES.put(RELEASE, List.of(
                "go on", "carry on", "as you were", "dismissed", "nevermind", "never mind",
                "later", "goodbye", "bye", "see ya", "cya", "thanks", "thank you"));
    }

    /** Tag vocabulary offered to the model, and the intent each maps to. */
    private static final Map<String, BotIntent> TAGS = Map.of(
            "FOLLOW", FOLLOW,
            "STAY", STAY,
            "PARTY", PARTY,
            "GUILD", GUILD,
            "FIGHT", FIGHT,
            "HOLD", HOLD,
            "COME", COME,
            "BYE", RELEASE);

    /** A trailing bracket tag, e.g. "sure thing [FOLLOW]". Anchored at the end so mid-sentence
     *  brackets in ordinary chat are left alone. */
    private static final Pattern TAG = Pattern.compile("\\s*\\[([A-Za-z]{2,8})]\\s*$");

    /** Cache of word-boundary patterns, one per phrase - compiled once, matched thousands of times. */
    private static final Map<String, Pattern> COMPILED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The player's own words. Returns {@link #NONE} when nothing actionable was said, which is the
     * overwhelmingly common case - most chat is just chat.
     */
    public static BotIntent fromPlayerText(String said) {
        if (said == null || said.isBlank()) {
            return NONE;
        }
        String text = said.toLowerCase(Locale.ROOT);
        for (Map.Entry<BotIntent, List<String>> entry : PHRASES.entrySet()) {
            for (String phrase : entry.getValue()) {
                if (containsWord(text, phrase)) {
                    return entry.getKey();
                }
            }
        }
        return NONE;
    }

    /**
     * Splits a model reply into the line the bot says and the action it asked for.
     *
     * <p>The tag is always stripped whether or not it is one we know, so a hallucinated
     * "[SHRUG]" never reaches map chat.
     */
    public static Tagged fromModelReply(String reply) {
        if (reply == null) {
            return new Tagged("", NONE);
        }
        Matcher m = TAG.matcher(reply);
        if (!m.find()) {
            return new Tagged(reply.trim(), NONE);
        }
        String word = m.group(1).toUpperCase(Locale.ROOT);
        String spoken = reply.substring(0, m.start()).trim();
        return new Tagged(spoken, TAGS.getOrDefault(word, NONE));
    }

    /** A model reply split into what is spoken and what is meant. */
    public record Tagged(String spoken, BotIntent intent) {
    }

    /** The tag menu, injected into the system prompt so the model knows what it may ask for. */
    static String tagMenu() {
        return " If - and only if - they are asking you to do something, end your message with one"
                + " of these exact tags: [FOLLOW] to go with them, [STAY] to stop going with them,"
                + " [PARTY] to join their party, [GUILD] to join their guild, [FIGHT] to fight"
                + " alongside them, [HOLD] to stop fighting, [COME] to travel to where they are,"
                + " [BYE] to end the conversation. Use at most one tag, and use none at all if they"
                + " were only talking.";
    }

    /**
     * Word-boundary containment. A phrase matches only as whole words, so "stop" does not fire on
     * "stopwatch" and "here" does not fire on "there".
     */
    private static boolean containsWord(String haystack, String phrase) {
        return COMPILED.computeIfAbsent(phrase,
                p -> Pattern.compile("\\b" + Pattern.quote(p) + "\\b")).matcher(haystack).find();
    }
}
