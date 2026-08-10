# Thunder Breaker 4th job (custom)

This server lifts the level 120 cap on Cygnus Knights and adds a 4th job advancement for
Thunder Breaker, with a small custom skill set.

This is a **custom change**, not vanilla v83 behaviour. It also requires a matching edit to
your **client** `.wz` files - the server half alone is not enough.

## What v83 actually ships

Worth knowing before you change anything, because the naming is misleading:

- `Job.THUNDERBREAKER4` (job id **1512**) exists in the code, but only as a placeholder.
- `Skill.wz/1512.img` exists too - as a 268-byte stub with a job-tab icon and an
  **empty** `skill` node. The same is true of every Cygnus 4th job (1112, 1212, 1312, 1412).
- **No advancement leads to it.** The Thunder Breaker chain is quest `20105` → 1st job,
  `20205` → 2nd, `20315` → 3rd, and then it stops.

So v83 has a Cygnus 4th job in name only. Real Cygnus 4th job content arrived after
Big Bang, well past this version.

## The skills

None of the five requested skills exist for Thunder Breaker in v83, and two of them
(Power Stance, Hero's Will) have no pirate version at all. They are therefore **cloned**
from the closest existing skill, keeping the original level tables, MP costs, durations
and effect values verbatim:

| New skill | Id | Cloned from | Levels |
| --- | --- | --- | --- |
| Maple Warrior | `15121000` | `5121000` (Buccaneer) | 30 |
| Sharp Eyes | `15121001` | `3121002` (Bowmaster) | 30 |
| Power Stance | `15121002` | `1121002` (Hero) | 30 |
| Hero's Will | `15121003` | `1121011` (Hero) | 5 |
| Flash Jump | `15121004` | `14101004` (Night Walker) | 20 |

Maple Warrior comes from the Buccaneer since Thunder Breaker is the pirate branch, and
Flash Jump from the Night Walker since that is the Cygnus version.

Verified against the originals - the clones produce identical buff values at max level:
`MAPLE_WARRIOR:15`, `SHARP_EYES:3980`, `STANCE:90` (90% knockback resistance).

## Server-side changes (already applied)

- `Character.getMaxClassLevel()` returns 200 for every class, so Cygnus is no longer
  capped at 120. The exp table already had all 201 entries, so nothing else was needed.
- `GameConstants.getJobMaxLevel()` no longer special-cases Cygnus at 120.
- `constants/skills/ThunderBreaker.java` gained the five new skill ids.
- `StatEffect` learned the new ids in four places, so the buffs actually apply:
  the `STANCE`, `SHARP_EYES` and `MAPLE_WARRIOR` cases, plus `isHerosWill()`.
  Without these the skills would exist but do nothing.
- `SkillFactory` marks the four buff skills as buffs (Flash Jump is a movement skill and
  is deliberately excluded, matching how Hermit and Night Walker Flash Jump are handled).
- `wz/Skill.wz/1512.img.xml` now contains the five skill definitions.
- `wz/String.wz/Skill.img.xml` gained the five name/description entries.
- `scripts/npc/1101007.js` adds the advancement on Hawkeye.

## Client-side changes (you have to do these)

> [!IMPORTANT]
> Skipping this step means the skills will not appear in your skill window and cannot be
> assigned to a key. The server would accept them, but you would have no way to cast them.
> The client reads its own `.wz` files - it does not learn skills from the server.

The client needs the same five skills under `1512.img`, plus their names. Copying nodes in
HaRepacker carries the icon sprites across, which is why this is done in the editor rather
than by pasting the server's XML.

1. Install [HaRepacker-resurrected](https://github.com/lastbattle/Harepacker-resurrected).
2. Open your client's `Skill.wz` with encryption **GMS (old)**.
3. For each row in the table above:
   - Navigate to the source node, e.g. `512.img → skill → 5121000`.
   - Right-click it → **Copy**.
   - Navigate to `1512.img → skill`, right-click → **Paste**.
   - Rename the pasted node to the new id, e.g. `15121000`.
4. Open `String.wz → Skill.img` and do the same for the five entries, copying e.g. the
   `5121000` entry, renaming it to `15121000`, and setting its `name` string. The five
   names are Maple Warrior, Sharp Eyes, Power Stance, Hero's Will and Flash Jump.
5. Save both files, overwriting the ones in your MapleStory install directory.

The main README warns that node copy/paste in HaRepacker is occasionally unreliable - if a
skill shows up blank in game, re-copy that one node.

You do **not** need to re-export anything back to the server. The server side is already
done, and its XML was generated from these same source skills.

> [!NOTE]
> I could not verify the client half from here - it is a Windows GUI tool and the client
> `.wz` files in the Cosmic-client repo are Git LFS pointers. The server half is verified:
> the skills load, sit in job tree 1512, and produce buff values identical to the originals.

## Getting the advancement in game

Reach **Lv. 120** as a 3rd job Thunder Breaker (job 1511), then talk to **Hawkeye** on
Ereve - the same NPC who handled your 3rd job advancement.

He will advance you to job 1512 and grant **master level** on all five skills. That last
part matters: 4th job skills are normally capped by master level, which is raised with
mastery books, and v83 has no books for skills that did not exist. Granting master level at
the advancement stands in for them. The skills start at level 0, so you still have to spend
SP on them - you earn plenty going from 120 to 200.

Hawkeye's existing 3rd job advancement is unaffected; that runs through the quest window
(quest `20315`), not through talking to him.

To test without levelling, use a GM character: `@job 1512` works, or `@level 120` first.

## Reverting

Set `getMaxClassLevel()` back to `isCygnus() ? 120 : 200`, delete `scripts/npc/1101007.js`,
and empty the `skill` node in `wz/Skill.wz/1512.img.xml`. Characters already advanced to
1512 would need `@job 1511` to be put back into a valid state.
