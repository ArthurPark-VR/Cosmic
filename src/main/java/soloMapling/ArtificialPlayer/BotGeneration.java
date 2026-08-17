package soloMapling.ArtificialPlayer;

import client.Character;
import client.Client;
import client.Job;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotBuffRequestSystem.BotBuffRequestHandler;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.server.SoloMaplingConstants;
import soloMapling.server.SoloMaplingUtilities;

import java.awt.*;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static soloMapling.ArtificialPlayer.BotClientHandler.getBotClient;
import static soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.botEnterPortalDropDown;
import static soloMapling.ArtificialPlayer.BotDecoratorSystem.BotDecorate.setBotVariables;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.microTurnAroundToLeft;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.FreeMarket.FMShopDescGen.getRandomCharacterIGN;
import static soloMapling.server.ExecutorServiceManager.runAsync;
import static soloMapling.server.SoloMaplingUtilities.getChr;
import static soloMapling.server.SoloMaplingUtilities.channel;
import static soloMapling.server.SoloMaplingUtilities.getMapleMapById;
import static soloMapling.server.SoloMaplingUtilities.world;

public class BotGeneration {

    // Bot generation - handles anything related to putting the bots in the server

    // Atomic because bots spawn in parallel - a plain int would let two threads
    // grab the same id, silently overwriting one bot with another in storage.
    private static final AtomicInteger currentBotCount = new AtomicInteger(100);

    /**
     * Character id of the template every bot is cloned from, resolved once.
     *
     * <p>Upstream hardcodes 2, with 162-fmbot-data.sql seeding an 'fmbot' character at that id.
     * That seed is an INSERT IGNORE, and its own comment concedes the hazard: on a database that
     * already has a character at id 2, the insert is silently skipped. This server's database
     * predates SoloMapling, so id 2 was one of the player's own characters - and every bot was
     * cloned from it. That is one root cause for two symptoms: every bot wore that character's
     * equipment, and every bot inherited its skills, including custom Thunder Breaker skill ids
     * a clean client cannot resolve, which crashed the client on attack.
     *
     * <p>Resolved by account name instead, and if the template cannot be found or does not look
     * like a template, bots do not spawn at all. Refusing to spawn is far better than cloning a
     * player: the failure is loud, immediate, and cannot be mistaken for something else.
     */
    private static final int TEMPLATE_UNRESOLVED = -1;
    private static final int TEMPLATE_MISSING = -2;
    private static volatile int templateCid = TEMPLATE_UNRESOLVED;

