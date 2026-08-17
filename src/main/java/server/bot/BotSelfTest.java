/*
    In-server verification for the bot code, run by setting BOT_SELFTEST=1 in the environment.
    Does nothing otherwise.

    This exists because compiling proves the types line up and nothing else. Almost every bot path
    calls into machinery that assumes a connected player somewhere behind it, and the only way to
    find out whether that assumption bites is to boot a real server with a real database and a
    real map and run the path. Every bug worth catching in this feature was caught this way and
    none of them by the compiler: a null io channel, an account view map that only exists after
    somebody logs in, and an instruction matcher that ignored the word "stop".

    Everything destructive cleans up after itself - the marriage is divorced, the party disbanded -
    so this can be pointed at a live server, though a scratch database is the better habit.
*/
package server.bot;

import client.Character;
import net.server.Server;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.LifeFactory;
import server.life.Monster;
import server.maps.MapleMap;
import tools.DatabaseConnection;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;

public final class BotSelfTest {
    private static final Logger log = LoggerFactory.getLogger(BotSelfTest.class);

    private static int passed = 0;
    private static int failed = 0;

    private BotSelfTest() {
    }

    public static void runIfRequested() {
        if (!"1".equals(System.getenv("BOT_SELFTEST"))) {
            return;
        }
        log.info("SELFTEST ==================== start ====================");
        try {
            testTraitParsing();
            testMovementInMap();
            testWarpAcrossMaps();
            testCombatDamage();
            testCombatStopsOnReflect();
            testPartyJoin();
            testMarriageGateAndMarriage();
            testIntentPlumbing();
            testScheduledTasks();
            testBotsAreStandingOnGround();
            testLooksAreDistinct();
            testSettlePacket();
        } catch (Throwable t) {
            log.error("SELFTEST harness itself blew up", t);
            failed++;
        }
        log.info("SELFTEST ==================== {} passed, {} failed ====================", passed, failed);
    }

    private static void check(String what, boolean ok, Object detail) {
        if (ok) {
            passed++;
            log.info("SELFTEST PASS  {} [{}]", what, detail);
        } else {
            failed++;
            log.error("SELFTEST FAIL  {} [{}]", what, detail);
        }
    }

    private static void testTraitParsing() {
        String json = "{\"favourite_food\":\"tea\",\"hangout\":\"Ereve\",\"job\":\"Wind Archer\",\"role\":\"close\"}";
        check("traitValue job", "Wind Archer".equals(BotWorld.traitValue(json, "job")),
                BotWorld.traitValue(json, "job"));
        check("traitValue hangout", "Ereve".equals(BotWorld.traitValue(json, "hangout")),
                BotWorld.traitValue(json, "hangout"));
        check("traitValue missing key is empty", BotWorld.traitValue(json, "nope").isEmpty(), "");
        check("traitValue null json is empty", BotWorld.traitValue(null, "job").isEmpty(), "");
    }

    private static void testMovementInMap() {
        Character bot = BotWorld.get("Cassiel");
        if (bot == null) {
            check("movement: Cassiel present", false, "not spawned");
            return;
        }
        Point before = new Point(bot.getPosition());
        boolean arrived = BotMovement.stepTowards(bot, new Point(before.x + 400, before.y));
        Point after = bot.getPosition();

        check("stepTowards did not throw", true, before + " -> " + after);
        check("stepTowards moved or reported arrival",
                arrived || after.x != before.x, "arrived=" + arrived + " after=" + after);
        check("stepTowards stayed in the map", bot.getMapId() == 130000000, bot.getMapId());
    }

    private static void testWarpAcrossMaps() {
        Character bot = BotWorld.get("Rhys");
        if (bot == null) {
            check("warp: Rhys present", false, "not spawned");
            return;
        }
        int fromMap = bot.getMapId();
        MapleMap henesys = Server.getInstance().getWorld(0).getChannel(1)
                .getMapFactory().getMap(100000000);

        BotMovement.warpTo(bot, henesys, henesys.getPortal(0).getPosition());
        check("warpTo changed map", bot.getMapId() == 100000000, fromMap + " -> " + bot.getMapId());
        check("warpTo registered in the new map",
                henesys.getCharacterById(bot.getId()) != null, "found in Henesys");

        // Put him back where he belongs.
        MapleMap ereve = Server.getInstance().getWorld(0).getChannel(1)
                .getMapFactory().getMap(130000000);
        BotMovement.warpTo(bot, ereve, ereve.getPortal(0).getPosition());
        check("warpTo back to Ereve", bot.getMapId() == 130000000, bot.getMapId());
    }

