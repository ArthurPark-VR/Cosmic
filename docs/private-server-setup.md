# Private server setup (single machine, Docker)

A step-by-step guide to running Cosmic privately on your own PC, where the server and the
client both live on the same machine and nothing is exposed to the internet.

The main [README](../README.md) covers the general setup and other ways to run the server
(IDE, standalone jar). This guide covers the Docker route only, end to end.

## What you end up with

| Piece | Where it runs | Reachable at |
| --- | --- | --- |
| Login server | Docker container | `127.0.0.1:8484` |
| Channels 1-3 (world 0) | Docker container | `127.0.0.1:7575-7577` |
| MySQL 8.4 database | Docker container | `127.0.0.1:3307` |
| Client (MapleStory.exe) | Windows, natively | connects to `127.0.0.1` |

All ports are bound to `127.0.0.1`, so the server accepts connections from your machine
only - not from your home network and not from the internet.

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/)
- [Git](https://git-scm.com/downloads)
- [Git LFS](https://git-lfs.com/) - **required**, see the warning below
- Windows, for the client

You do *not* need Java, Maven, or MySQL installed. Docker builds and runs all of that.

> [!WARNING]
> The client repository stores its `.wz` and `.exe` files in Git LFS. If you clone it
> without Git LFS installed, you get ~130-byte text placeholders instead of the real
> files, and nothing will work. Install Git LFS *before* cloning, or run `git lfs pull`
> inside an existing clone to fetch the real content. See
> [Step 3](#3---install-the-client) for how to check.

## 1 - Start the server

```sh
git clone https://github.com/P0nk/Cosmic.git
cd Cosmic
docker compose up
```

The first run takes a while: Docker builds the server image (downloading the Java
dependencies), starts MySQL, and then the server creates all ~75 database tables and
populates them. You do not need to create the schema by hand.

You are ready when you see:

```
server.Server - Listening on port 8484
server.Server - Cosmic is now online after ____ ms.
```

Leave that terminal open - the server runs in the foreground. `Ctrl+C` shuts it down.
To run it in the background instead, use `docker compose up -d`, and read the logs with
`docker compose logs -f maplestory`.

## 2 - Install the game client

Clone the client repository (with Git LFS installed):

```sh
git clone https://github.com/P0nk/Cosmic-client.git
```

Verify you got the real files, not LFS placeholders:

```sh
cd Cosmic-client
git lfs pull          # no-op if the files are already complete
```

`cosmic-wz/Character.wz` should be around 200 MB. If it is a few hundred *bytes*, Git LFS
did not run - install it and re-run `git lfs pull`.

## 3 - Install the client

> [!IMPORTANT]
> Windows Security flags the client as a virus and will silently delete it. Before you
> start, add your *Downloads* folder and the MapleStory install folder as *Exclusions*
> under *Virus & threat protection settings*. This happens because the client has been
> modified for local play; see the client repo's README for the details.

1. Run `MapleGlobal-v83-setup.exe` and install it (default location is
   `C:\Nexon\MapleStory`).
2. Delete these from the install directory:
   - the entire `HShield` folder
   - `ASPLnchr.exe`
   - `MapleStory.exe`
   - `Patcher.exe`
3. Copy everything from `cosmic-wz` into the install directory, replacing the existing
   `.wz` files.
4. Copy `HeavenMS-localhost-WINDOW.exe` into the install directory.

Because the server is on the same machine, the client works as shipped - it already
points at `127.0.0.1`. **No hex editing is needed.** (That step in the client README only
applies when the server lives on a different machine.)

## 4 - Log in

Start the server first, then double-click the client.

The built-in admin account:

| Field | Value |
| --- | --- |
| Username | `admin` |
| Password | `admin` |
| PIN | `0000` |
| PIC | `000000` |

You can also just type any new username and password at the login screen and it will be
registered automatically - that is `AUTOMATIC_REGISTER` in `config.yaml`, on by default.

The admin character logs in with hide mode enabled, so it looks almost invisible and mobs
will not move. Type `@hide` in chat to toggle it off. `@commands` lists everything else.

## GM character and Blessing of the Fairy

The `admin` account already ships with what you need: a character named **Admin** at
**GM level 6** (the highest tier), holding 1,000,000 NX credit and 1,000,000 maple points.
So you do not have to create a GM character - you already have one.

Useful commands once logged in as a GM (`@commands` lists them all):

| Command | Does |
| --- | --- |
| `@item <id> <qty>` | spawn any item |
| `@level <n>` / `@levelpro <n>` | set level |
| `@job <id>` | change job |
| `@maxstat`, `@maxskill` | max stats / skills |
| `@buff`, `@heal` | self buffs and healing |
| `@gmshop` | open the GM shop |
| `!setgmlevel <name> <lvl>` | make another character a GM (level 0-6) |

### Blessing of the Fairy

This is the part worth understanding, because it is not what most people assume. The
server computes it as (`Character.java`):

```sql
SELECT name, level FROM characters WHERE accountid = ? AND id != ? ORDER BY level DESC limit 1
```

It is simply **the highest-level *other* character on the same account**. Two consequences:

- It does **not** need to be a GM character. Any second character on the account counts -
  GM status just makes it fast to level one with `@level`.
- The value is read **once, when the character loads**. After levelling your other
  character, you must **log the first one out and back in** before the buff updates.

So the setup you want is: level the `Admin` character up, then log in on your Thunder
Breaker and it will carry Blessing of the Fairy from it.

## Day-to-day

```sh
docker compose up              # start (foreground)
docker compose up -d           # start (background)
docker compose down            # stop
docker compose logs -f maplestory   # follow server logs
docker compose up --build      # rebuild after changing Java code
```

`config.yaml`, `scripts/`, and `wz/` are mounted into the container rather than baked into
the image, so editing them only needs a restart, not a rebuild:

```sh
docker compose restart maplestory
```

Changing Java source *does* require `--build`.

## Tweaking the server

Rates live at the top of `config.yaml`, under the world you are playing on (world 0 is
"Scania", the default). They are multipliers - `5` means 5x:

```yaml
  - flag: 0
    server_message: Welcome to Scania!
    channels: 3
    exp_rate: 5
    meso_rate: 3
    drop_rate: 3
    boss_drop_rate: 3
```

`boss_drop_rate` replaces `drop_rate` for bosses rather than stacking with it, so it is
kept in step with the drop rate above.

Restart the server afterwards. Note that `channels: 3` matches the `7575-7577` range
published in `docker-compose.yml` - if you raise the channel count, widen that range to
match, or the extra channels will not be reachable.

This server also lifts the Cygnus level 120 cap and adds a Thunder Breaker 4th job. That
one needs a matching edit to your client `.wz` files - see
[thunder-breaker-4th-job.md](thunder-breaker-4th-job.md).

### Global buffs

Every player is kept permanently buffed, whatever their job or level:

| Buff | Skill | Effect | Duration |
| --- | --- | --- | --- |
| Hyper Body | `9101008` | +60% max HP and MP | 15 min |
| Bless | `9101003` | +20 att, +20 magic att, +100 def, +100 magic def, +100 acc, +100 avoid | 15 min |
| Holy Symbol | `9101002` | +50% experience per kill | 15 min |
| Haste | `9101001` | +40 speed, +20 jump | 15 min |
| Power Stance | `1121002` | 90% knockback resistance | 5 min |
| Sharp Eyes | `3121002` | +critical rate and damage | 5 min |
| Maple Warrior | `5121000` | +15% to all stats | 15 min |

The first four are the GM versions, which are stronger than the player ones and work for
any class. The last three stand in for the Thunder Breaker 4th job skills, so the kit is
available without the job advancement or any client-side edit.

Those three deliberately use the **original** skill ids rather than the custom `15121xxx`
ones. The effects are identical, but the client already knows these, so their buff icons
render and nothing depends on the edited `Skill.wz`.

```yaml
    USE_GLOBAL_BUFFS: true
    GLOBAL_BUFF_INTERVAL: 3
    GLOBAL_BUFF_SKILLS:
      - 9101008
      - 9101003
      - 9101002
      - 9101001
      - 1121002
      - 3121002
      - 5121000
```

Add or remove skill ids to change the set - each is applied at its max level, and anything
that doesn't resolve is logged and skipped rather than breaking login. Set
`USE_GLOBAL_BUFFS: false` to turn the whole thing off.

> [!IMPORTANT]
> `GLOBAL_BUFF_INTERVAL` must stay comfortably below the **shortest** duration in the list,
> not the longest. Power Stance and Sharp Eyes last only 5 minutes, so the interval is 3.
> Leaving it at 5 would let them expire in the same moment the refresh fires.

The buffs are applied directly rather than cast, which is what lets a normal character
receive GM-only buffs - casting them would be rejected, but applying the effect never goes
through that check.

## The other players

The world comes up populated - roughly 1,900 characters wandering towns, grinding fields and
running Free Market shops. They are bots, and they are scenery: nothing about them is stored,
and they are different people every restart.

Except the ones you adopt.

### Talking to them

Say a bot's name in map chat and it answers as itself. What it says comes from a local language
model reading its actual game state - job, level, gear tier, where it is, what it is doing - so a
level 128 Night Lord in Sleepywood does not talk like a level 19 beginner loitering in Henesys,
and nothing is authored per bot.

While you are talking, that bot stops whatever routine it was running. Its scripted lines and its
wandering are suspended; you have its attention. It goes back to normal when you say a parting
word ("later", "go on", "thanks"), after three minutes of silence, or when you leave the map.

Only one bot at a time listens to you. Saying a different bot's name moves the conversation to
them.

The bots whose chat menu *is* the feature - the gacha machine, the blackjack dealer, the shops,
the OPQ runners - keep their scripted menus and are never taken over.

### Telling them what to do

Ordinary words work, and they work whether or not the language model is running:

| Say | What happens |
| --- | --- |
| "follow me" | tails you everywhere, across maps and through portals |
| "attack" / "help me fight" | fights alongside you, and follows so it can |
| "stop attacking" | stops fighting, keeps following |
| "stay here" / "stop following" | stops following and settles where it stands |
| "party up" | joins your party (make one first - it will not create one for you) |
| "join my guild" | joins your guild, **and becomes permanent** - see below |
| "come here" | warps to you, however far away it is |

Two things to know before you take one to a boss. A companion's damage is **cosmetic** - the
numbers are scaled off the mob and are for feel, not a real contribution to the kill, so a
companion is company rather than a second damage dealer. And it cannot die: bots take contact
damage as a visual (knockback, damage numbers, the hurt pose) but it never touches their HP.

"come here" is the one that matters for bossing. A follower normally walks to you the way a
player would, and there is no walking route into a boss interior - those are entered through an
NPC or a scripted door. "Come here" skips the journey. A companion already following you does
this by itself: if it cannot find a route to your map, it warps in after about fifteen seconds.

### Companions

Inviting a bot into your guild adopts it. From then on it is the same character every restart -
same name, same face, same class, same level, still in your guild - while every other bot in the
world is regenerated from scratch.

`BOT_COMPANION_LIMIT` in `config.yaml` caps both how many you can adopt and how many can follow
you at once. It defaults to 6, and small is deliberate: followers tick fast and are exempt from
the server's load governor, which is right for a companion standing next to you and expensive for
a crowd.

Companions are stored in the `bot_companions` table, not in `characters`. They are not real
accounts and cannot be logged into - keeping them out of `characters` keeps them out of the
rankings, the character-select list and the deletion flow.

You will rarely need these - chat is the interface - but there are GM commands too. `!bot
companions` prints each companion's character id, which is the `<id>` the others take, and `!bot
help` lists everything:

```
!bot companions          list your companions
!bot adopt <id>          adopt without the guild step
!bot dismiss <id>        drop a companion back to being scenery
!bot fight <id>          order it to fight alongside you
!bot holdfire <id>       order it to stop
!bot comehere <id>       warp it to you
!bot followbot <id>      make it follow you
```

### How many bots

`BOT_POPULATION_SCALE` in `config.yaml` scales the wandering cohorts - town presence and the
training fields. `0.5` is half of what SoloMapling intends.

The Free Market is deliberately **not** scaled by it. The shops are the point of the FM, and a
half-stocked market reads as a dead server in a way a quiet Henesys does not, so it stays at full
population no matter how far the rest of the world is dialled down. Expect it to be the densest
place on the server; the startup log prints one line per room so you can see it.

Walking into a busy town for the first time after a restart is heavy - the client has to take in
hundreds of avatars at once. That cost is paid once, not continuously.

## Your save data

Everything - accounts, characters, inventory - lives in MySQL, stored on disk at
`database/docker-db-data/`. It is gitignored and survives `docker compose down`.

To back it up, stop the server and copy that folder, or dump it:

```sh
docker compose exec db mysqldump -uroot cosmic > backup.sql
```

`docker compose down -v` and deleting `database/docker-db-data/` both **erase your
characters permanently**. The schema is recreated empty on the next start.

You can also point a database client (HeidiSQL, DBeaver) at `localhost:3307`, user `root`,
empty password, to browse the data directly.

## Troubleshooting

**"We are unable to connect to the login server"** - the server is not running, or it is
still starting. Wait for "Cosmic is now online", then launch the client.

**The client will not start, or dies immediately** - this is a known quirk of the client;
try double-clicking it a few times in a row. Also confirm Windows Security has not
quarantined it.

**"port is already allocated"** - something else on your PC is using 8484, 7575-7577, or
3307. Stop it, or change the left-hand side of the port mappings in `docker-compose.yml`
(note that the client expects the login server on 8484 specifically).

**The server can't reach the database** - Compose waits for MySQL's healthcheck before
starting the server, so this should be rare. If it happens, `docker compose down` and up
again. On a very slow first run, MySQL may exceed the healthcheck's `start_period`; raise
it in `docker-compose.yml`.

**Wiping and starting fresh** - `docker compose down`, delete `database/docker-db-data/`,
then `docker compose up`.

## Playing with friends

Nothing here is required for solo play. This is the whole picture for letting a handful of
people in, without putting the server on the open internet.

### Pick how they reach you

**A private network (recommended).** Install [Tailscale](https://tailscale.com/) (or
ZeroTier) on your machine and on each friend's. Everyone gets a stable private address, and
your server is reachable only by people you've invited. No router configuration, no ports
open to the world, and nothing to re-do when your home IP changes. For a group of friends
this is strictly better than the alternative.

**Port forwarding.** Forward 8484 and 7575-7577 on your router to your machine and hand out
your public IP. This genuinely exposes the login server to the internet - anyone who finds
the port can reach the login screen. If you go this way, do the hardening below properly,
and expect to redistribute clients whenever your IP changes.

### What to change on the server

1. Drop the `127.0.0.1:` prefixes from the game port mappings in `docker-compose.yml` so
   they listen on all interfaces:

   ```yaml
         - "8484:8484"
         - "7575-7577:7575-7577"
   ```

   Leave the database mapping alone, or better, delete it - see hardening.

2. Set the address in `config.yaml`. Which of the three the server uses depends on where
   the player is connecting from:

   | Player is... | Server sees | Uses |
   | --- | --- | --- |
   | you, through Docker | `172.x` | `LANHOST` |
   | a friend on your home wifi | `192.168.x` / `10.x` | `LANHOST` |
   | a friend over VPN | `100.x` | `HOST` |
   | a friend over the internet | public ip | `HOST` |

   The simplest correct answer is to set **all three to the same address** - your Tailscale
   address, or your public IP. Your own client reaches it fine either way.

   ```yaml
       HOST: 100.x.x.x
       LANHOST: 100.x.x.x
       LOCALHOST: 100.x.x.x
   ```

3. Restart. `docker compose down && docker compose up`.

### What to give your friends

Each person needs the client, the `cosmic-wz` files, and a client **hex-edited to point at
your address** - see "Edit client ip" in the client repo's README. Easiest is to edit one
copy yourself and hand out that exact file, so nobody has to touch a hex editor.

### Hardening before anyone else connects

- **Database password.** `DB_PASS` in `config.yaml` and `MYSQL_ROOT_PASSWORD` in
  `docker-compose.yml`. Empty is only defensible while nothing outside your machine can
  reach it. Also delete the `3307` port mapping entirely - you can still reach the database
  with `docker compose exec db mysql -uroot -p cosmic`.
- **`AUTOMATIC_REGISTER`.** On by default: anyone who reaches the login screen creates an
  account by typing a new username. Behind a VPN that is fine. Exposed to the internet it
  is not - turn it off and create accounts yourself.
- **`MINIMUM_GM_LEVEL`.** Currently `1`, so *every* character becomes a Donator with
  `@goto`, `@buffme`, `@whodrops` and `@whatdropsfrom`. Harmless among friends, but decide
  deliberately rather than by accident. Set to `0` to keep it to yourself.
- **Back up first.** Everything lives in `database/docker-db-data`. More players means more
  to lose.

### Things that will apply to everyone

Your rates (5x/3x/3x) and the global buffs are server-wide, so your friends get them too.
`CHANNEL_LOAD` is 100 per channel across 3 channels, which is far beyond anything a group
of friends will need.

The upstream project explicitly does not support public servers, and you would be doing
this at your own risk. For an invite-only group over a private network, that risk is mostly
about your own machine rather than anyone else's.
