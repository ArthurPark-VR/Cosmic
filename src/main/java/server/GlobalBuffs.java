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
package server;

import client.Character;
import client.Skill;
import client.SkillFactory;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a configured set of buffs active on every player, regardless of job or level.
 *
 * The effects are applied server-side rather than cast, which is what lets a non-GM receive
 * GM-only buffs: casting them would be rejected by the isGMSkills check in SpecialMoveHandler,
 * but applying the StatEffect directly never goes through that path.
 *
 * Buffs are applied on login and refreshed by GlobalBuffTask. See config.yaml.
 */
public class GlobalBuffs {
    private static final Logger log = LoggerFactory.getLogger(GlobalBuffs.class);

    private static volatile List<Skill> resolved;

    private GlobalBuffs() {
    }

    /**
     * Resolves the configured skill ids once, warning about any that do not exist.
     * Resolution is deferred until first use because the skill data is loaded after config.
     */
    private static List<Skill> getSkills() {
        List<Skill> cached = resolved;
        if (cached != null) {
            return cached;
        }

        synchronized (GlobalBuffs.class) {
            if (resolved != null) {
                return resolved;
            }

            List<Skill> skills = new ArrayList<>();
            List<String> configured = YamlConfig.config.server.GLOBAL_BUFF_SKILLS;
            if (configured != null) {
                for (String entry : configured) {
                    if (entry == null || entry.isBlank()) {
                        continue;
                    }

                    final int skillId;
                    try {
                        skillId = Integer.parseInt(entry.trim());
                    } catch (NumberFormatException e) {
                        log.warn("Ignoring global buff '{}' - not a skill id.", entry);
                        continue;
                    }

                    Skill skill = SkillFactory.getSkill(skillId);
                    if (skill == null || skill.getEffect(skill.getMaxLevel()) == null) {
                        // e.g. 9001008 has a name in String.wz but no data in Skill.wz
                        log.warn("Ignoring global buff {} - no skill data found for it.", skillId);
                        continue;
                    }

                    skills.add(skill);
                }
            }

            if (!skills.isEmpty()) {
                log.info("Global buffs active: {} skill(s), refreshed every {} min.", skills.size(),
                        YamlConfig.config.server.GLOBAL_BUFF_INTERVAL);
            }

            resolved = skills;
            return skills;
        }
    }

    public static boolean isEnabled() {
        return YamlConfig.config.server.USE_GLOBAL_BUFFS;
    }

    /**
     * Applies every configured buff to the given player at its max level.
     * Re-applying an active buff simply refreshes its duration.
     */
    public static void apply(Character chr) {
        if (!isEnabled() || chr == null || !chr.isLoggedinWorld() || !chr.isAlive()) {
            return;
        }

        for (Skill skill : getSkills()) {
            try {
                skill.getEffect(skill.getMaxLevel()).applyTo(chr);
            } catch (Exception e) {
                // A single bad buff must not stop the rest, nor break login.
                log.warn("Failed to apply global buff {} to {}.", skill.getId(), chr.getName(), e);
            }
        }
    }
}
