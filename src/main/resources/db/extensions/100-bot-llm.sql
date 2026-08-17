-- Persistent identity for LLM-backed bots.
--
-- Written once when a bot is created and never regenerated: a bot that invents a new
-- favourite food every time it is asked reads worse than one with three canned lines.
-- Consistency across sessions is what makes a persona feel real, not model size.
CREATE TABLE bot_persona
(
    characterid  INT          NOT NULL,
    temperament  VARCHAR(64)  NOT NULL DEFAULT '',
    speech_style VARCHAR(255) NOT NULL DEFAULT '',
    backstory    TEXT,
    -- Free-form stable facts the model must answer consistently: favourite food,
    -- home town, opinions, quirks. JSON so new facts do not need a migration.
    traits       JSON,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (characterid),
    CONSTRAINT bot_persona_chr FOREIGN KEY (characterid) REFERENCES characters (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Rolling conversation log. Only the most recent few rows per (bot, counterparty) are fed
-- back as context - 8B-class models degrade with long context, so this is a short window,
-- not an archive. Prune on a schedule rather than letting it grow forever.
CREATE TABLE bot_memory
(
    id           INT          NOT NULL AUTO_INCREMENT,
    characterid  INT          NOT NULL,
    -- Character name of whoever the bot was talking to. Matches characters.name width.
    counterparty VARCHAR(13)  NOT NULL DEFAULT '',
    -- MAP | WHISPER | PARTY | BUDDY | GUILD | ALLIANCE | SPOUSE
    channel      VARCHAR(16)  NOT NULL DEFAULT 'MAP',
    -- 'them' or 'bot'
    speaker      VARCHAR(8)   NOT NULL DEFAULT 'them',
    content      VARCHAR(512) NOT NULL DEFAULT '',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY bot_memory_recent (characterid, counterparty, created_at),
    CONSTRAINT bot_memory_chr FOREIGN KEY (characterid) REFERENCES characters (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- What the bot is currently working towards. Gives the model something concrete to talk
-- about ("five more levels and I can take 4th job") and gives the server something to
-- advance - party exp already flows to bots through Monster.distributeExperience.
CREATE TABLE bot_goal
(
    characterid  INT          NOT NULL,
    -- LEVEL | JOB | GEAR | ITEM | IDLE
    goal_type    VARCHAR(32)  NOT NULL DEFAULT 'IDLE',
    -- Human-readable target for prompt context, e.g. 'reach 4th job' or 'Maple Sword'.
    target       VARCHAR(128) NOT NULL DEFAULT '',
    -- Numeric target where one applies (level number, item id). 0 when not applicable.
    target_value INT          NOT NULL DEFAULT 0,
    -- ACTIVE | DONE
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (characterid),
    CONSTRAINT bot_goal_chr FOREIGN KEY (characterid) REFERENCES characters (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
