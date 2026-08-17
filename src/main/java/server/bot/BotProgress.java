/*
    Level progression for characters whose growth is part of their story - currently Wren alone.

    The level is DERIVED, not stored and ticked: elapsed real time since the character was created,
    plus a contribution from conversations held. That means no scheduler, nothing to drift out of
    sync, and a correct value whenever it is read - including after the server has been off for a
    week, which a ticking counter would have slept through.

    Wren's voice is meant to change as the number climbs, so the stage guidance below is part of
    the prompt rather than decoration. It is the one persona in the cast that is deliberately not
    constant.
*/
package server.bot;

import config.YamlConfig;

public final class BotProgress {

    private BotProgress() {
    }

    /**
     * @param startLevel     level the character began at
     * @param hoursElapsed   real hours since they started
     * @param interactions   conversations held, across everyone
     */
    public static int levelFor(int startLevel, long hoursElapsed, int interactions) {
        int perHours = Math.max(1, YamlConfig.config.server.BOT_PROGRESS_HOURS_PER_LEVEL);
        int perTalks = Math.max(1, YamlConfig.config.server.BOT_PROGRESS_TALKS_PER_LEVEL);
        int max = YamlConfig.config.server.BOT_PROGRESS_MAX_LEVEL;

        long fromTime = hoursElapsed / perHours;
        long fromTalking = (long) interactions / perTalks;
        long level = startLevel + fromTime + fromTalking;

        return (int) Math.max(startLevel, Math.min(max, level));
    }

    /**
     * Voice guidance for the current level band. Written as behaviour, not as a label.
     */
    public static String stageNote(int level) {
        if (level < 30) {
            return "You are level " + level + ", which is very weak, and you know it. You are new "
                    + "to fighting and easily out of your depth. Ask questions. Be openly grateful "
                    + "when someone helps you. Do not pretend to knowledge you do not have.";
        }
        if (level < 60) {
            return "You are level " + level + ". You are finding your feet and you are quietly "
                    + "proud of small victories. You still defer to stronger players but you are "
                    + "no longer helpless, and you have started to enjoy this.";
        }
        if (level < 100) {
            return "You are level " + level + ". You are genuinely competent now and you have "
                    + "stopped apologising for existing. You can hold your own, you know it, and "
                    + "there is a steadiness in you that was not there before.";
        }
        if (level < 140) {
            return "You are level " + level + ". You speak to strong players as an equal now. You "
                    + "remember being weak and you are kind to people who still are. The hesitancy "
                    + "is gone.";
        }
        return "You are level " + level + " and formidable. You are calm, certain, and economical "
                + "with words in a way you were not when you started. You have not forgotten who "
                + "helped you early on.";
    }
}
