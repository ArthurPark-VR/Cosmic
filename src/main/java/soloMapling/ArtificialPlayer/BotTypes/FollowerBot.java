package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import soloMapling.ArtificialPlayer.BotAiSystem.BotOrders;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotOptionMenu;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotTickService;

import java.util.List;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.BotLogger.log;

// A recruited companion: follows one real player everywhere. The heavy lifting is the GC follow
// engine (50ms same-map tailing + 400ms cross-map session that travels portal chains and redirects
// mid-trip - see GCFollow); this FSM is only the supervisor: it tracks the leader BY ID so a relog
// re-attaches (a session holds a hard Character ref and dies with it), re-arms the session whenever
// it's down, watches party membership, and converts itself away when the ride ends.
//
// Cadence: pinned fast (~750ms) and governor-exempt - a follower is foreground content whether or
// not its current map is observed (it may be catching up through empty maps). The map it shares
// with its leader is FULL by definition, so this costs almost nothing in practice.
//
// Lifecycle in: SocialBot recruit (party join -> convert), TrainingBot "Follow me!", !bot followbot.
// Lifecycle out: "Train here with me!" -> TrainingBot (station-here handoff); leader gone past the
// grace / party dissolved -> TrainingBot on a mob map, SocialBot in a town.
public class FollowerBot extends BotSM {

    private static final long FOLLOW_TICK_MS = 750;
    private static final long LEADER_LOST_GRACE_MS = 90_000;
    // No map change in this long while the leader is elsewhere = the portal graph has no route in.
    private static final long CROSS_MAP_STALL_MS = 15_000;

    private enum FollowPhase { INIT, FOLLOW, LEADER_LOST }

    // Written on the macro tick, read from menu callbacks - keep volatile.
    private volatile FollowPhase followPhase = FollowPhase.INIT;
    private volatile int leaderId = -1;
    private volatile long leaderLostSinceMs = 0;
    private volatile boolean wasPartied = false;
    private volatile boolean pausedForTrade = false;
    // Cross-map chase progress: the map the bot was last seen on, and when it arrived there.
    private volatile int lastSeenMapId = -1;
    private volatile long crossMapSinceMs = 0;

    // NOTE: no "here" keyword - "there" contains "here", so a casual "hi there" would trigger it.
    private final BotOptionMenu menu = new BotOptionMenu(this,
            List.of("Train here with me!", "Nevermind"),
            List.of(List.of("train", "grind", "station"), List.of("nevermind", "bye", "nah", "nope")),
            this::onMenuSelect);

    public FollowerBot(Character character) {
        super(character);
        botType = "FollowerBot";
        dialoguePath = "FollowerBotDialogue.yaml";
    }

    // Pinned cadence, observed or not: supervision (re-arm/relog/party checks) must never sleep 9-12s.
    @Override
    public void checkPrioritySpeed() {
        updateScheduleDelay(FOLLOW_TICK_MS);
    }

    // A companion keeps up while you talk to it. Suspending a follower's tick would stop the
    // supervision that re-arms a dropped follow session and re-attaches after a relog - so the bot
    // would quietly fall behind during the conversation and be gone by the end of it. There is no
    // scripted routine here for the AI to supersede anyway: this FSM only follows.
    @Override
    protected boolean pausedByAi() {
        return false;
    }

    @Override
    public synchronized void startScheduledTask(long initialDelayMs) {
        super.startScheduledTask(initialDelayMs);
        int id = getChr().getId();
        BotTickService.setNoThrottle(id, true);
        // Conversions inherit the spawn-choreography stagger, but a converted follower is already
        // live in-world - pull the first tick to the follow cadence so it starts moving immediately.
        BotTickService.reschedule(id, FOLLOW_TICK_MS);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        if (getState() == BotState.TRADING) {
            // Trades are sacred: halt the follow session so the bot doesn't walk out of the trade.
            if (!pausedForTrade) {
                pausedForTrade = true;
                GCMovement.stop(chr);
            }
            return;
        }
        pausedForTrade = false;

        switch (followPhase) {
            case INIT -> doInit();
            case FOLLOW -> doFollow();
            case LEADER_LOST -> doLeaderLost();
        }

        if (!getRunning()) {
            return; // a phase converted this bot away mid-tick
        }
        pollLeaderInvite();
        menu.poll(); // last: a selection may also convert this bot away
    }