    private static void testCombatDamage() {
        Character bot = BotWorld.get("Seraphine");
        if (bot == null) {
            check("combat: Seraphine present", false, "not spawned");
            return;
        }
        MapleMap map = bot.getMap();

        // A Blue Snail, which exists in every v83 install.
        Monster mob = LifeFactory.getMonster(100100);
        if (mob == null) {
            check("combat: test monster loaded", false, "mob 100100 missing from wz");
            return;
        }
        map.spawnMonsterOnGroundBelow(mob, new Point(bot.getPosition()));

        int hpBefore = mob.getHp();
        // Drive the real tick rather than a private helper, so scheduling, target selection,
        // range, packet construction and damage application are all covered.
        BotCombat.engage(bot, bot);
        sleep(3000);
        BotCombat.disengage(bot.getName());

        int hpAfter = mob.isAlive() ? mob.getHp() : 0;
        check("combat dealt damage", hpAfter < hpBefore, hpBefore + " -> " + hpAfter);
        check("combat left the bot alive", bot.getHp() > 0, bot.getHp());

        if (mob.isAlive()) {
            map.killMonster(mob, bot, false, (short) 0);
        }
    }

    private static void testCombatStopsOnReflect() {
        Character bot = BotWorld.get("Elowen");
        if (bot == null) {
            check("reflect: Elowen present", false, "not spawned");
            return;
        }
        MapleMap map = bot.getMap();
        Monster mob = LifeFactory.getMonster(100100);
        if (mob == null) {
            check("reflect: test monster loaded", false, "mob missing");
            return;
        }
        map.spawnMonsterOnGroundBelow(mob, new Point(bot.getPosition()));
        // A real MobSkill, not null: applyMonsterBuff's packet path dereferences it, which is a
        // quirk of that API rather than anything to do with bots.
        server.life.MobSkill reflect = server.life.MobSkillFactory.getMobSkillOrThrow(
                server.life.MobSkillType.PHYSICAL_COUNTER, 1);
        mob.applyMonsterBuff(java.util.Map.of(client.status.MonsterStatus.WEAPON_REFLECT, 10),
                1, 60000, reflect, new java.util.ArrayList<>());

        int hpBefore = mob.getHp();
        BotCombat.engage(bot, bot);
        sleep(2500);
        BotCombat.disengage(bot.getName());
        int hpAfter = mob.isAlive() ? mob.getHp() : 0;

        check("combat held fire under reflect", hpAfter == hpBefore, hpBefore + " -> " + hpAfter);
        if (mob.isAlive()) {
            map.killMonster(mob, bot, false, (short) 0);
        }
    }

    private static void testPartyJoin() {
        Character leader = BotWorld.get("Cassiel");
        Character joiner = BotWorld.get("Rhys");
        if (leader == null || joiner == null) {
            check("party: both bots present", false, "missing");
            return;
        }
        if (!Party.createParty(leader, false)) {
            check("party created", false, "createParty returned false");
            return;
        }
        int partyId = leader.getParty().getId();
        check("party created", partyId > 0, partyId);

        // Exactly what PartyOperationHandler does before handing off to the bot.
        InviteCoordinator.createInvite(InviteType.PARTY, leader, partyId, joiner.getId());
        BotSocial.onPartyInvite(joiner, partyId);
        sleep(5000);   // the accept is deliberately delayed 1.2-3.5s

        check("bot accepted the party invite",
                joiner.getParty() != null && joiner.getParty().getId() == partyId,
                joiner.getParty() == null ? "still no party" : joiner.getParty().getId());
        check("party sees both members",
                leader.getParty() != null && leader.getParty().getMembers().size() == 2,
                leader.getParty() == null ? 0 : leader.getParty().getMembers().size());

        // Disband, so running this against a live server does not leave a party standing.
        Party.leaveParty(leader.getParty(), joiner.getClient());
        Party.leaveParty(leader.getParty(), leader.getClient());
    }

    private static void testMarriageGateAndMarriage() {
        Character suitor = BotWorld.get("Cassiel");
        if (suitor == null) {
            check("marriage: suitor present", false, "missing");
            return;
        }
        // Gate first: nobody has spoken to Wren, so this must be refused.
        BotMarriage.Result cold = BotMarriage.propose(suitor, "Wren");
        check("marriage refused without the conversations",
                cold == BotMarriage.Result.NOT_CLOSE_ENOUGH, cold);

        // Now fake the history the gate is measuring and retry.
        setInteractions("Wren", suitor.getName(), BotMarriage.EXCHANGES_REQUIRED);
        BotMarriage.Result warm = BotMarriage.propose(suitor, "Wren");
        check("marriage accepted once earned", warm == BotMarriage.Result.MARRIED, warm);

        Character wren = BotWorld.get("Wren");
        check("partnerId set on both sides",
                suitor.getPartnerId() == wren.getId() && wren.getPartnerId() == suitor.getId(),
                suitor.getPartnerId() + " / " + wren.getPartnerId());
        check("marriage ring created", suitor.getMarriageRing() != null,
                suitor.getMarriageRing());

        check("divorce undoes it", BotMarriage.divorce(suitor), suitor.getPartnerId());
        check("partnerId cleared", suitor.getPartnerId() <= 0, suitor.getPartnerId());
        setInteractions("Wren", suitor.getName(), 0);
    }

