package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotSM;

/**
 * Minimal attack test bot: each tick, swing at the nearest in-reach mob with whatever
 * attack its class + weapon resolve to. No movement, no sub-states - it stands where it
 * spawned and attacks. Used to verify the per-class attack routes on Henesys Hunting
 * Ground 1. Mobs only move when a real player is on the map, so keep a GM there to feed
 * targets into reach.
 */
public class TestAttackBot extends BotSM {

    // Chat with this bot IS a feature - the menu below is the game. An AI conversation would
    // replace working content with small talk, so this type is never taken over.
    @Override
    public boolean allowsAiTakeover() {
        return false;
    }

    public TestAttackBot(Character character) {
        super(character);
        botType = "TestAttackBot";
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        BotAttackDriver.botAttack(getChr());
    }
}
