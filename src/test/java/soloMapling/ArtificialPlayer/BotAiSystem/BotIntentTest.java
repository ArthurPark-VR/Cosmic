package soloMapling.ArtificialPlayer.BotAiSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command layer is the one part of the companion work that is pure logic, and it is the part
 * whose bugs are hardest to see in game: a bot that misreads "hi there" as a command looks stupid
 * rather than broken, and nobody reports it.
 */
class BotIntentTest {

    // ── The commands themselves ──────────────────────────────────────────────

    @Test
    void readsPlainCommands() {
        assertEquals(BotIntent.FOLLOW, BotIntent.fromPlayerText("follow me"));
        assertEquals(BotIntent.FOLLOW, BotIntent.fromPlayerText("come with me"));
        assertEquals(BotIntent.FOLLOW, BotIntent.fromPlayerText("let's go"));
        assertEquals(BotIntent.STAY, BotIntent.fromPlayerText("stay here"));
        assertEquals(BotIntent.PARTY, BotIntent.fromPlayerText("party up"));
        assertEquals(BotIntent.GUILD, BotIntent.fromPlayerText("join my guild"));
        assertEquals(BotIntent.FIGHT, BotIntent.fromPlayerText("attack"));
        assertEquals(BotIntent.HOLD, BotIntent.fromPlayerText("stop attacking"));
        assertEquals(BotIntent.COME, BotIntent.fromPlayerText("come here"));
        assertEquals(BotIntent.RELEASE, BotIntent.fromPlayerText("later"));
    }

    @Test
    void isCaseInsensitiveAndIgnoresSurroundingChat() {
        assertEquals(BotIntent.FOLLOW, BotIntent.fromPlayerText("Lucian: FOLLOW ME please"));
        assertEquals(BotIntent.FIGHT, BotIntent.fromPlayerText("ok Anastasia attack it"));
    }

    // ── The bugs this table is prone to ──────────────────────────────────────

    @Test
    void matchesWholeWordsOnly() {
        // The class of bug that makes a bot feel broken: "stop" inside "stopwatch", "here" inside
        // "there". Both were real in an earlier keyword matcher.
        assertEquals(BotIntent.NONE, BotIntent.fromPlayerText("nice stopwatch"));
        assertEquals(BotIntent.NONE, BotIntent.fromPlayerText("hi there"));
        assertEquals(BotIntent.NONE, BotIntent.fromPlayerText("did you get killed"));
    }

    @Test
    void ordinaryChatCommandsNothing() {
        assertEquals(BotIntent.NONE, BotIntent.fromPlayerText("what level are you"));
        assertEquals(BotIntent.NONE, BotIntent.fromPlayerText("this map is rough"));
        assertEquals(BotIntent.NONE, BotIntent.fromPlayerText("how come you're here"));
        assertEquals(BotIntent.NONE, BotIntent.fromPlayerText(""));
        assertEquals(BotIntent.NONE, BotIntent.fromPlayerText(null));
    }

    @Test
    void specificPhrasesBeatBareKeywords() {
        // "stop following" must not be read as HOLD's bare "stop", or asking a companion to stop
        // tailing you would stop its combat instead and leave it following.
        assertEquals(BotIntent.STAY, BotIntent.fromPlayerText("stop following me"));
        assertEquals(BotIntent.HOLD, BotIntent.fromPlayerText("stop"));
        // FOLLOW claims the "come with me" phrasing before COME's bare "come here" family.
        assertEquals(BotIntent.FOLLOW, BotIntent.fromPlayerText("come along"));
    }

    @Test
    void aCommandBeatsAGoodbyeInTheSameBreath() {
        // RELEASE is checked last on purpose: a trailing "thanks" must not cancel the order that
        // preceded it in the same sentence.
        assertEquals(BotIntent.FIGHT, BotIntent.fromPlayerText("attack it, thanks"));
        assertEquals(BotIntent.FOLLOW, BotIntent.fromPlayerText("alright go on and follow me"));
    }

    // ── Model tags ───────────────────────────────────────────────────────────

    @Test
    void splitsATagOffAModelReply() {
        BotIntent.Tagged t = BotIntent.fromModelReply("sure, right behind you [FOLLOW]");
        assertEquals("sure, right behind you", t.spoken());
        assertEquals(BotIntent.FOLLOW, t.intent());
    }

    @Test
    void stripsTagsItDoesNotRecognise() {
        // A hallucinated tag must never reach map chat, and must never act.
        BotIntent.Tagged t = BotIntent.fromModelReply("no idea mate [SHRUG]");
        assertEquals("no idea mate", t.spoken());
        assertEquals(BotIntent.NONE, t.intent());
    }

    @Test
    void leavesUntaggedRepliesAlone() {
        BotIntent.Tagged t = BotIntent.fromModelReply("just grinding here for now");
        assertEquals("just grinding here for now", t.spoken());
        assertEquals(BotIntent.NONE, t.intent());
    }

    @Test
    void onlyStripsATrailingTag() {
        // Brackets mid-sentence are ordinary chat, not a command.
        BotIntent.Tagged t = BotIntent.fromModelReply("the [best] spot is up there");
        assertEquals("the [best] spot is up there", t.spoken());
        assertEquals(BotIntent.NONE, t.intent());
    }

    @Test
    void survivesAnEmptyOrNullReply() {
        assertEquals(BotIntent.NONE, BotIntent.fromModelReply(null).intent());
        assertTrue(BotIntent.fromModelReply(null).spoken().isEmpty());
        assertTrue(BotIntent.fromModelReply("  ").spoken().isEmpty());
    }

    @Test
    void tagMenuNamesEveryTagTheParserAccepts() {
        // Drift here is silent: the model would be offered a tag the parser drops on the floor.
        String menu = BotIntent.tagMenu();
        for (String tag : new String[]{"FOLLOW", "STAY", "PARTY", "GUILD", "FIGHT", "HOLD", "COME", "BYE"}) {
            assertTrue(menu.contains("[" + tag + "]"), "menu is missing [" + tag + "]");
            assertEquals(BotIntent.class, BotIntent.fromModelReply("ok [" + tag + "]").intent().getClass());
            assertTrue(BotIntent.fromModelReply("ok [" + tag + "]").intent() != BotIntent.NONE,
                    "parser does not accept [" + tag + "]");
        }
    }
}