    private static void testIntentPlumbing() {
        Character bot = BotWorld.get("Doyeon");
        Character leader = BotWorld.get("Almanac");
        if (bot == null || leader == null) {
            check("intent: bots present", false, "missing");
            return;
        }
        BotChat.applyIntent(bot, leader, "hey follow me");
        check("follow me started a follow", BotMovement.isFollowing(bot.getName()), "following");
        check("follow me did not start combat", !BotCombat.isFighting(bot.getName()), "not fighting");

        BotChat.applyIntent(bot, leader, "ok help me fight");
        check("help me fight started combat", BotCombat.isFighting(bot.getName()), "fighting");
        check("fight cancelled the follow loop", !BotMovement.isFollowing(bot.getName()), "not following");

        BotChat.applyIntent(bot, leader, "stop");
        check("stop ended combat", !BotCombat.isFighting(bot.getName()), "stopped");
        check("stop ended following", !BotMovement.isFollowing(bot.getName()), "stopped");

        BotChat.applyIntent(bot, leader, "what is your favourite food");
        check("small talk changed nothing",
                !BotCombat.isFighting(bot.getName()) && !BotMovement.isFollowing(bot.getName()), "idle");

        // Bare single-word orders, and the words they must NOT be confused with.
        BotChat.applyIntent(bot, leader, "fight");
        check("bare 'fight' engages", BotCombat.isFighting(bot.getName()), "fighting");
        BotChat.applyIntent(bot, leader, "wait");
        check("bare 'wait' disengages", !BotCombat.isFighting(bot.getName()), "stopped");

        BotChat.applyIntent(bot, leader, "follow me");
        BotChat.applyIntent(bot, leader, "i stopped by the shop earlier");
        check("'stopped' is not an order", BotMovement.isFollowing(bot.getName()), "still following");
        BotChat.applyIntent(bot, leader, "stop");
        check("bare 'stop' really stops", !BotMovement.isFollowing(bot.getName()), "stopped");

        BotChat.applyIntent(bot, leader, "that killer bee was awful");
        check("'killer' is not an attack order", !BotCombat.isFighting(bot.getName()), "idle");
    }

    /**
     * Every bot must be standing on a foothold, not hanging above one.
     *
     * <p>This is the check that should have existed from the start. The evidence was already in
     * this harness's own output - a bot spawned at y=-67 and the first ground-snapped step put it
     * at y=30 - and it went unread until someone logged in and saw the whole cast floating in the
     * air. A number printed is not a number checked.
     */
    private static void testBotsAreStandingOnGround() {
        int floating = 0;
        String worst = "";
        int worstGap = 0;

        for (Character bot : BotWorld.all()) {
            MapleMap map = bot.getMap();
            if (map == null) {
                continue;
            }
            Point at = bot.getPosition();
            Point ground = BotMovement.onGround(map, at);
            int gap = Math.abs(ground.y - at.y);
            if (gap > 8) {
                floating++;
                if (gap > worstGap) {
                    worstGap = gap;
                    worst = bot.getName() + " at y=" + at.y + ", ground y=" + ground.y;
                }
            }
        }
        check("no bot is floating", floating == 0,
                floating == 0 ? BotWorld.all().size() + " bots all grounded" : floating + " floating, worst: " + worst);
    }

