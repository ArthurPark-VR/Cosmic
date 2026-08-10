/* Author:          Cosmic (custom)
    NPC Name:       Hawkeye (1101007)
    Description:    Thunder Breaker 4th job advancement.

    v83 ships job 1512 (THUNDERBREAKER4) as an empty placeholder - the job id exists but
    Skill.wz/1512.img has no skills and no advancement path leads to it. This server adds
    the advancement and a small 4th job skill set. See docs/thunder-breaker-4th-job.md.

    Hawkeye already handles the 3rd job advancement through quest 20315; that quest is
    unaffected, since it is driven by the quest window rather than by talking to him.
*/

var status = -1;

var ADVANCE_LEVEL = 120;

// [skill id, master level] - master level matches the level count in Skill.wz/1512.img.
// Granting master level here stands in for the mastery books v83 has no items for.
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
        if (jobId == 1512) {
            cm.sendOk("You already carry the Empress' final blessing, #h #. Go and make use of it.");
            cm.dispose();
            return;
        }
        if (jobId != 1511) {
            cm.sendOk("The wind off the sea is restless today. Come speak with me once you've earned your place among the Advanced Knights.");
            cm.dispose();
            return;
        }
        if (cm.getPlayer().getLevel() < ADVANCE_LEVEL) {
            cm.sendOk("Not yet, #h #. Shinsoo's power will only answer to someone who has reached #bLv. " + ADVANCE_LEVEL + "#k. Keep training.");
            cm.dispose();
            return;
        }
        cm.sendYesNo("So the sea finally answered you. Shinsoo has agreed to share what remains of her strength - it will open techniques no Knight of Cygnus has carried before.\r\n\r\nAre you ready to take them on?");
    } else if (status == 1) {
        var Job = Java.type('client.Job');
        cm.getPlayer().changeJob(Job.THUNDERBREAKER4);

        for (var i = 0; i < SKILLS.length; i++) {
            cm.teachSkill(SKILLS[i][0], 0, SKILLS[i][1], -1);
        }

        cm.sendOk("It's done. Your limits are lifted - you may grow all the way to #bLv. 200#k now.\r\n\r\nYour new techniques are waiting in your skill window, but they're dormant until you spend SP on them. Go earn it.");
        cm.dispose();
    } else {
        cm.dispose();
    }
}