    private static synchronized int resolveTemplateCid() {
        if (templateCid != TEMPLATE_UNRESOLVED) {
            return templateCid;
        }
        final String sql = """
                SELECT c.id, c.name, c.level, c.gm,
                       (SELECT COUNT(*) FROM inventoryitems i
                         WHERE i.characterid = c.id AND i.inventorytype = -1) AS equipped,
                       (SELECT COUNT(*) FROM skills s WHERE s.characterid = c.id) AS skills
                FROM characters c
                JOIN accounts a ON a.id = c.accountid
                WHERE a.name = 'fmbot'
                ORDER BY c.id LIMIT 1""";
        try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
             java.sql.PreparedStatement ps = con.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                System.err.println("[BotGeneration] FATAL: no character on the 'fmbot' account. "
                        + "Bots will not spawn. The 162-fmbot-data.sql seed is an INSERT IGNORE "
                        + "and was skipped, most likely because character id 2 was already taken. "
                        + "Create a level 1, gm 0 character with no equipment on account 'fmbot'.");
                templateCid = TEMPLATE_MISSING;
                return templateCid;
            }
            int id = rs.getInt("id");
            int equipped = rs.getInt("equipped");
            int skills = rs.getInt("skills");
            if (equipped > 0 || skills > 0 || rs.getInt("gm") > 0) {
                // Not fatal - decoration overwrites equipment - but every bot inherits the
                // skills, and inherited skills the client does not know crash it on attack.
                System.err.println("[BotGeneration] WARNING: bot template '" + rs.getString("name")
                        + "' (id " + id + ") has " + equipped + " equipped item(s), " + skills
                        + " skill(s), gm=" + rs.getInt("gm") + ". A template must be a plain "
                        + "level 1 character with none of those; bots inherit all of it.");
            }
            System.out.println("[BotGeneration] Bot template: '" + rs.getString("name")
                    + "' id=" + id + " level=" + rs.getInt("level"));
            templateCid = id;
        } catch (java.sql.SQLException e) {
            System.err.println("[BotGeneration] FATAL: could not resolve the bot template: " + e);
            templateCid = TEMPLATE_MISSING;
        }
        return templateCid;
    }

    /**
     * Worst-case duration of the spawn choreography (pre-drop delay 0.5-1.2s
     * + portal lag 1.5s + drop-down playback + optional turn-around delay
     * 1.0-1.5s + turn playback). Anything that must visually wait for a freshly
     * spawned bot to finish arriving (FSM first tick, shop opening, chair sits,
     * facing adjustments) should delay by at least this much.
     */
    public static final long SPAWN_CHOREOGRAPHY_MAX_MS = 7000;

    /** Total number of bots created since server start (for startup logging). */
    public static int getBotsCreatedCount() {
        return currentBotCount.get() - 100;
    }


    public static Character getConsoleBot() {
        Character consoleBot = getChr(999);
        if (consoleBot != null) {
            return consoleBot;
        }

        int botId = 999;
        int baseId = 2; // Base Bot Character

        try {
            Character chr = Character.loadCharFromDB(baseId, getBotClient(), false);
            consoleBot = chr;
        } catch (SQLException e) {
            e.printStackTrace();
        }

        consoleBot = setConsoleBot(consoleBot, botId); // Bot onDemandBot
        addBotToServer(consoleBot);
        return consoleBot;
    }

    public static int createBot(Point pos, MapleMap map) {
        return createBot(pos, map, 0, 0, 0);
    }

    public static int createBot(Point pos, MapleMap map, int baseClass, int minLevel, int maxLevel) {
        return createBot(pos, map, baseClass, minLevel, maxLevel, 0);
    }

    // forcedJobId > 0 pins the exact job (GM 'trainhere' test spawn); 0 = a random job for the class.
    public static int createBot(Point pos, MapleMap map, int baseClass, int minLevel, int maxLevel, int forcedJobId) {
        int cid = resolveTemplateCid();
        if (cid == TEMPLATE_MISSING) {
            return -1;   // refuse rather than clone whoever happens to hold id 2
        }

        Character bot = null;
        try {
            Character chr = Character.loadCharFromDB(cid, getBotClient(), false);
            bot = chr;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        int botId = SoloMaplingConstants.GameConstants.BOT_BASE_ID + currentBotCount.getAndIncrement();
        bot = setBotStats(bot, botId); // Bot onDemandBot
        addBotToServer(bot);
        placeBotOnMap(bot, pos, map);
        // Decorate before the drop-down plays so the bot arrives fully dressed
        // (decoration is an in-memory cache lookup, takes microseconds).
        if (baseClass <= 0) {
            setBotVariables(bot);
        } else {
            setBotVariables(bot, baseClass, minLevel, maxLevel, forcedJobId);
        }
        // Choreography sleeps ~2.5-6s in total; play it on a virtual thread so
        // mass spawning isn't gated on each bot's arrival animation. Drop-down ->
        // turn-around ordering is preserved because it's one sequential task.
        Character finalBot = bot;
        runAsync(() -> playSpawnChoreography(finalBot));
        return botId;
    }


    /**
     * Places the bot on a map + position and plays the portal drop-down animation
     * so the spawn looks like a real player arriving. Sequence runs inline so the
     * caller waits for the full spawn choreography to finish before continuing —
     * use this (not createBot's async path) when downstream actions must not race
     * the drop-down / turn-around (e.g. OPQ map transitions).
     *
     * This method blocks the calling thread for roughly 2.5-6s total. Callers
     * should already be running on a pooled executor (not the client thread).
     */
    public static void warpBotToLocation(Character fakechar, Point pos, MapleMap map) {
        placeBotOnMap(fakechar, pos, map);
        playSpawnChoreography(fakechar);
    }

    private static void placeBotOnMap(Character fakechar, Point pos, MapleMap map) {
        if (fakechar.getMap() == map) {
            fakechar.getMap().removePlayer(fakechar);
        }
        fakechar.setMap(map);
        fakechar.setPosition(pos);
        fakechar.setStance(5);
        map.addPlayer(fakechar);
    }

    /**
     * The spawn arrival choreography. Blocks the calling thread while it plays:
     *   - drop-down fires 500-1200ms after the bot is added to the map
     *     (plus ~1.5s portal lag inside botEnterPortalDropDown)
     *   - a random-direction micro turn-around fires 1000-1500ms after the
     *     drop-down playback completes - the strict ordering matters, the turn
     *     must never overlap the drop-down packets
     * Worst case is bounded by {@link #SPAWN_CHOREOGRAPHY_MAX_MS}.
     */
    private static void playSpawnChoreography(Character fakechar) {
        long dropDelayMs = ThreadLocalRandom.current().nextLong(500, 1201);
        if (!BotHelpers.blockingSleep(dropDelayMs)) return;
        botEnterPortalDropDown(fakechar);

        // Bots spawn facing right by default, so a 50% roll to flip to left gives
        // roughly even left/right distribution without a no-op right-turn.
        if (ThreadLocalRandom.current().nextBoolean()) {
            long turnDelayMs = ThreadLocalRandom.current().nextLong(1000, 1501);
            if (!BotHelpers.blockingSleep(turnDelayMs)) return;
            microTurnAroundToLeft(fakechar);
        }
    }

    private static Character setConsoleBot(Character baseChr, int botId) {
        Character onDemandBot = baseChr; // Character.getDefault(c)
        onDemandBot.setClient(getBotClient());
        onDemandBot.setName("Console");

        onDemandBot.setID(botId);
        onDemandBot.setFame(botId); // debug purposes
        onDemandBot.setLevel(69);
        onDemandBot.setJob(Job.getById(420));

        return onDemandBot;
    }

    private static Character setBotStats(Character baseChr, int botId) {
        Character onDemandBot = baseChr; // Character.getDefault(c)
        onDemandBot.setClient(getBotClient());
        onDemandBot.setName(getRandomCharacterIGN());
        onDemandBot.setID(botId);
        onDemandBot.setFame(botId); // debug purposes
        return onDemandBot;
    }

    public static void removeBotFromServer(Character fakechar) {
        // Drop any AI conversation history for this name, or the map grows for the life of the
        // process. Done here rather than on a timer because despawn is the exact moment it dies.
        soloMapling.ArtificialPlayer.BotAiSystem.BotAiChat.forget(fakechar.getName());
        fakechar.getMap().removePlayer(fakechar);
        channel.removePlayer(fakechar);
        world.getPlayerStorage().removePlayer(fakechar.getId());
        CharacterStorage.removeActiveBot(fakechar.getId());//
        BotBuffDriver.clearBot(fakechar.getId());   // Phase 3a: release buff recast timers
        BotBuffRequestHandler.clearBot(fakechar.getId());   // release chat-buff-request cooldown
    }

    private static void addBotToServer(Character fakechar) {
//        final Channel channel = Server.getInstance().getChannel(BotSM.GameConstants.WORLD_SCANIA, BotSM.GameConstants.CHANNEL_1);
        channel.addPlayer(fakechar);
//        World world = Server.getInstance().getWorld(BotSM.GameConstants.WORLD_SCANIA);
        world.getPlayerStorage().addPlayer(fakechar);
    }

    public static void spawnBotFm(Character fakechar, Point pt) {
        int fmMap = 910000000;
        MapleMap spawnMap = getBotClient().getChannelServer().getMapFactory().getMap(fmMap);
        fakechar.setMap(spawnMap);
        fakechar.setPosition(pt);
        fakechar.setStance(5);
        spawnMap.addPlayer(fakechar);
    }

    /*
    Creates a bot on demand. The bot is registered in channel storage synchronously
    inside createBot, so it's normally ready on the first check - the 100ms poll
    loop only kicks in as a fallback. If the bot isn't ready after 3000ms, returns null.
     */
    public static Character createBotPollReadiness(Point position, int mapId) {
        int botId = BotGeneration.createBot(position, getMapleMapById(mapId));

        for (int i = 0; i < 30; i++) { // 30 * 100ms = 3000ms max
            Character fakechar = BotHelpers.getCharFromChannelStorage(botId);
            if (fakechar != null) {
                if (i > 0) {
                    debugprint("Bot " + botId + " ready after " + (i * 100) + "ms");
                }
                return fakechar;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        System.err.println("Bot " + botId + " not ready after 3 seconds, skipping store");
        return null;
    }

}