    /**
     * The cast should not all look the same. Checks the five written characters are individually
     * distinct and that the derived looks actually vary.
     */
    private static void testLooksAreDistinct() {
        java.util.Set<String> looks = new java.util.HashSet<>();
        java.util.Set<Integer> hairs = new java.util.HashSet<>();
        java.util.Set<Integer> faces = new java.util.HashSet<>();

        for (Character bot : BotWorld.all()) {
            var eq = bot.getInventory(client.inventory.InventoryType.EQUIPPED);
            // The whole outfit, because "they all look the same" is about the silhouette, not
            // just the head - two bots may share a face and still be told apart at a glance.
            looks.add(bot.getHair() + "/" + bot.getFace()
                    + "/" + itemIn(eq, (short) -5) + "/" + itemIn(eq, (short) -6)
                    + "/" + itemIn(eq, (short) -7));
            hairs.add(bot.getHair());
            faces.add(bot.getFace());
        }
        int total = BotWorld.all().size();
        check("hair varies across the cast", hairs.size() >= total / 2, hairs.size() + " distinct hairstyles across " + total);
        check("faces vary across the cast", faces.size() >= total / 2, faces.size() + " distinct faces across " + total);
        check("no two bots look alike", looks.size() == total, looks.size() + "/" + total + " unique full looks");

        for (String name : new String[]{"Cassiel", "Rhys", "Seraphine", "Elowen", "Wren"}) {
            Character bot = BotWorld.get(name);
            if (bot == null) {
                check("look: " + name + " present", false, "missing");
                continue;
            }
            var equipped = bot.getInventory(client.inventory.InventoryType.EQUIPPED);
            boolean dressed = equipped.getItem((short) -5) != null && equipped.getItem((short) -7) != null;
            check("look: " + name + " is dressed", dressed,
                    "hair=" + bot.getHair() + " face=" + bot.getFace()
                            + " top=" + (equipped.getItem((short) -5) == null ? "none" : equipped.getItem((short) -5).getItemId()));
        }
    }

    /**
     * The packet that stops a bot hanging in the air, checked byte by byte.
     *
     * <p>This is the one part of the bot code a running server cannot prove, because the thing
     * being tested is what a MapleStory client does with these bytes and there is no client here.
     * So the bytes themselves are checked against the layout AbstractMovementPacketHandler parses:
     * one command-0 fragment carrying position, foothold and a standing stance. Getting the shape
     * right is necessary, not sufficient - it still has to be looked at in game.
     */
    private static void testSettlePacket() {
        Character bot = BotWorld.get("Cassiel");
        if (bot == null) {
            check("settle: Cassiel present", false, "missing");
            return;
        }
        byte[] bytes = BotWorld.settlePacket(bot).getBytes();

        // opcode(2) chrId(4) zero(4) count(1) command(1) x,y,vx,vy,fh(10) stance(1) duration(2)
        check("settle packet is the right length", bytes.length == 25, bytes.length + " bytes");
        if (bytes.length != 25) {
            return;
        }

        int chrId = (bytes[2] & 0xFF) | (bytes[3] & 0xFF) << 8 | (bytes[4] & 0xFF) << 16 | (bytes[5] & 0xFF) << 24;
        int count = bytes[10];
        int command = bytes[11];
        int x = (short) ((bytes[12] & 0xFF) | (bytes[13] & 0xFF) << 8);
        int y = (short) ((bytes[14] & 0xFF) | (bytes[15] & 0xFF) << 8);
        int foothold = (short) ((bytes[20] & 0xFF) | (bytes[21] & 0xFF) << 8);
        int stance = bytes[22];

        check("settle names the right character", chrId == bot.getId(), chrId + " vs " + bot.getId());
        check("settle carries exactly one movement", count == 1, count);
        check("settle uses absolute move (command 0)", command == 0, command);
        check("settle carries the real position",
                x == bot.getPosition().x && y == bot.getPosition().y, x + "," + y);
        check("settle carries a real foothold, not 0", foothold > 0, foothold);
        check("settle uses a standing stance", stance == 0, stance);
    }

    private static int itemIn(client.inventory.Inventory inv, short slot) {
        var item = inv.getItem(slot);
        return item == null ? 0 : item.getItemId();
    }

    /**
     * Runs the timed background tasks by hand.
     *
     * <p>These are the tasks a boot test never reaches. The ranking pass fires hourly and the
     * autosaver on its own interval, so a two-minute verification run proves nothing about either
     * - which is exactly how a bot account with a null lastlogin reached a live server and threw
     * a NullPointerException on the hour, aborting the ranking update for every real character
     * too. Driving them directly costs a second and closes that gap.
     */
    private static void testScheduledTasks() {
        try {
            new net.server.task.RankingLoginTask().run();
            check("ranking pass survives the bot account", true, "no exception");
        } catch (Exception e) {
            check("ranking pass survives the bot account", false, e.toString());
        }

        try {
            new net.server.task.CharacterAutosaverTask(Server.getInstance().getWorld(0)).run();
            check("autosave survives bot characters", true, "no exception");
        } catch (Exception e) {
            check("autosave survives bot characters", false, e.toString());
        }
    }

    private static void setInteractions(String botName, String playerName, int count) {
        final String sql = """
                INSERT INTO bot_relationship (bot_name, player_name, interactions)
                VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE interactions = ?""";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, botName);
            ps.setString(2, playerName);
            ps.setInt(3, count);
            ps.setInt(4, count);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("SELFTEST could not seed relationship", e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
