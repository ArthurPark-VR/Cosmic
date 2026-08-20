/*
    While a player is talking to a bot, the AI drives that bot instead of its script.

    SoloMapling's bots are always doing something - trading lines with each other, running a dice
    game, wandering to the next spot on a patrol. That is what makes the world feel inhabited, and
    it is exactly wrong the moment a player walks up and says something, because the bot carries on
    with the routine and answers out of a YAML file. Joining the conversation should interrupt it.

    So engaging a bot suspends its state machine. It is one flag, read by
    BotSM.checkIfNotRunningOrPaused, which nineteen of the twenty bot types already call at the top
    of their tick - so "supersede the script" is not twenty edits, it is one. The bot stops
    wandering, stops its scripted lines, and answers as itself until the conversation ends.

    Three things end it, and all three matter:

      - a word. "later", "go on", "thanks" - the player says they are done and the bot is released
        that instant.
      - silence. Three minutes with nothing said and the bot goes back to what it was doing, because
        a player who walked away should not leave a bot frozen mid-town forever.
      - distance. The player leaves the map and the conversation is over by definition.

    Only one bot is engaged per player at a time. Naming a different one moves the conversation
    rather than starting a second, because three thousand bots all answering at once is a worse
    failure than a bot that has to be addressed by name.
*/
package soloMapling.ArtificialPlayer.BotAiSystem;

import client.Character;
import net.server.Server;
import server.maps.MapleMap;
import soloMapling.server.SoloMaplingConstants;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.checkIfInvisibleBot;
import static soloMapling.server.ExecutorServiceManager.getScheduledExecutorService;

public final class BotTakeover {

    /** Silence that ends a conversation. Long enough to think, short enough not to strand a bot. */
    private static final long SILENCE_RELEASE_MS = TimeUnit.MINUTES.toMillis(3);

    /** How often the timeout/left-the-map sweep runs. Nothing here is latency-sensitive. */
    private static final long SWEEP_MS = TimeUnit.SECONDS.toMillis(15);

    /**
     * Said when the model is busy mid-conversation. Not scripted dialogue standing in for an
     * answer - just an acknowledgement, because a bot that goes silent halfway through a
     * conversation reads as broken in a way "one sec" does not.
     */
    private static final String[] STALLING = {"one sec", "hang on", "gimme a sec"};

    /** playerId -> the bot they are talking to. */
    private static final Map<Integer, Session> byPlayer = new ConcurrentHashMap<>();

    /** botId -> the same session, so the suspend check is a single map hit off the tick wheel. */
    private static final Map<Integer, Session> byBot = new ConcurrentHashMap<>();

    private static final AtomicBoolean sweeping = new AtomicBoolean(false);

    private static final class Session {
        final int botId;
        final int playerId;
        volatile long lastWordMs;

        Session(int botId, int playerId) {
            this.botId = botId;
            this.playerId = playerId;
            this.lastWordMs = System.currentTimeMillis();
        }
    }

    private BotTakeover() {
    }

    // ── The suspend flag ─────────────────────────────────────────────────────

