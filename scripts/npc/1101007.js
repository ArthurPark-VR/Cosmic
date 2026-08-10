/* Author:          Cosmic (custom)
    NPC Name:       Hawkeye (1101007)
    Description:    Hands out the custom Thunder Breaker 4th job skills.

    This NPC deliberately does NOT perform the job advancement. v83 already advances Cygnus
    Knights from 3rd to 4th job at Lv. 120, at the end of the "Chief Knight of the Empress"
    questline (quest 20408, via Neinheart and Shinsoo on Ereve). Advancing anywhere else
    would be a trap: quest 20400, the entry to that chain, requires job 1511, so a Thunder
    Breaker who reached 1512 early could never start it and would lose the whole chain
    along with the Chief Knight medal.

    Quest 20408 teaches these skills itself. This script only exists as a recovery path for
    a character who reached 1512 without them - most obviously via the GM command @job 1512.

    Hawkeye's own quests (20105, 20205, 20305, 20315, 20605, 20615, 2309) are unaffected:
    those run through the quest window, which is handled by QuestActionHandler and never
    consults NPC scripts. He had no NPC script at all before this one, and they worked.
*/

var status = -1;

// [skill id, master level] - master level matches the level count in Skill.wz/1512.img.
var SKILLS = [
    [15121000, 30],   // Maple Warrior
    [15121001, 30],   // Sharp Eyes
    [15121002, 30],   // Power Stance
    [15121003, 5],    // Hero's Will
    [15121004, 20]    // Flash Jump
];

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        cm.dispose();
        return;
    }
    if (mode == 0) {
        cm.sendOk("Come back when you feel ready.");
        cm.dispose();
        return;
    }
    status++;

    var jobId = cm.getJobId();

    if (status == 0) {
        if (jobId != 1512) {
            cm.sendOk("The sea has nothing more to teach you yet, #h #.\r\n\r\nWhen you reach #bLv. 120#k, speak with #bNeinheart#k - the Empress has a task that will decide whether you're fit to be named Chief Knight. Come back to me afterwards if the techniques don't take.");
            cm.dispose();
            return;
        }

        var missing = 0;
        for (var i = 0; i < SKILLS.length; i++) {
            if (cm.getPlayer().getMasterLevel(SKILLS[i][0]) < SKILLS[i][1]) {
                missing++;
            }
        }

        if (missing == 0) {
            cm.sendOk("Shinsoo's strength is already yours, #h #. Spend your SP and put it to use.");
            cm.dispose();
            return;
        }

        cm.sendYesNo("Something didn't take hold when you were named Chief Knight. Shall I open the rest of Shinsoo's techniques to you?");
    } else if (status == 1) {
        for (var i = 0; i < SKILLS.length; i++) {
            cm.teachSkill(SKILLS[i][0], 0, SKILLS[i][1], -1);
        }
        cm.sendOk("It's done. Your techniques are waiting in your skill window - they stay dormant until you spend SP on them.");
        cm.dispose();
    } else {
        cm.dispose();
    }
}
