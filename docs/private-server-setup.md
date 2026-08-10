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
"Scania", the default). They are multipliers - `10` means 10x:

```yaml
  - flag: 0
    server_message: Welcome to Scania!
    channels: 3
    exp_rate: 10
    meso_rate: 10
    drop_rate: 10
    boss_drop_rate: 10
```

Restart the server afterwards. Note that `channels: 3` matches the `7575-7577` range
published in `docker-compose.yml` - if you raise the channel count, widen that range to
match, or the extra channels will not be reachable.

This server also lifts the Cygnus level 120 cap and adds a Thunder Breaker 4th job. That
one needs a matching edit to your client `.wz` files - see
[thunder-breaker-4th-job.md](thunder-breaker-4th-job.md).

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

## Opening it up to other people later

This setup is deliberately loopback-only. If you later want friends to connect, you would
need to:

1. Remove the `127.0.0.1:` prefixes from the port mappings in `docker-compose.yml`.
2. Set `HOST` in `config.yaml` to your public IP (this is the address the login server
   hands back to clients when they pick a channel).
3. Forward ports 8484 and 7575-7577 on your router.
4. Hex-edit the client's IP for each person you give it to - see "Edit client ip" in the
   client repo's README.
5. Set a real database password (`DB_PASS` in `config.yaml`, `MYSQL_ROOT_PASSWORD` in
   `docker-compose.yml`) and stop publishing port 3307. The empty password is only
   acceptable while nothing outside your machine can reach it.

Consider whether you want `AUTOMATIC_REGISTER` left on at that point, since it lets anyone
who can reach the login screen create an account. The upstream project does not support
public servers - you would be doing this at your own risk.