    /**
     * Is a player currently talking through this bot? Called from every bot's tick, so it is a
     * single concurrent-map read and nothing else.
     */
    public static boolean isEngaged(int botId) {
        return byBot.containsKey(botId);
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    /**
     * Offers a line of map chat to the takeover layer.
     *
     * @return true if the AI owns this line and the scripted dialogue pipeline should not also see
     *         it. False means this was not ours - the caller should hand it to the message queue
     *         exactly as before.
     */
    public static boolean onPlayerChat(Character player, String said) {
        if (player == null || said == null || said.isBlank() || !BotAiChat.isEnabled()) {
            return false;
        }
        if (BotHelpers.isBot(player)) {
            return false;   // bots talking among themselves is the script's business, not ours
        }
        startSweeper();

        Session session = byPlayer.get(player.getId());
        Character bot = session == null ? null : live(session.botId);
        if (session != null && bot == null) {
            release(session, null);   // the bot despawned mid-conversation
            session = null;
        }

        // Naming someone else moves the conversation. Checked before anything else so a player can
        // always get a specific bot's attention, even mid-conversation with another.
        Character named = namedBotOnMap(player, said);
        if (named != null && (bot == null || named.getId() != bot.getId())) {
            if (!BotAiChat.canStart(named)) {
                return false;   // model down or saturated - let the scripted dialogue answer
            }
            if (session != null) {
                release(session, bot);
            }
            session = engage(player, named);
            bot = named;
        }

        if (session == null || bot == null) {
            return false;   // nothing engaged and nothing named: not ours
        }
        if (bot.getMapId() != player.getMapId()) {
            release(session, bot);
            return false;
        }
        session.lastWordMs = System.currentTimeMillis();

        // The player's own words decide the action. This runs before the model answers and
        // regardless of whether it answers at all, so a command lands immediately and keeps working
        // with the model switched off.
        BotIntent intent = BotIntent.fromPlayerText(said);
        if (intent == BotIntent.RELEASE) {
            release(session, bot);
            return true;
        }
        if (intent != BotIntent.NONE) {
            BotActions.apply(bot, player, intent);
        }

        final Session current = session;
        boolean started = BotAiChat.tryReply(bot, player, said, true,
                (b, p, reply) -> onModelReply(current, b, p, reply));
        if (started) {
            return true;
        }
        if (intent != BotIntent.NONE) {
            // The command landed; only the words are missing. Acknowledge it rather than falling
            // through to a scripted line that would answer something else entirely.
            SocialCommands.BotFullChat(bot, "ok");
            return true;
        }
        // Mid-conversation and the model is busy. Stall rather than go quiet; the scripted pipeline
        // cannot help here anyway, since a follow-up line does not carry the bot's name.
        SocialCommands.BotFullChat(bot, STALLING[Math.floorMod((int) System.nanoTime(), STALLING.length)]);
        return true;
    }

    /**
     * A finished generation: split any action tag off the reply, speak the rest, then act.
     *
     * <p>Speak first. The tag is the model's reading of a request the keyword table already had its
     * chance at, so it is the weaker signal of the two - and an action that fires before its line
     * looks like the bot moved without answering.
     */
    private static void onModelReply(Session session, Character bot, Character player, String reply) {
        BotIntent.Tagged tagged = BotIntent.fromModelReply(reply);
        if (!tagged.spoken().isEmpty()) {
            SocialCommands.BotFullChat(bot, tagged.spoken());
        }
        if (tagged.intent() == BotIntent.RELEASE) {
            release(session, bot);
            return;
        }
        if (tagged.intent() != BotIntent.NONE) {
            BotActions.apply(bot, player, tagged.intent());
        }
    }

    // ── Session lifecycle ────────────────────────────────────────────────────

    private static Session engage(Character player, Character bot) {
        Session s = new Session(bot.getId(), player.getId());
        byPlayer.put(player.getId(), s);
        byBot.put(bot.getId(), s);
        return s;
    }

    /**
     * Ends a conversation. The bot's own state machine resumes on its next tick with nothing to
     * undo - the suspension was only ever this map entry.
     */
    private static void release(Session session, Character bot) {
        byPlayer.remove(session.playerId, session);
        byBot.remove(session.botId, session);
        if (bot != null && bot.getMap() != null) {
            // Wake it now rather than letting it sit out the rest of a 9-12s unobserved tick.
            var sm = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage
                    .getBotById(session.botId);
            if (sm != null) {
                sm.nudgeSoon(0L);
            }
        }
    }

    /** Ends whatever conversation this bot is in. Called when a bot despawns. */
    public static void forgetBot(int botId) {
        Session s = byBot.remove(botId);
        if (s != null) {
            byPlayer.remove(s.playerId, s);
        }
    }

    /** Ends whatever conversation this player is in. Called on logout. */
    public static void forgetPlayer(int playerId) {
        Session s = byPlayer.remove(playerId);
        if (s != null) {
            byBot.remove(s.botId, s);
        }
    }

    // ── Timeout ──────────────────────────────────────────────────────────────

    /**
     * The half of the release rules that cannot be driven by chat: a player who says nothing, and a
     * player who walks away. Both have to be noticed without anyone speaking, so they need a clock.
     */
    private static void startSweeper() {
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        getScheduledExecutorService().scheduleWithFixedDelay(BotTakeover::sweep,
                SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS);
    }

    private static void sweep() {
        long now = System.currentTimeMillis();
        for (Session s : byPlayer.values()) {
            try {
                Character bot = live(s.botId);
                Character player = live(s.playerId);   // resolves real players too
                if (bot == null || player == null
                        || bot.getMap() == null || player.getMap() == null
                        || bot.getMapId() != player.getMapId()
                        || now - s.lastWordMs > SILENCE_RELEASE_MS) {
                    release(s, bot);
                }
            } catch (RuntimeException e) {
                release(s, null);   // never let one bad session stop the sweep
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * The bot on the player's map whose name appears in what they said.
     *
     * <p>Same rule the scripted Dispatcher uses, deliberately: a player who has learned they can
     * get a bot's attention by naming it should not have to learn a second rule for the AI. The
     * longest matching name wins, so "Ana" cannot steal a line meant for "Anastasia".
     */
    private static Character namedBotOnMap(Character player, String said) {
        MapleMap map = player.getMap();
        if (map == null) {
            return null;
        }
        String text = said.toLowerCase(Locale.ROOT);
        Character best = null;
        for (Character chr : map.getCharacters()) {
            if (chr == null || !BotHelpers.isBot(chr) || checkIfInvisibleBot(chr.getId())) {
                continue;
            }
            String name = chr.getName();
            if (name == null || name.isEmpty() || !text.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            // Some bots ARE their menu - the gacha machine, the blackjack dealer, the shops, the
            // OPQ runners. Those keep their scripted dialogue; taking one over would swap working
            // content for conversation.
            soloMapling.ArtificialPlayer.BotSM sm =
                    soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(chr.getId());
            if (sm != null && !sm.allowsAiTakeover()) {
                continue;
            }
            if (best == null || name.length() > best.getName().length()) {
                best = chr;
            }
        }
        return best;
    }

    /**
     * The live character for an id, bot or player.
     *
     * <p>Not BotHelpers.getCharFromChannelStorage: that one filters its result down to bots, and a
     * session has a player on the other end of it.
     */
    private static Character live(int cid) {
        try {
            return Server.getInstance()
                    .getChannel(SoloMaplingConstants.GameConstants.WORLD_SCANIA,
                            SoloMaplingConstants.GameConstants.CHANNEL_1)
                    .getPlayerStorage().getCharacterById(cid);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
