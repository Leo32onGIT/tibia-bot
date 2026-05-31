# Violent Bot
This branch is intended for hosting dedicated instances of Violent Bot.    
You can run this locally `free/m` or on a vps for `vps hosting cost/m`

# Patreon
Join Patreon as a **paid supporter** and I will send you an invite link for the bot I am running myself.    
This is the best option if you are non-technical and simply wish to use Violent Bot.

Production:
- [Website](https://violentbot.xyz)
- [Discord](https://discord.gg/PNnzzs4hN3)
- [Patreon](https://www.patreon.com/violentbot)
   
Current features include:    
- Online List
- Levels List
- Deaths List
- Activity Feed
- Server Save Notifications
- Command Log

## Architecture

The code is organised into focused packages under `com.tibiabot` rather than a few
god-objects. The top-level entry points stay thin:

- `BotApp` — application state and orchestration (wires the collaborators below).
- `BotListener` — a thin JDA event dispatcher; routes each event to a handler.
- `TibiaBot` — the per-world Akka stream that polls TibiaData and detects deaths/levels.

Supporting packages:

| Package | Responsibility |
| --- | --- |
| `app/` | Startup wiring — `Bootstrap` (JDA session) and `StreamSupervisor` (per-world stream lifecycle). |
| `commands/` | Slash-command schemas, `CommandRouter`, `Permissions`; `commands/handlers/` has one object per command. |
| `interactions/` | Button, modal and message (screenshot-upload) interaction handlers. |
| `discord/` | `DiscordGateway` (the JDA read seam) and `RateLimitedSender` (outbound message queue). |
| `persistence/` | Repository ports + `ConnectionProvider`/`SchemaInitializer`; JDBC/Postgres impls in `persistence/jdbc/`. All JDBC access goes through `JdbcSupport.withConnection`, which releases the connection even when a statement throws, so errors can't leak connections under concurrent load. |
| `presentation/` | Pure embed/message builders (deaths, online list, boosted, galthen). |
| `scheduler/` | Server-save schedule decisions (window, Rashid location, Drome countdown). |
| `tracking/` | Death/level/online dedup state and masslog detection. |
| `tibiadata/` | TibiaData v4 API client; response models in `tibiadata/response/`. |
| `wiki/` | Fandom wiki client and HTML parser. |
| `domain/` | Core case classes; game-time cycles in `domain/time/`. |
| `galthen/`, `boosted/`, `admin/` | Feature services extracted from `BotApp`. |

```mermaid
flowchart TB
    Discord([Discord])

    subgraph entry [Entry points]
        BL["BotListener — thin event dispatcher"]
        BA["BotApp — shared state + orchestration"]
        TB["TibiaBot — per-world stream"]
    end

    subgraph layer [commands + interactions]
        RT[CommandRouter]
        HD["handlers — one per slash command"]
        IX["Button / Modal / Screenshot handlers"]
    end

    subgraph svc [feature services]
        FS["galthen · boosted · admin"]
    end

    subgraph infra [infrastructure]
        GW[DiscordGateway]
        SN[RateLimitedSender]
        ST["state/StreamState"]
        PR[presentation]
        TR[tracking]
        SC[scheduler]
    end

    subgraph data [data + external]
        RP["persistence repositories"]
        DB[(PostgreSQL)]
        TD[tibiadata client]
        WK[wiki client]
        EXT{{"TibiaData v4 / Fandom"}}
    end

    Discord --> BL
    BL --> RT --> HD --> BA
    BL --> IX --> BA
    BA --> FS --> RP
    BA --> ST
    BA --> RP --> DB
    HD --> PR
    SC --> BA
    TB --> TD --> EXT
    BA --> WK --> EXT
    TB --> ST
    TB --> TR
    TB --> PR
    TB --> SN --> GW --> Discord
```

**Concurrency:** one independent Akka stream per world (held by `StreamSupervisor`),
all sharing a single `ActorSystem`/dispatcher and HTTP pool. Each world ticks every
60s through a back-pressured `mapAsync(1)` pipeline with a `mapAsyncUnordered(32)`
fan-out for per-character lookups, and per-stage `Supervision.Resume` so a single bad
response never kills the stream. Per-world dedup state is isolated to each stream; the
state shared across worlds (`state/StreamState`) is read lock-free on `@volatile` fields
and mutated only through synchronized `modify*` helpers, so concurrent per-guild updates
never clobber each other.

```mermaid
flowchart TB
    subgraph sup ["app/StreamSupervisor — one Akka stream per world"]
        WA[world A]
        WB[world B]
        WN[world N]
    end

    subgraph pipe ["the pipeline each world runs independently — tick 60s, back-pressured"]
        direction LR
        T["Source.tick 60s"] --> GWp["getWorld<br/>mapAsync(1)"]
        GWp --> GC["getCharacterData<br/>mapAsyncUnordered(32)"]
        GC --> SDp["scanForDeaths<br/>mapAsync(1)"]
        SDp --> PDp["postToDiscord<br/>mapAsync(1)"]
    end

    WA --> T
    WB --> T
    WN --> T

    GC -->|HTTP per online character| API{{TibiaData v4 API}}
    SDp <-->|"@volatile read · synchronized modify*"| ST[("state/StreamState")]
    PDp --> SN["RateLimitedSender<br/>per-world queue"] --> JDA["JDA global rate limiter"] --> D([Discord])

    WA -.->|run concurrently on| AS[/"shared ActorSystem dispatcher + akka-http pool"/]
    WB -.-> AS
    WN -.-> AS
```

The N world streams run concurrently on the shared dispatcher and HTTP pool; the only
points they contend on are `StreamState` (serialised writes) and the JDA rate limiter
(outbound sends). Startup staggers stream launches by ~5.5s so they don't all poll at once.

## Building & Testing

The project targets Java 8 and builds with sbt. If you don't have a JDK 8 / sbt
toolchain locally, build and test in Docker:

```bash
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/cache \
  -v "$HOME/.cache/tibiabot-build:/cache" -v "$PWD:/work" -w /work/tibia-bot \
  sbtscala/scala-sbt:eclipse-temurin-8u352-b08_1.8.2_2.13.10 sbt -batch test
```

Tests are hermetic by default:

- **Unit tests** cover the pure logic (routing, permissions, embed builders, trackers,
  schedule decisions, the rate-limited sender).
- **Decoder tests** parse frozen real TibiaData `/v4` responses
  (`src/test/resources/tibiadata/`) with the production JSON formats, locking the API contract.
- **Postgres integration tests** self-cancel unless a database is provided; to run them,
  add `--network <pg-net> -e PGHOST=<host> -e PGPASSWORD=<pw>` to the command above.

## Pre-requisites:

#### Create the new bot in Discord
1. Go to: https://discord.com/developers/applications and create a **New Application**.
2. Go to the **Bot** tab and click on **Add Bot**.
3. Click **Reset Token** & take note of the `Token` that is generated.

#### Custom Emojis and Poke Roles
The bot is configured to point to emojis in _my_ discord server.     
You will need to change this to point to your emojis.

1. Upload the emojis provided in the [discord emojis](https://github.com/Leo32onGIT/tibia-bot/tree/dedicated/tibia-bot/src/main/resources/discord%20emojis) folder to your discord.
2. Open the [discord.conf](https://github.com/Leo32onGIT/tibia-bot/blob/dedicated/tibia-bot/src/main/resources/discord.conf#L17-L60) file and edit it.
3. Point to `emoji ids` to ones that exist on _your_ discord server - the ones you uploaded in step 1.

#### Prepare your linux machine to host the bot
1. Ensure `docker` is installed.
1. Ensure `Java JDK 8` is installed.
1. Ensure `sbt` is installed.
3. Download the `postgres` docker image:    
`docker pull postgres`

## Deployment Steps

1. Clone the repository to your host machine.    
2. Compile the code into a docker image:    
`sbt docker:publishLocal`    
3. Take note of the docker \<image id\> you just created: `docker images`   
> ![docker image id](https://i.imgur.com/nXvSeIL.png)

4. Create a `prod.env` file with the discord server/channel id & bot authentication token:
> ```env
> TOKEN=XXXXXXXXXXXXXXXXXXXXXX   
> POSTGRES_HOST=sqlhost
> POSTGRES_PASSWORD=XXXXXXXXXX
> TIBIADATA_HOST=https://api.tibiadata.com/
> ```
5. Create the docker volume for the postgres database:    
`docker volume create --name pgdata`
6. Create the docker network for the `postgres database` and `violent bot` to communicate over:    
`docker network create violentbot`
6. Run the postgres docker image:    
`docker run --rm -d -t --env-file prod.env --hostname sqlhost --network=violentbot --name postgres -p 5432:5432 -v pgdata:/var/lib/postgresql postgres`
7. Run the docker container you just created & parse the **prod.env** file:     
`docker run --rm -d -t --env-file prod.env --network=violentbot --name violent-bot <image_id>`

## Debugging

1. If something isn't working correctly you should be able to see why very clearly in the logs.
2. Use `docker ps` to find the \<container id\> for the running bot.
3. Use `docker logs <container id>` to view the logs.
4. Use `docker pull dpage/pgadmin4` and `docker run -t --name pgadmin -p 0.0.0.0:82:80 --link postgres:postgres -e 'PGADMIN_DEFAULT_EMAIL=XXXXXXX@gmail.com' -e 'PGADMIN_DEFAULT_PASSWORD=XXXXXXXX' -d dpage/pgadmin4` if you need to visualise the postgres dbs