    // An unpartied follower (e.g. via !bot followbot) accepts a party invite from its OWN leader -
    // that's how the GM flow gets party EXP going - and politely rejects anyone else so the
    // first-wins-free queue never holds a rotting entry.
    private void pollLeaderInvite() {
        Character chr = getChr();
        if (!BotPartyQueue.getInstance().hasPendingInvite(chr)) {
            return;
        }
        BotPartyQueue.PartyInviteEntry entry = BotPartyQueue.getInstance().getPartyInvite(chr);
        Character inviter = entry == null ? null : entry.getInviter();
        if (inviter != null && inviter.getId() == leaderId && chr.getParty() == null) {
            if (BotPartyCommands.botAcceptPartyInvite(chr)) {
                wasPartied = true;
                sayNode("PartyJoined", inviter);
            }
            return;
        }
        BotPartyCommands.botRejectPartyInvite(chr);
    }

    // ── Phases ───────────────────────────────────────────────────────────────

    private void doInit() {
        Character chr = getChr();
        int pending = BotRecruitManager.consumePendingLeader(chr.getId());
        leaderId = pending > 0 ? pending : leaderIdFromParty();
        if (leaderId <= 0) {
            fallbackConvert();
            return;
        }
        Character leader = resolveLeader();
        if (leader == null || leader.getMap() == null) {
            leaderLostSinceMs = now();
            followPhase = FollowPhase.LEADER_LOST;
            return;
        }
        wasPartied = chr.getParty() != null;
        GCMovement.follow(chr, leader);
        sayNode("FollowStart", leader);
        followPhase = FollowPhase.FOLLOW;
    }

    private void doFollow() {
        Character chr = getChr();
        Character leader = resolveLeader();
        if (leader == null || leader.getMap() == null) {
            leaderLostSinceMs = now();
            followPhase = FollowPhase.LEADER_LOST;
            sayNode("LeaderLost", null);
            return;
        }
        if (chr.getParty() != null) {
            wasPartied = true;
        } else if (wasPartied) {
            // Kicked / disband: the ride is over.
            sayNode("PartyFarewell", leader);
            fallbackConvert();
            return;
        }
        if (!GCMovement.isFollowing(chr)) {
            // Initial arm, post-trade re-arm, and relog re-attach (a dead session cleared itself;
            // the freshly resolved leader Character makes the new session current).
            GCMovement.follow(chr, leader);
        }
        catchUpIfStranded(chr, leader);
        fightIfOrdered(chr);
    }

    /**
     * The boss-door case: the leader is on a map the follow engine cannot walk to.
     *
     * <p>GCFollow chases across maps by travelling the portal graph, which is right almost
     * everywhere and reads as the bot making its own way over. It cannot reach a boss interior, an
     * event stage or a PQ map, because those are entered through an NPC or a scripted door and no
     * portal chain leads in. Left alone the follower re-tries travel forever and never arrives.
     *
     * <p>The trigger is lack of PROGRESS, not elapsed time. A legitimate multi-hop trip changes map
     * every few seconds, so it never trips this; a bot that has not changed map at all while its
     * leader is somewhere else has no route, and gets warped in.
     */
    private void catchUpIfStranded(Character chr, Character leader) {
        if (leader.getMapId() == chr.getMapId()) {
            crossMapSinceMs = 0;
            lastSeenMapId = chr.getMapId();
            return;
        }
        if (crossMapSinceMs == 0 || chr.getMapId() != lastSeenMapId) {
            lastSeenMapId = chr.getMapId();
            crossMapSinceMs = now();   // just set off, or just made a hop - the chase is progressing
            return;
        }
        if (now() - crossMapSinceMs < CROSS_MAP_STALL_MS) {
            return;
        }
        crossMapSinceMs = now();       // re-arm, so a warp that fails is retried rather than spammed
        java.awt.Point at = leader.getPosition();
        if (at != null) {
            GCMovement.warpTo(chr, leader.getMapId(), at.x, at.y);
        }
    }

