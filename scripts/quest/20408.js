/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

var status = -1;

function start(mode, type, selection) { // missing script for questid found thanks to Jade™
    if (mode == -1) {
        qm.dispose();
    } else {
        if (mode == 0 && type > 0) {
            qm.dispose();
            return;
        }

        if (mode == 1) {
            status++;
        } else {
            status--;
        }

        if (status == 0) {
            qm.sendNext("#h0#... First of all, thank you for your great work. If it weren't you, I... I wouldn't be safe from the curse of Black Witch. Thank you so much.");
        } else if (status == 1) {
            qm.sendNextPrev("If nothing else, this chain of events makes one thing crystal clear, you have put in countless hours of hard work to better yourself and contribute to the Cygnus Knights.");
        } else if (status == 2) {
            qm.sendAcceptDecline("To celebrate your hard work and accomplishments... I would like to award you a new title and renew my blessings onto you. Will you... accept this?");
        } else if (status == 3) {
            if (!qm.canHold(1142069, 1)) {
                qm.sendOk("Please, make a room available on your EQUIP inventory for the medal.");
                qm.dispose();
                return;
            }

            qm.gainItem(1142069, 1);
            if (qm.getJobId() % 10 == 1) {
                qm.changeJobById(qm.getJobId() + 1);

                // Thunder Breaker 4th job skills are custom to this server - v83 ships job 1512
                // with an empty skill list, so there is nothing to learn without this.
                // Master level is granted here because 4th job skills are capped by master level
                // and v83 has no mastery books for skills that never existed.
                // See docs/thunder-breaker-4th-job.md.
                if (qm.getJobId() == 1512) {
                    qm.teachSkill(15121000, 0, 30, -1);   // Maple Warrior
                    qm.teachSkill(15121001, 0, 30, -1);   // Sharp Eyes
                    qm.teachSkill(15121002, 0, 30, -1);   // Power Stance
                    qm.teachSkill(15121003, 0, 5, -1);    // Hero's Will
                    qm.teachSkill(15121004, 0, 20, -1);   // Flash Jump
                }
            }

            qm.forceStartQuest();
            qm.forceCompleteQuest();

            qm.sendOk("#h0#. For courageously battling the Black Mage, I will appoint you as the new Chief Knight of Cygnus Knights from this moment onwards. Please use your power and authority wisely to help protect the citizens of Maple World.");
        } else if (status == 4) {
            qm.dispose();
        }
    }
}
