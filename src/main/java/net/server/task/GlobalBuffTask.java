/*
    This file is part of the Cosmic MapleStory Server

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
package net.server.task;

import net.server.world.World;

/**
 * Refreshes the configured global buffs on every online player, so they never lapse.
 */
public class GlobalBuffTask extends BaseTask implements Runnable {

    @Override
    public void run() {
        wserv.runGlobalBuffSchedule();
    }

    public GlobalBuffTask(World world) {
        super(world);
    }
}