    // "Follow me and attack" - the follow engine already has the bot standing where its leader is
    // standing, so fighting alongside them is just swinging at whatever came into reach. botAttack
    // acquires its own target, faces it, and is cooldown-gated internally, so calling it every
    // 750ms tick is cheap: most calls do nothing. Buffs are re-asserted on the same beat and are
    // likewise gated by their own recast timers.
    //
    // The order is a standing flag rather than a field here because re-typing a bot builds a new
    // FSM (BotTypeManager.convertBotType), and "follow me" is itself a conversion - a field would
    // be discarded by the very command that precedes this one.
    private void fightIfOrdered(Character chr) {
        if (!BotOrders.isFighting(chr.getId())) {
            return;
        }
        try {
            BotBuffDriver.botBuff(chr);
            BotAttackDriver.botAttack(chr);
        } catch (RuntimeException e) {
            // A swing that fails must never break the follow loop.
        }
    }

    private void doLeaderLost() {
        Character chr = getChr();
        Character leader = resolveLeader();
        if (leader != null && leader.getMap() != null) {
            followPhase = FollowPhase.FOLLOW;
            return;
        }
        if (wasPartied && chr.getParty() == null) {
            fallbackConvert();
            return;
        }
        if (now() - leaderLostSinceMs > LEADER_LOST_GRACE_MS) {
            sayNode("PartyFarewell", null);
            fallbackConvert();
        }
    }

    // ── Menu (Dispatcher routes a "botname" chat here via displayCommands) ───

    @Override
    public void displayCommands(Character chr) {
        menu.show(chr);
    }

    private void onMenuSelect(int idx, Character player) {
        Character chr = getChr();
        if (idx == 0) { // Train here with me!
            if (player.getId() != leaderId) {
                sayNode("LoyalToLeader", player);
                menu.close(player);
                return;
            }
            if (MapMobIndex.level(chr.getMapId()) < 0) {
                sayNode("NoMobsHere", player);
                menu.close(player);
                return;
            }
            sayNode("StationHere", player);
            menu.close(player);
            BotRecruitManager.markStationHere(chr.getId());
            GCMovement.stop(chr);
            BotTypeManager.convertBotType(chr, BotTypeManager.BotType.TRAINING_BOT);
            return;
        }
        sayNode("Goodbye", player);
        menu.close(player);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    // Resolve the live leader from the channel's player storage each use (OPQ pattern): an id
    // survives relog, a Character reference doesn't.
    private Character resolveLeader() {
        if (leaderId <= 0) {
            return null;
        }
        return getChr().getClient().getChannelServer().getPlayerStorage().getCharacterById(leaderId);
    }

    private int leaderIdFromParty() {
        Party party = getChr().getParty();
        if (party == null) {
            return -1;
        }
        Character lead = party.getLeader() != null ? party.getLeader().getPlayer() : null;
        if (lead != null && !isBot(lead)) {
            return lead.getId();
        }
        Character member = firstRealMember(party);
        return member == null ? -1 : member.getId();
    }

    private Character firstRealMember(Party party) {
        for (PartyCharacter pc : party.getMembers()) {
            Character p = pc == null ? null : pc.getPlayer();
            if (p != null && !isBot(p) && p.getMap() != null) {
                return p;
            }
        }
        return null;
    }

    // The ride ended: become a TrainingBot on a mob map, a SocialBot in a town. Callers speak
    // their own farewell first; this only re-types.
    private void fallbackConvert() {
        Character chr = getChr();
        BotRecruitManager.clearArmed(chr.getId());
        GCMovement.stop(chr);
        boolean grindable = MapMobIndex.level(chr.getMapId()) >= 0;
        BotTypeManager.convertBotType(chr,
                grindable ? BotTypeManager.BotType.TRAINING_BOT : BotTypeManager.BotType.SOCIAL_BOT);
    }

    private void sayNode(String node, Character player) {
        Character chr = getChr();
        if (chr == null || chr.getMap() == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return;
        }
        try {
            String line = BotDialogueHandler.getRandomResolvedLine(dialoguePath, botType, node, chr, player);
            if (line != null) {
                BotSpeak(chr, line);
            }
        } catch (Exception e) {
            // a missing dialogue node must never break the follow loop
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    @Override
    public synchronized void stopScheduledTask() {
        Character chr = getChr();
        if (chr != null) {
            BotRecruitManager.clearArmed(chr.getId()); // pending station/leader handoffs survive on purpose
            GCMovement.disable(chr); // ends the follow session + releases the shared movement lock
        }
        super.stopScheduledTask();
        log("[FollowerBot] stopped: " + (chr != null ? chr.getName() : "?"));
    }
}
