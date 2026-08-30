package com.tibiabot

import akka.actor.{ActorSystem, Cancellable}
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source}
import akka.stream.{Attributes, Materializer, Supervision}
import com.tibiabot.BotApp.{alliedGuildsData, alliedPlayersData, discordsData, huntedGuildsData, huntedPlayersData, worldsData, activityData, customSortData, Players}
import com.tibiabot.tibiadata.{TibiaApi, TibiaDataClient}
import com.tibiabot.tibiadata.response.{CharacterResponse, Deaths, OnlinePlayers, WorldResponse}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.{ErrorHandler, ErrorResponseException}
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button

import java.time.ZonedDateTime
import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Random, Success}
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

//noinspection FieldFromDelayedInit
class TibiaBot(
  world: String,
  outboundSender: discord.RateLimitedSender,
  onlineListSender: discord.RateLimitedSender,
  worldMetrics: tracking.WorldMetrics,
  recentEvents: tracking.RecentEvents,
  paywallService: paywall.PaywallService
)(implicit system: ActorSystem, ex: ExecutionContextExecutor, mat: Materializer) extends StrictLogging {

  // A date-based "key" for a character, used to track recent deaths and recent online entries
  private case class CharKey(char: String, time: ZonedDateTime)
  private case class CharDeath(char: CharacterResponse, death: Deaths)
  private case class CharSort(guildName: String, allyGuild: Boolean, huntedGuild: Boolean, allyPlayer: Boolean, huntedPlayer: Boolean, vocation: String, level: Int, message: String)
  private case class OnlineListEntry(name: String, level: Int, lastUpdated: ZonedDateTime)

  private val recentDeaths = mutable.Set.empty[CharKey]
  private val levelTracker = new tracking.LevelTracker
  private val recentOnline = mutable.Set.empty[CharKey]
  private val onlineTracker = new tracking.OnlineTracker
  private val onlineDurationPersistence = new persistence.OnlineDurationPersistence(persistence.RedisCacheProvider.cache, world, Config.Cache.onlineDurationTtl)

  // Dedicated online list table for killer level lookups - updated every 5 minutes
  private val onlineListTable = mutable.Map.empty[String, OnlineListEntry]

  // Levels for killers the table above doesn't cover (other worlds, or someone
  // who logged in since it was last rebuilt) — see prefetchKillerLevels.
  private val killerLevelCache = new tracking.KillerLevelCache(Config.Cache.killerLevelTtl)
  // Modest on purpose: getKillerFallback hits the public api.tibiadata.com,
  // the same host each world's character poll already works at 32-way
  // concurrency, across every world this process runs.
  private val killerLevelConcurrency = 6
  private val killerLevelBatchCap = 40
  private val killerLevelBatchTimeout = 10.seconds

  // What the bot believes is currently posted in each online-list channel, so
  // the steady-state refresh needs no read of Discord at all — see
  // tracking.OnlineListState.
  private val onlineListState = new tracking.OnlineListState

  // Owned by the online-list sweep, which never overlaps itself — see
  // tracking.BountyPresence for why a login needs remembering rather than
  // reading off the roster.
  private val bountyPresence = new tracking.BountyPresence

  // initialize cached deaths/levels/transfers from database
  recentDeaths ++= BotApp.getDeathsCache(world).map(deathsCache => CharKey(deathsCache.name, ZonedDateTime.parse(deathsCache.time)))
  levelTracker.load(BotApp.getLevelsCache(world).map(levelsCache => tracking.LevelRecord(levelsCache.name, levelsCache.level.toInt, levelsCache.vocation, ZonedDateTime.parse(levelsCache.lastLogin), ZonedDateTime.parse(levelsCache.time))))
  BotApp.loadWorldTransfers(world)

  // Best-effort warm restore of online-duration state from a pre-restart
  // Redis snapshot (see OnlineDurationPersistence) — async, so it may race a
  // live poll; OnlineTracker.restore never clobbers an entry the live poll
  // already wrote.
  onlineDurationPersistence.load().foreach { snapshot =>
    if (snapshot.nonEmpty) {
      val restoreTime = ZonedDateTime.now()
      val entries = snapshot.map { case (name, s) =>
        tracking.OnlinePlayer(name, s.level, s.vocation, s.guildName, restoreTime, s.duration, s.flag)
      }
      onlineTracker.restore(entries, restoreTime)
      hasOnlineData = true
      logger.info(s"Warmed online-duration cache for world '$world' from Redis snapshot: ${entries.size} entries")
    }
  }

  // Set once this world has a roster worth rendering, from either a completed
  // poll or the Redis warm-restore. Written by the stream thread, read by the
  // online-list sweep on its own thread — see onlineListSweep.
  @volatile private var hasOnlineData = false

  private var onlineListTimer: Map[String, ZonedDateTime] = Map.empty
  // Seeded from bot_cache.rename_cooldowns so a channel renamed moments before
  // a restart isn't immediately eligible for another rename (see
  // renameOnlineCategoryIfDue/renameOnlineChannelIfDue).
  private var onlineListCategoryTimer: Map[String, ZonedDateTime] = BotApp.getRenameCooldowns(world)
  private var cacheListTimer: Map[String, ZonedDateTime] = Map.empty
  private var alliesListPurgeTimer: Map[String, ZonedDateTime] = Map.empty
  private var enemiesListPurgeTimer: Map[String, ZonedDateTime] = Map.empty
  private var neutralsListPurgeTimer: Map[String, ZonedDateTime] = Map.empty
  private var onlineListTableUpdateTimer: ZonedDateTime = ZonedDateTime.now().minusMinutes(10) // Start immediately
  // The refresh cadence currently in force, carried across sweeps so
  // AdaptiveRefreshInterval can apply hysteresis to it. 0 means "no previous
  // value", which makes the first sweep take the tier the depth asks for.
  // Read and written only from the online-list sweep's own thread, like the
  // purge timers above.
  private var onlineListRefreshSeconds: Int = 0

  // The implicit ExecutionContext CachingTibiaApi takes is the stream's own `ex`
  // (an ExecutionContextExecutor, so it wins implicit resolution) — an inner
  // `ExecutionContext.global` binding used to sit here but was never actually
  // selected, so it is gone rather than left looking meaningful.
  // One stack per world — see app.CharacterApiStack, which the primary's
  // fetch-only poller builds the identical arrangement from.
  private val tibiaDataClient: TibiaApi = app.CharacterApiStack.forWorld(TibiaBot.PollInterval)


  private val deathRecentDuration = 30 * 60 // 30 minutes for a death to count as recent enough to be worth notifying
  private val onlineRecentDuration = 10 * 60 // 10 minutes for a character to still be checked for deaths after logging off
  private val recentLevelExpiry = 25 * 60 * 60 // 25 hours before deleting recentLevel entry
  private val cooldowns = new ConcurrentHashMap[String, ZonedDateTime]()
  private val cooldownMinutes = 30L
  // Benign, operator-side failures (channel/message deleted, perms removed) —
  // ignore instead of letting JDA's default handler log an ERROR stack trace
  // for each one; other errors still log. UNKNOWN_MESSAGE matters especially
  // for the rate-limited lanes: an edit/rename is captured against a specific
  // message/channel at enqueue time but may not dispatch until well after
  // (queue depth + pace delay), so the target can legitimately be gone by
  // then — e.g. a later online-list update purging a now-excess message
  // before an earlier queued edit for that same message fires.
  private val ignoreDeletedTarget = new ErrorHandler()
    .ignore(ErrorResponse.UNKNOWN_CHANNEL, ErrorResponse.UNKNOWN_MESSAGE, ErrorResponse.MISSING_PERMISSIONS, ErrorResponse.MISSING_ACCESS)

  private val logAndResumeDecider: Supervision.Decider = { e =>
    logger.error("An exception has occurred in the TibiaBot:", e)
    Supervision.Resume
  }

  private val logAndResume: Attributes = supervisionStrategy(logAndResumeDecider)
  private lazy val sourceTick = Source.tick(TibiaBot.firstPollDelay(Random.nextInt), TibiaBot.PollInterval, ())
  private lazy val getWorld = Flow[Unit].mapAsync(1) { _ =>
    logger.info(s"Running stream for world: '$world'")
    tibiaDataClient.getWorld(world) // Pull all online characters
  }.withAttributes(logAndResume)

  private lazy val getCharacterData = Flow[Either[String, WorldResponse]].mapAsync(1) {
    case Right(worldResponse) =>
      val now = ZonedDateTime.now()
      val online: List[OnlinePlayers] = worldResponse.world.online_players.getOrElse(List.empty[OnlinePlayers])

      // get online data with durations (carries over guild/duration/flag, drops log-offs)
      onlineTracker.updateFromOnline(online.map(player => (player.name, player.level.toInt, player.vocation)), now)
      hasOnlineData = true
      val onlineWithVocLvlAndDuration = onlineTracker.snapshot
      // Best-effort, fire-and-forget: piggybacks on this world's existing poll
      // cadence instead of a separate schedule (see OnlineDurationPersistence).
      onlineDurationPersistence.save(onlineWithVocLvlAndDuration)
      // battleye_date is the literal string "release" for a world protected since
      // launch (green BattlEye); any actual date means protection was added later
      // (yellow BattlEye) — confirmed against the live TibiaData API, not documented.
      worldMetrics.recordPoll(
        onlineWithVocLvlAndDuration.size, now.toInstant, now.plusSeconds(60).toInstant,
        battleyeGreen_ = worldResponse.world.battleye_date == "release",
        pvpType_ = worldResponse.world.pvp_type
      )

      // Update online list table every 5 minutes for killer level lookups
      if (now.isAfter(onlineListTableUpdateTimer.plusMinutes(5))) {
        onlineListTable.clear()
        onlineWithVocLvlAndDuration.foreach { player =>
          onlineListTable.put(player.name.toLowerCase, OnlineListEntry(player.name, player.level, now))
        }
        onlineListTableUpdateTimer = now
      }

      // Remove existing online chars from the list...
      recentOnline.filterInPlace { i =>
        !online.exists(player => player.name == i.char)
      }
      recentOnline.addAll(online.map(player => CharKey(player.name, now)))

      fanOut(recentOnline.map(_.char).toSet)(tibiaDataClient.getCharacter)
    // World poll failed: fall back to re-checking whoever was last seen online.
    case Left(_) =>
      fanOut(recentOnline.map(_.char).toSet)(tibiaDataClient.getCharacter)
  }.withAttributes(logAndResume)

  /** Fetch every character in `inputs` at the shared 32-way concurrency and
   *  collect the responses. The shape every getCharacterData branch uses. */
  private def fanOut[A](inputs: Set[A])(fetch: A => Future[Either[String, CharacterResponse]]): Future[Set[Either[String, CharacterResponse]]] =
    Source(inputs)
      .mapAsyncUnordered(32)(fetch)
      .runWith(Sink.collection)
      .map(_.toSet)

  private lazy val scanForDeaths = Flow[Set[Either[String, CharacterResponse]]].mapAsync(1) { characterResponses =>
    val now = ZonedDateTime.now()

    // Level-ups are low priority and can arrive in bursts (many characters levelling
    // in the same tick) — buffered per channel here and flushed as one combined
    // message per channel below, instead of one message per level-up. Safe as a plain
    // local val: this whole flow stage runs at concurrency 1, single-threaded per tick.
    val levelUpBuffer = mutable.Map.empty[String, (TextChannel, ListBuffer[String])]

    val newDeaths = characterResponses.flatMap {
      case Right(char) =>
        val charName = char.character.character.name
        val guildName = char.character.character.guild.map(_.name).getOrElse("")

        val formerNamesList: List[String] = char.character.character.former_names.map(_.toList).getOrElse(Nil)
        val formerWorldsList: List[String] = char.character.character.former_worlds.map(_.toList).getOrElse(Nil)

        // Refresh the shared hunted/allied lookup cache at most once per 6 minutes
        // per world (gated per-world, not per-character).
        val cacheTimer = cacheListTimer.getOrElse(world, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
        if (ZonedDateTime.now().isAfter(cacheTimer.plusMinutes(6))) {
          val cacheWorld = char.character.character.world
          BotApp.huntedAlliedService.addListToCache(charName, formerNamesList, cacheWorld, formerWorldsList, guildName, char.character.character.level.toInt.toString, char.character.character.vocation, char.character.character.last_login.getOrElse(""), ZonedDateTime.now())
          cacheListTimer = cacheListTimer + (world -> ZonedDateTime.now())
        }

        // Incoming world transfer, detected once for the world rather than once
        // per discord watching it. The record lives in the shared bot_cache
        // beside deaths and levels, so every discord tracking this world shares
        // one answer to "have we seen this arrival already?", and one that adds
        // the world later inherits that answer instead of replaying every
        // former-world flag Tibia still has set — a backlog up to six months deep.
        //
        // Detecting and recording are deliberately unconditional, where posting
        // below is not: which discords announce an arrival depends on their own
        // filters, and if the record were only written when somebody announced it,
        // what the shared baseline held would depend on who happened to be
        // looking.
        //
        // The former-world field says somebody moved within about 180 days, never
        // when, so the first sweep of a world nobody has tracked before still
        // announces whoever moved at some point in that window, however long ago.
        // It settles into real arrivals once every character has been seen once.
        //
        // Matched on former names as well as the live one: the record is keyed by
        // whatever the character was called when it was written, so looking only
        // under the name they carry now reads a renamed character as a stranger
        // and posts their months-old transfer over again under the new name.
        val postedTransfer = presentation.WorldTransfers.postedFor(
          BotApp.worldTransfersData.getOrElse(world, List()), charName, formerNamesList)
        // A record still filed under a dropped name is moved onto the live one.
        // This is the only place that happens, and it has to be here rather than
        // in the rename branch below: that branch only fires for characters with
        // an activity row — members of a tracked guild — and an untracked arrival
        // has none.
        if (postedTransfer.exists(!_.name.equalsIgnoreCase(charName))) {
          BotApp.rekeyWorldTransfer(world, charName, formerNamesList)
        }
        val transferSources = presentation.WorldTransfers.unreported(
          char.character.character.world, world, formerWorldsList, postedTransfer.map(_.formerWorlds))
        transferSources.foreach { arrivedFrom =>
          BotApp.recordWorldTransfer(world, charName, arrivedFrom, ZonedDateTime.now())
        }

        // update the guildIcon depending on the discord this would be posted to
        if (discordsData.contains(world)) {
          val discordsList = discordsData(world)
          discordsList.foreach { discords =>
            val guildId = discords.id
            val blocker = BotApp.activityCommandBlocker.getOrElse(guildId, false)
            val allyGuildCheck = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(guildName))
            val huntedGuildCheck = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(guildName))

            val guildAlliedPlayers: List[Players] = alliedPlayersData.getOrElse(guildId, List())
            val guildHuntedPlayers: List[Players] = huntedPlayersData.getOrElse(guildId, List())
            val allyPlayerCheck = guildAlliedPlayers.exists(player =>
              player.name.equalsIgnoreCase(charName) ||
              formerNamesList.exists(formerName => formerName.equalsIgnoreCase(player.name))
            )
            val huntedPlayerCheck = guildHuntedPlayers.exists(player =>
              player.name.equalsIgnoreCase(charName) ||
              formerNamesList.exists(formerName => formerName.equalsIgnoreCase(player.name))
            )

            // add guild to online list cache
            onlineTracker.setGuild(charName, guildName)

            // Activity channel
            if (!blocker) {
              val guild = BotApp.discordGateway.guildById(discords.id)
              val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.equalsIgnoreCase(world))
              val activityChannel = worldData.headOption.map(_.activityChannel).getOrElse("0")
              val activityTextChannel = guild.getTextChannelById(activityChannel)
              val adminChannel = discords.adminChannel
              val charVocation = vocEmoji(char.character.character.vocation)
              val charLevel = char.character.character.level.toInt

              // Incoming world transfer, detected once for the world above.
              // Independent of the guild join/leave logic below, and posted first so
              // a character who transferred in and joined a tracked guild in the same
              // poll reads in the order it happened.
              //
              // Anyone tracked is announced at any level — that is the whole point of
              // tracking them. Everybody else has to clear the world's bar (see
              // WorldTransfers.UntrackedMinLevel), which keeps this from turning a
              // channel about hunted and allied players into a feed of every stranger
              // who moved house.
              val trackedHere = huntedGuildCheck || allyGuildCheck || huntedPlayerCheck || allyPlayerCheck
              val showNeutralActivity = worldData.headOption.map(_.showNeutralActivity).getOrElse("true")
              val notableStranger =
                showNeutralActivity == "true" && charLevel >= presentation.WorldTransfers.UntrackedMinLevel
              if (trackedHere || notableStranger) {
                transferSources.foreach { arrivedFrom =>
                  if (activityTextChannel != null) {
                    if (activityTextChannel.canTalk() || (!Config.prod)) {
                      val activityEmbed = new EmbedBuilder()
                      activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** transferred in from **${presentation.WorldTransfers.sourceText(arrivedFrom)}**.")
                      activityEmbed.setColor(
                        if (trackedHere) presentation.GuildActivity.activityColor(huntedGuildCheck || huntedPlayerCheck, allyGuildCheck || allyPlayerCheck)
                        else presentation.GuildActivity.untrackedColor)
                      // Arrow matches the colour: red for a hunted arrival, green for an
                      // allied one, grey for a stranger over the bar.
                      activityEmbed.setThumbnail(presentation.WorldTransfers.thumbnail(
                        huntedGuildCheck || huntedPlayerCheck, allyGuildCheck || allyPlayerCheck))
                      sendMessageWithRateLimit(activityTextChannel, "activity", embed = Some(activityEmbed))
                    }
                  }
                }
              }

              var skipJoinLeave = false

              val rename = presentation.GuildActivity.renameFromFormerNames(
                activityData.getOrElse(guildId, List()),
                charName,
                formerNamesList,
                formerName => onlineTracker.find(formerName).isDefined
              )

              rename.foreach { renamed =>
                val oldName = renamed.oldName
                val playerType = if (huntedPlayerCheck || huntedGuildCheck) 13773097 else if (allyPlayerCheck || allyGuildCheck) 36941 else 3092790
                val renamedAt = ZonedDateTime.now()
                // Whatever else happens, the character is not posted as joining or
                // leaving under their new name this poll — that would read as a
                // stranger appearing in the guild.
                skipJoinLeave = true
                // Six minutes for the character sheet to settle. A cache-bypassed
                // fetch can be served different cached copies of the same sheet from
                // one poll to the next, so a rename shows up, disappears and shows up
                // again; announcing on first sight spammed the activity channel.
                //
                // Moving the row is what makes the announcement one-shot — once it
                // carries the new name, renameFromFormerNames stops recognising the
                // character — so the move and the notice have to be the same decision.
                // Renaming the row while staying silent (what this did before) spent
                // the one chance to announce and posted nothing; the sheet could then
                // flip-flop freely and the rename was simply never reported. Holding
                // both back instead means the next poll re-detects it against the
                // untouched row and announces exactly once, just later.
                if (renamed.previousUpdate.plusMinutes(6).isBefore(renamedAt)) {
                  // The recorded guild is carried across as it stands rather than being
                  // caught up to the character's current one: a rename and a guild swap
                  // in the same poll are two events, and the swap is posted by the
                  // guild-change branch on the next poll. Storing the current guild here
                  // would agree with nothing in memory and would swallow the swap
                  // outright if the bot restarted before that poll.
                  var moved = false
                  BotApp.modifyActivityData { m =>
                    val live = m.getOrElse(guildId, List())
                    moved = live.exists(_.name.equalsIgnoreCase(oldName))
                    m + (guildId -> presentation.GuildActivity.applyRename(live, oldName, charName, formerNamesList, renamedAt))
                  }
                  // The row went while we were deciding. Announcing now would be
                  // announcing a move that did not happen, and nothing suppresses a
                  // repeat afterwards, so say nothing.
                  if (moved) {
                    BotApp.huntedAlliedService.updateActivityToDatabase(guild, oldName, formerNamesList, renamed.guild, renamedAt, charName)
                    // if player is in hunted or allied 'players' list, update information there too
                    if (huntedPlayerCheck) {
                      BotApp.huntedAlliedService.updateHuntedOrAllyNameToDatabase(guild, "hunted", oldName, charName)
                      val updatedHuntedPlayersData = huntedPlayersData.getOrElse(guildId, List()).map { player =>
                        if (player.name.equalsIgnoreCase(oldName)) {
                          player.copy(name = charName.toLowerCase)
                        } else {
                          player
                        }
                      }
                      BotApp.huntedAlliedService.modifyHuntedPlayersData(m => m + (guildId -> updatedHuntedPlayersData))
                    }
                    if (allyPlayerCheck) {
                      BotApp.huntedAlliedService.updateHuntedOrAllyNameToDatabase(guild, "allied", oldName, charName)
                      val updatedAlliedPlayersData = alliedPlayersData.getOrElse(guildId, List()).map { player =>
                        if (player.name.equalsIgnoreCase(oldName)) {
                          player.copy(name = charName.toLowerCase)
                        } else {
                          player
                        }
                      }
                      BotApp.huntedAlliedService.modifyAlliedPlayersData(m => m + (guildId -> updatedAlliedPlayersData))
                    }
                    if (activityTextChannel != null) {
                      if (activityTextChannel.canTalk() || (!Config.prod)) {
                        val activityEmbed = new EmbedBuilder()
                        activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$oldName](${charUrl(oldName)})** changed their name to **[$charName](${charUrl(charName)})**.")
                        activityEmbed.setColor(playerType)
                        activityEmbed.setThumbnail(Config.nameChangeThumbnail)
                        sendMessageWithRateLimit(activityTextChannel, "activity", embed = Some(activityEmbed))
                      }
                    }
                  }
                }
              }

              // Player hasn't changed their name
              if (!skipJoinLeave) {

                // Check charName
                val currentNameCheck = activityData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(charName))

                // Did they just join one the tracked guilds?
                var joinGuild = false
                if (!currentNameCheck) {
                  if (allyGuildCheck || huntedGuildCheck) {
                    joinGuild = true
                  }
                }

                // Player is already tracked
                if (currentNameCheck) {
                  val matchingActivityOption = activityData.getOrElse(guildId, List()).find(_.name.equalsIgnoreCase(charName))
                  val guildNameFromActivityData = matchingActivityOption.map(_.guild).getOrElse("")
                  val updatesTimeFromActivityData = matchingActivityOption.map(_.updatedTime).getOrElse(ZonedDateTime.parse("2022-01-01T01:00:00Z"))

                  if (updatesTimeFromActivityData.plusMinutes(6).isBefore(ZonedDateTime.now())) {

                    //charResponse.character.character.world
                    // Guild has changed
                    if (guildName != guildNameFromActivityData) {
                      val newGuildLess = if (guildName == "") true else false
                      val oldGuildLess = if (guildNameFromActivityData == "") true else false
                      val wasInHuntedGuild = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(guildNameFromActivityData))
                      val wasInAlliedGuild = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(guildNameFromActivityData))
                      // Left a tracked guild
                      if (wasInHuntedGuild || wasInAlliedGuild) {
                        val guildType = presentation.GuildActivity.guildType(wasInHuntedGuild, wasInAlliedGuild)
                        // No guild now
                        if (newGuildLess) {
                          if (activityTextChannel != null) {
                            if (activityTextChannel.canTalk() || (!Config.prod)) {
                              val activityEmbed = new EmbedBuilder()
                              activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** has left the **${guildType}** guild **[${guildNameFromActivityData}](${guildUrl(guildNameFromActivityData)})**.")
                              activityEmbed.setColor(14397256)
                              activityEmbed.setThumbnail(Config.guildLeaveThumbnail)
                              sendMessageWithRateLimit(activityTextChannel, "activity", embed = Some(activityEmbed))
                            }
                          }
                        } else { // Left a tracked guild, but joined a new one in the same turn
                          val colorType = presentation.GuildActivity.activityColor(huntedGuildCheck, allyGuildCheck)
                          if (activityTextChannel != null) {
                            if (activityTextChannel.canTalk() || (!Config.prod)) {
                              val activityEmbed = new EmbedBuilder()
                              val thumbnailType = colorType match {
                                case 13773097 => Config.guildSwapRed
                                case 36941 => Config.guildSwapGreen
                                case _ => Config.guildSwapGrey
                              }
                              activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** has left the **${guildType}** guild **[${guildNameFromActivityData}](${guildUrl(guildNameFromActivityData)})** and joined the guild **[${guildName}](${guildUrl(guildName)})**.")
                              activityEmbed.setColor(colorType)
                              activityEmbed.setThumbnail(thumbnailType)
                              sendMessageWithRateLimit(activityTextChannel, "activity", embed = Some(activityEmbed))
                            }
                          }
                          // remove from hunted list if in allied guild
                          if (allyGuildCheck) {
                            // Case-insensitive, like every other removal here: hunted
                            // names are always stored lowercased, while charName comes
                            // from the API properly capitalised, so an exact `==` never
                            // matched and left the in-memory list holding a player the
                            // database removal on the next line had already dropped.
                            BotApp.huntedAlliedService.modifyHuntedPlayersData(m => m.updated(guildId, m.getOrElse(guildId, List.empty).filterNot(_.name.equalsIgnoreCase(charName))))
                            BotApp.huntedAlliedService.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                            val adminTextChannel = guild.getTextChannelById(adminChannel)
                            if (adminTextChannel != null) {
                              if (adminTextChannel.canTalk() || (!Config.prod)) {
                                val commandUser = com.tibiabot.presentation.Names.user(BotApp.botUserName)
                                val adminEmbed = new EmbedBuilder()
                                adminEmbed.setTitle(":robot: enemy joined an allied guild:")
                                adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(they left a hunted guild & joined an allied one)*.")
                                adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                                adminEmbed.setColor(14397256) // orange for bot auto command
                                sendMessageWithRateLimit(adminTextChannel, "admin", embed = Some(adminEmbed), suppressNotifications = true)
                              }
                            }
                          }
                        }

                        // if he was in hunted guild add to hunted players list
                        if (wasInHuntedGuild) {
                          if (!allyGuildCheck && !huntedGuildCheck && !huntedPlayerCheck && !allyPlayerCheck) {
                            BotApp.huntedAlliedService.modifyHuntedPlayersData(m => m + (guildId -> (BotApp.Players(charName.toLowerCase(), "false", s"was originally in hunted guild ${guildNameFromActivityData}", BotApp.botUser) :: m.getOrElse(guildId, List()))))
                            BotApp.huntedAlliedService.addHuntedToDatabase(guild, "player", charName.toLowerCase(), "false", s"was originally in hunted guild ${guildNameFromActivityData}", BotApp.botUser)
                            val adminTextChannel = guild.getTextChannelById(adminChannel)
                            if (adminTextChannel != null) {
                              if (adminTextChannel.canTalk() || (!Config.prod)) {
                                val commandUser = com.tibiabot.presentation.Names.user(BotApp.botUserName)
                                val adminEmbed = new EmbedBuilder()
                                adminEmbed.setTitle(":robot: enemy automatically detected:")
                                adminEmbed.setDescription(s"$commandUser added the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nto the hunted list for **$world**\n*(they left a hunted guild, so they will remain hunted)*.")
                                adminEmbed.setThumbnail(creatureImageUrl("Stone_Coffin"))
                                adminEmbed.setColor(14397256) // orange for bot auto command
                                sendMessageWithRateLimit(adminTextChannel, "admin", embed = Some(adminEmbed), suppressNotifications = true)
                              }
                            }
                          }
                        } else if (wasInAlliedGuild){
                          if (!allyGuildCheck && !huntedGuildCheck && !huntedPlayerCheck && !allyPlayerCheck) {
                            // remove from activity
                            BotApp.modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(charName))))
                            BotApp.huntedAlliedService.removePlayerActivityfromDatabase(guild, charName.toLowerCase)
                          }
                        }
                      }

                      if (huntedPlayerCheck && oldGuildLess) {
                        val colorType = presentation.GuildActivity.activityColor(huntedGuildCheck, allyGuildCheck)
                        val guildType = presentation.GuildActivity.guildType(huntedGuildCheck, allyGuildCheck)
                        // joined a hunted guild
                        if (huntedGuildCheck) {
                          BotApp.huntedAlliedService.modifyHuntedPlayersData(m => m.updated(guildId, m.getOrElse(guildId, List.empty).filterNot(_.name.equalsIgnoreCase(charName))))
                          BotApp.huntedAlliedService.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                          val adminTextChannel = guild.getTextChannelById(adminChannel)
                          if (adminTextChannel != null) {
                            if (adminTextChannel.canTalk() || (!Config.prod)) {
                              val commandUser = com.tibiabot.presentation.Names.user(BotApp.botUserName)
                              val adminEmbed = new EmbedBuilder()
                              adminEmbed.setTitle(":robot: hunted list cleanup:")
                              adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(because they have joined an enemy guild and will be tracked that way)*.")
                              adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                              adminEmbed.setColor(14397256) // orange for bot auto command
                              sendMessageWithRateLimit(adminTextChannel, "admin", embed = Some(adminEmbed), suppressNotifications = true)
                            }
                          }
                        } else if (allyGuildCheck) {
                          BotApp.huntedAlliedService.modifyHuntedPlayersData(m => m.updated(guildId, m.getOrElse(guildId, List.empty).filterNot(_.name.equalsIgnoreCase(charName))))
                          BotApp.huntedAlliedService.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                          val adminTextChannel = guild.getTextChannelById(adminChannel)
                          if (adminTextChannel != null) {
                            if (adminTextChannel.canTalk() || (!Config.prod)) {
                              val commandUser = com.tibiabot.presentation.Names.user(BotApp.botUserName)
                              val adminEmbed = new EmbedBuilder()
                              adminEmbed.setTitle(":robot: hunted list cleanup:")
                              adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(because they have joined an allied guild and will be tracked that way)*.")
                              adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                              adminEmbed.setColor(14397256) // orange for bot auto command
                              sendMessageWithRateLimit(adminTextChannel, "admin", embed = Some(adminEmbed), suppressNotifications = true)
                            }
                          }
                        }
                        if (activityTextChannel != null) {
                          if (activityTextChannel.canTalk() || (!Config.prod)) {
                            val activityEmbed = new EmbedBuilder()
                            val thumbnailType = guildType match {
                              case "hunted" => Config.guildJoinRed
                              case "allied" => Config.guildJoinGreen
                              case _ => Config.guildJoinGrey
                            }
                            activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** joined the **${guildType}** guild **[${guildName}](${guildUrl(guildName)})**.")
                            activityEmbed.setColor(colorType)
                            activityEmbed.setThumbnail(thumbnailType)
                            sendMessageWithRateLimit(activityTextChannel, "activity", embed = Some(activityEmbed))
                          }
                        }
                      }

                      // Update in cache and db. Re-read the list inside the lock: a
                      // discord's activity list is shared by every world it tracks, and
                      // those streams poll concurrently — writing back a list built from
                      // an earlier read drops the other world's additions, and a player
                      // dropped from the list is posted as joining all over again.
                      val stamped = ZonedDateTime.now()
                      BotApp.modifyActivityData { m =>
                        val live = m.getOrElse(guildId, List())
                        val updated = live.find(_.name.equalsIgnoreCase(charName)).map(_.copy(guild = guildName, updatedTime = stamped))
                        m + (guildId -> (live.filterNot(_.name.equalsIgnoreCase(charName)) ++ updated))
                      }
                      BotApp.huntedAlliedService.updateActivityToDatabase(guild, charName, formerNamesList, guildName, stamped, charName)
                    }
                  }
                } else if (joinGuild) { // Character doesn't exist in tracking_activity but should be
                  // add to cache and db
                  val newActivity = BotApp.PlayerCache(charName, formerNamesList, guildName, ZonedDateTime.now())
                  BotApp.modifyActivityData { m =>
                    m + (guildId -> (newActivity :: m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(charName))))
                  }
                  BotApp.huntedAlliedService.addActivityToDatabase(guild, charName, formerNamesList, guildName, newActivity.updatedTime)
                  // joined a hunted guild
                  if (huntedGuildCheck) {
                    if (huntedPlayerCheck) { // was he originally in hunted 'player' list?
                      BotApp.huntedAlliedService.modifyHuntedPlayersData(m => m.updated(guildId, m.getOrElse(guildId, List.empty).filterNot(_.name.equalsIgnoreCase(charName))))
                      BotApp.huntedAlliedService.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                      val adminTextChannel = guild.getTextChannelById(adminChannel)
                      if (adminTextChannel != null) {
                        if (adminTextChannel.canTalk() || (!Config.prod)) {
                          val commandUser = com.tibiabot.presentation.Names.user(BotApp.botUserName)
                          val adminEmbed = new EmbedBuilder()
                          adminEmbed.setTitle(":robot: hunted list cleanup:")
                          adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(because they have joined an enemy guild and will be tracked that way)*.")
                          adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                          adminEmbed.setColor(14397256) // orange for bot auto command
                          sendMessageWithRateLimit(adminTextChannel, "admin", embed = Some(adminEmbed), suppressNotifications = true)
                        }
                      }
                    }
                  } else if (allyGuildCheck) { // joined an allied guild
                    if (allyPlayerCheck) {
                      // remove from allied 'Player' cache and db
                      BotApp.huntedAlliedService.modifyAlliedPlayersData(m => m.updated(guildId, m.getOrElse(guildId, List.empty).filterNot(_.name.equalsIgnoreCase(charName))))
                      BotApp.huntedAlliedService.removeAllyFromDatabase(guild, "player", charName.toLowerCase())
                      val adminTextChannel = guild.getTextChannelById(adminChannel)
                      if (adminTextChannel != null) {
                        if (adminTextChannel.canTalk() || (!Config.prod)) {
                          val commandUser = com.tibiabot.presentation.Names.user(BotApp.botUserName)
                          val adminEmbed = new EmbedBuilder()
                          adminEmbed.setTitle(":robot: allied list cleanup:")
                          adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the allied list for **$world**\n*(because they have joined an allied guild and will be tracked that way)*.")
                          adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                          adminEmbed.setColor(14397256) // orange for bot auto command
                          sendMessageWithRateLimit(adminTextChannel, "admin", embed = Some(adminEmbed), suppressNotifications = true)
                        }
                      }
                    }
                  }
                  val guildType = presentation.GuildActivity.guildType(huntedGuildCheck, allyGuildCheck)
                  val colorType = presentation.GuildActivity.activityColor(huntedGuildCheck, allyGuildCheck)
                  if (guildType != "neutral") { // ignore neutral guild changes, only show hunted/allied rejoins
                    if (activityTextChannel != null) {
                      if (activityTextChannel.canTalk() || (!Config.prod)) {
                        val activityEmbed = new EmbedBuilder()
                        val thumbnailType = guildType match {
                          case "hunted" => Config.guildJoinRed
                          case "allied" => Config.guildJoinGreen
                          case _ => Config.guildJoinGrey
                        }
                        activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** joined the **${guildType}** guild **[${guildName}](${guildUrl(guildName)})**.")
                        activityEmbed.setColor(colorType)
                        activityEmbed.setThumbnail(thumbnailType)
                        sendMessageWithRateLimit(activityTextChannel, "activity", embed = Some(activityEmbed))
                      }
                    }
                  }
                }

              }
              // end name change check
            }
          }
        }
        // detecting new levels
        val deaths: List[Deaths] = char.character.deaths.getOrElse(List.empty)
        val sheetLevel = char.character.character.level
        val sheetVocation = char.character.character.vocation
        val sheetLastLogin = ZonedDateTime.parse(char.character.character.last_login.getOrElse("2022-01-01T01:00:00Z"))
        var recentlyDied = false
        if (deaths.nonEmpty) {
          val mostRecentDeath = deaths.maxBy(death => ZonedDateTime.parse(death.time))
          val mostRecentDeathTime = ZonedDateTime.parse(mostRecentDeath.time)
          val mostRecentDeathAge = java.time.Duration.between(mostRecentDeathTime, now).getSeconds
          if (mostRecentDeathAge <= 600) {
            recentlyDied = true
          }
        }
        if (!recentlyDied) {
          onlineTracker.find(charName).foreach { onlinePlayer =>
            // level (i need to add logic here to batch messages control throughput a bit)
            if (onlinePlayer.level > sheetLevel) {
              val newLevelRecord = tracking.LevelRecord(charName, onlinePlayer.level, sheetVocation, sheetLastLogin, now)
              // post level to each discord
              if (discordsData.contains(world)) {
                val discordsList = discordsData(world)
                discordsList.foreach { discords =>
                  val guildId = discords.id
                  if (paywallService.isActive(guildId, world)) {
                  val guild = BotApp.discordGateway.guildById(discords.id)

                  // get appropriate guildIcon
                  val allyGuildCheck = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(guildName))
                  val huntedGuildCheck = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(guildName))
                  val allyPlayerCheck = alliedPlayersData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(charName))
                  val huntedPlayerCheck = huntedPlayersData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(charName))
                  val guildIcon = presentation.GuildIcons.guildIcon(guildName, allyGuildCheck, huntedGuildCheck, allyPlayerCheck, huntedPlayerCheck)
                  val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.equalsIgnoreCase(world))
                  val levelsChannel = worldData.headOption.map(_.levelsChannel).getOrElse("0")
                  val webhookMessage = s"${vocEmoji(onlinePlayer.vocation)} **[$charName](${charUrl(charName)})** advanced to level **${onlinePlayer.level}** $guildIcon"
                  val levelsTextChannel = guild.getTextChannelById(levelsChannel)
                  if (levelsTextChannel != null) {
                    if (levelsTextChannel.canTalk() || (!Config.prod)) {
                      // check show_neutrals_levels setting
                      val showNeutralLevels = worldData.headOption.map(_.showNeutralLevels).getOrElse("true")
                      val showAlliesLevels = worldData.headOption.map(_.showAlliesLevels).getOrElse("true")
                      val showEnemiesLevels = worldData.headOption.map(_.showEnemiesLevels).getOrElse("true")
                      val minimumLevel = worldData.headOption.map(_.levelsMin).getOrElse(20)
                      val enemyIcons = List(Config.enemy, Config.enemyGuild, s"${Config.otherGuild}${Config.enemy}")
                      val alliesIcons = List(Config.allyGuild, Config.ally, s"${Config.otherGuild}${Config.ally}")
                      val neutralIcons = List(Config.otherGuild, "")
                      // suppress the level-up for a category whose show-flag is off, or below the minimum level
                      val levelsCheck = presentation.LevelVisibility.shouldPost(
                        neutralIcons.contains(guildIcon), alliesIcons.contains(guildIcon), enemyIcons.contains(guildIcon),
                        showNeutralLevels, showAlliesLevels, showEnemiesLevels, onlinePlayer.level, minimumLevel)
                      if (levelTracker.shouldRecord(charName, onlinePlayer.level, sheetLastLogin)) {
                        if (levelsCheck) {
                          levelUpBuffer.getOrElseUpdate(levelsTextChannel.getId, (levelsTextChannel, ListBuffer.empty[String]))._2 += webhookMessage
                        }
                      }
                    }
                  }
                  }
                }
              }
              // add flag to onlineList if player has leveled
              onlineTracker.setFlag(charName, Config.levelUpEmoji)
              if (levelTracker.shouldRecord(charName, onlinePlayer.level, sheetLastLogin)) {
                levelTracker.record(newLevelRecord)
                BotApp.addLevelsCache(world, charName, onlinePlayer.level.toString, sheetVocation, sheetLastLogin.toString, now.toString)
              }
            }
          }
        }
        // parsing death info
        deaths.flatMap { death =>
          val deathTime = ZonedDateTime.parse(death.time)
          val deathAge = java.time.Duration.between(deathTime, now).getSeconds
          val charDeath = CharKey(char.character.character.name, deathTime)
          if (deathAge < deathRecentDuration && !recentDeaths.contains(charDeath)) {
            recentDeaths.add(charDeath)
            // First sight of this death, so deathAge is how far behind it we
            // were — the baseline any change to the poll schedule moves. A
            // world starting cold has no dedup history and briefly records
            // whatever it finds, up to deathRecentDuration old; the 15-minute
            // window this lands in clears that on its own.
            worldMetrics.recordDeathDetected(deathAge)
            BotApp.addDeathsCache(world, char.character.character.name, deathTime.toString)
            Some(CharDeath(char, death))
          }
          else None
        }
      case Left(_) => None
    }

    // Flush this tick's buffered level-ups: one combined message per channel instead
    // of one message per level-up, chunked to stay within Discord's message length limit.
    // One RecentEvents row per channel (not per character, and not combined across
    // channels) — the same world can be tracked by multiple discords, each with its
    // own levels channel, so a row per channel identifies which discord it went to
    // instead of silently merging separate discords' counts into one misleading total.
    levelUpBuffer.values.foreach { case (channel, lines) =>
      lines.foreach(_ => worldMetrics.incrementLevels())
      presentation.ListEmbeds.pack(lines.toList, 1900).foreach { chunk =>
        sendMessageWithRateLimit(channel, "level-up", message = chunk.stripPrefix("\n"))
      }
      val summary = if (lines.size == 1) "1 player leveled up" else s"${lines.size} players leveled up"
      val discordLabel = s"""<span class="muted" title="Discord ID: ${channel.getGuild.getId}">&middot; ${channel.getGuild.getName}</span>"""
      recentEvents.record("level-up", s"$summary $discordLabel")
    }

    // The online-list refresh used to run here, at the tail of this stage.
    // It now has its own schedule (see onlineListSweep) — rebuilding it means
    // sorting, grouping and rendering every online player for every discord
    // tracking this world, and doing that inside a mapAsync(1) stage put it on
    // the critical path for that tick's deaths and for the next poll behind
    // them, despite only actually firing every ~2 minutes per guild at best.

    Future.successful(newDeaths)
  }.withAttributes(logAndResume)

  /** One pass over every discord tracking this world, refreshing the online
   *  list for those whose own refresh interval has elapsed.
   *
   *  Runs on its own fixed-delay schedule rather than off the back of the world
   *  poll, so a slow or failing poll neither delays nor is delayed by the
   *  online list. The per-guild timer below (not the sweep interval) is what
   *  actually paces refreshes; the sweep just needs to tick often enough to
   *  notice a guild coming due.
   *
   *  Single-threaded by construction — scheduleWithFixedDelay never overlaps
   *  runs — which is what lets this and everything it calls own the online-list
   *  timers (onlineListTimer, the purge timers, onlineListCategoryTimer)
   *  without locking. */
  private def onlineListSweep(): Unit = {
    try {
      // Before the first poll (or Redis warm-restore) lands there is no roster
      // to render, and "nobody is online" would be a lie rather than a fact.
      if (hasOnlineData && discordsData.contains(world)) {
        // Only materialised if some guild is actually due — it copies the whole
        // roster, and most sweeps find nothing to do.
        lazy val roster = onlineTracker.snapshot
        // Back off when the shared online-list lane is congested instead of
        // always refreshing at the healthy cadence — see AdaptiveRefreshInterval
        // for the queueDepth -> interval mapping. Feeding the current cadence
        // back in is what gives that mapping its hysteresis, so a depth parked
        // on a tier boundary doesn't flap between two cadences.
        onlineListRefreshSeconds =
          discord.AdaptiveRefreshInterval.intervalSeconds(onlineListSender.queueDepth, onlineListRefreshSeconds)
        val refreshIntervalSeconds = onlineListRefreshSeconds

        // Bounty logins are checked every sweep, not on each guild's own refresh
        // timer: that timer paces *rendering* an online list and backs off when
        // the send lane is busy, which would quietly turn "they just logged in"
        // into "they logged in a few minutes ago" exactly when the world is at
        // its busiest. Presence itself is world-wide, so it is read once here and
        // the result fanned out below.
        bountyLoginSweep(roster)

        discordsData(world).foreach { discords =>
          val guildId = discords.id
          val onlineTimer = onlineListTimer.getOrElse(guildId, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
          if (paywallService.isActive(guildId, world) && ZonedDateTime.now().isAfter(onlineTimer.plusSeconds(refreshIntervalSeconds))) {
            val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.equalsIgnoreCase(world))
            val alliesChannel = worldData.headOption.map(_.alliesChannel).getOrElse("0")
            val neutralsChannel = worldData.headOption.map(_.neutralsChannel).getOrElse("0")
            val enemiesChannel = worldData.headOption.map(_.enemiesChannel).getOrElse("0")
            val categoryChannel = worldData.headOption.map(_.category).getOrElse("0")
            val onlineCombinedOption = worldData.headOption.map(_.onlineCombined).getOrElse("false")
            onlineListTimer = onlineListTimer + (guildId -> ZonedDateTime.now())
            onlineList(roster, guildId, alliesChannel, neutralsChannel, enemiesChannel, categoryChannel, onlineCombinedOption, world)
          }
        }
      }
    } catch {
      // Must never propagate: an escaping exception kills the schedule, and
      // with it every online list on this world until the next restart.
      case ex: Throwable => logger.error(s"Online list sweep failed for world '$world'", ex)
    }
  }

  /** Who anybody has a bounty on has just logged in on this world — one presence
   *  pass, then a DM per guild that was watching them.
   *
   *  Nothing is materialised when nobody is watching, which is the ordinary case:
   *  the roster runs to four figures and the whole point of asking here is that
   *  the answer is usually "no one".
   *
   *  A guild whose Patreon seat has lapsed is skipped, the same as its online
   *  list is — a paused world shouldn't keep messaging people privately. */
  private def bountyLoginSweep(roster: => List[tracking.OnlinePlayer]): Unit =
    try {
      val targets = BotApp.notifyService.bountyTargets(world)
      if (targets.nonEmpty) {
        val online = roster.iterator.map(player => player.name.toLowerCase -> player.duration).toMap
        val logins = bountyPresence.logins(targets, online)
        if (logins.nonEmpty) {
          val byName = roster.iterator.map(player => player.name.toLowerCase -> player).toMap
          discordsData(world).foreach { discords =>
            val guildId = discords.id
            if (paywallService.isActive(guildId, world)) {
              val guild = BotApp.discordGateway.guildById(guildId)
              val guildName = if (guild == null) "" else guild.getName
              logins.foreach { name =>
                byName.get(name).foreach { player =>
                  BotApp.notifyService.onBountyLogin(guildId, world, guildName, player.name, player.level, player.vocation)
                }
              }
            }
          }
        }
      }
    } catch {
      // Same rule as the sweep that calls this: a notification problem must not
      // take the online list down with it.
      case ex: Throwable => logger.warn(s"Bounty login sweep failed for world '$world'", ex)
    }

  private lazy val postToDiscordAndCleanUp = Flow[Set[CharDeath]].mapAsync(1) { charDeaths =>
    // post death to each discord
    if (discordsData.contains(world)) {
      val discordsList = discordsData(world)
      // Resolved once for the whole batch, before the per-discord loop below —
      // the names needed depend only on the deaths, not on which discord is
      // being posted to, so doing this inside the loop repeated every lookup
      // per discord. Skipped entirely when no discord tracks this world (a
      // primary polling a world only a secondary's guilds need).
      val killerLevelsAt = ZonedDateTime.now()
      if (discordsList.nonEmpty) prefetchKillerLevels(charDeaths, killerLevelsAt)
      discordsList.foreach { discords =>
        val guildId = discords.id
        if (paywallService.isActive(guildId, world)) {
        val guild = BotApp.discordGateway.guildById(discords.id)
        val adminChannel = discords.adminChannel
        val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.equalsIgnoreCase(world))
        val deathsChannel = worldData.headOption.map(_.deathsChannel).getOrElse("0")
        val nemesisRole = worldData.headOption.map(_.nemesisRole).getOrElse("0")
        val fullblessRole = worldData.headOption.map(_.fullblessRole).getOrElse("0")
        val allyHelpRole = worldData.headOption.map(_.allyPkRole).getOrElse("0")
        val exivaListCheck = worldData.headOption.map(_.exivaList).getOrElse("true")
        val deathsTextChannel = guild.getTextChannelById(deathsChannel)
        if (deathsTextChannel != null) {
          if (deathsTextChannel.canTalk() || (!Config.prod)) {
            val embeds = charDeaths.toList.sortBy(_.death.time).map { charDeath =>
              var notablePoke = ""
              val charName = charDeath.char.character.character.name
              val killer = charDeath.death.killers.lastOption.map(_.name).getOrElse("Invalid")
              var context = "Died"
              var embedColor = 3092790 // background default
              var embedThumbnail = presentation.DeathEffect.thumbnail(killer).getOrElse(creatureImageUrl(killer))
              var vowelCheck = "" // this is for adding "an" or "a" in front of creature names
              val killerBuffer = ListBuffer[String]()
              val exivaBuffer = ListBuffer[String]()
              var exivaList = ""
              val killerList = charDeath.death.killers // get all killers

              // guild rank and name
              val guildName = charDeath.char.character.character.guild.map(_.name).getOrElse("")
              val guildRank = charDeath.char.character.character.guild.map(_.rank).getOrElse("")
              var guildText = ""

              // guild
              // does player have guild?
              var guildIcon = Config.otherGuild
              var huntedGuilds = false
              var allyGuilds = false
              if (guildName != "") {
                // if untracked neutral guild show grey
                if (embedColor == 3092790) {
                  embedColor = 4540237
                }
                val customSortGuildCheck = customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "guild" && g.name.equalsIgnoreCase(guildName))
                if (customSortGuildCheck) {
                  embedColor = 14397256 // yellow
                }
                // is player an ally
                allyGuilds = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(guildName))
                if (allyGuilds) {
                  embedColor = 13773097 // bright red
                  guildIcon = Config.allyGuild
                }
                // is player in hunted guild
                huntedGuilds = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(guildName))
                if (huntedGuilds) {
                  embedColor = 36941 // bright green
                  if (context == "Died") {
                    notablePoke = "fullbless" // PVE fullbless opportuniy (only poke for level 400+)
                  }
                }
                guildText = s"$guildIcon *$guildRank* of the [$guildName](${guildUrl(guildName)})\n"
              }

              // player
              val customSortPlayerCheck = customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "player" && g.name.equalsIgnoreCase(charName))
              if (customSortPlayerCheck) {
                embedColor = 14397256 // yellow
              }
              // ally player
              val allyPlayers = alliedPlayersData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(charName))
              if (allyPlayers) {
                embedColor = 13773097 // bright red
              }
              // hunted player
              val huntedPlayers = huntedPlayersData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(charName))
              if (huntedPlayers) {
                embedColor = 36941 // bright green
                if (context == "Died") {
                  notablePoke = "fullbless" // PVE fullbless opportuniy
                }
              }

              // poke if killer is in notable-creatures config
              val poke = Config.notableCreatures.contains(killer.toLowerCase())
              if (poke) {
                notablePoke = "nemesis"
                embedColor = presentation.Embeds.NemesisPurple
              }

              if (killerList.nonEmpty) {
                killerList.foreach { k =>
                  if (k.player) {
                    if (k.name != charName) { // ignore 'self' entries on deathlist
                      context = "Killed"
                      if (allyPlayers || allyGuilds) {
                        notablePoke = "allypk"
                      } else if (huntedPlayers || huntedGuilds) {
                        notablePoke = "screenshot"
                      } else {
                        notablePoke = "" // reset poke as its not a fullbless
                      }
                      if (embedColor == 3092790 || embedColor == 4540237) {
                        embedColor = 14869218 // bone white
                      }
                      embedThumbnail = presentation.DeathEffect.pvp
                      domain.Killers.summonBehind(k.name, k.summon) match {
                        case Some((creature, summoner)) => // e.g: fire elemental of Violent Beams
                          val vowel = domain.Killers.article(creature)
                          val summonerLevelText = getKillerLevel(summoner, killerLevelsAt).map(level => s" [$level]").getOrElse("")
                          killerBuffer += s"$vowel ${Config.summonEmoji} **$creature of [$summoner$summonerLevelText](${charUrl(summoner)})**"
                          if (embedColor == 13773097) {
                            if (exivaListCheck == "true") {
                              exivaBuffer += summoner
                            }
                          }
                        case None => // a player (incl. names with " of " like "Knight of Flame") or an undetected summon
                          val levelText = getKillerLevel(k.name, killerLevelsAt).map(level => s" [$level]").getOrElse("")
                          killerBuffer += s"**[${k.name}$levelText](${charUrl(k.name)})**"
                          if (embedColor == 13773097) {
                            if (exivaListCheck == "true") {
                              exivaBuffer += k.name
                            }
                          }
                      }
                    }
                  } else {
                    // map boss lists to their respective emojis (built once in BossEmoji)
                    val bossIcon = presentation.BossEmoji.of(k.name)

                    // add "an" or "a" depending on first letter of creatures name
                    // ignore capitalized names (nouns) as they are bosses
                    // if player dies to a neutral source show 'died by energy' instead of 'died by an energy'
                    if (!k.name.exists(_.isUpper)) {
                      vowelCheck = domain.Killers.sourceArticle(k.name)
                    }
                    killerBuffer += s"$vowelCheck$bossIcon**${k.name}**"
                  }
                }
              }

              if (exivaBuffer.nonEmpty) {
                exivaBuffer.zipWithIndex.foreach { case (exiva, i) =>
                  if (i == 0) {
                    exivaList += s"""\n${Config.exivaEmoji} `exiva "$exiva"`""" // add exiva emoji
                  } else {
                    exivaList += s"""\n${Config.indentEmoji} `exiva "$exiva"`""" // just use indent emoji for further player names
                  }
                }

                // see if detectHunted is toggled on or off
                val detectHunteds = worldData.headOption.map(_.detectHunteds).getOrElse("on")
                if (detectHunteds == "on") {
                  // scan exiva list for enemies to be added to hunted
                  val exivaBufferFlow = Source(exivaBuffer.toSet).mapAsyncUnordered(16)(tibiaDataClient.getCharacter).toMat(Sink.seq)(Keep.right)
                  val futureResults: Future[Seq[Either[String, CharacterResponse]]] = exivaBufferFlow.run()
                  futureResults.onComplete {
                    case Success(output) =>
                      val huntedBuffer = ListBuffer[(String, String, String, Int)]()
                      output.foreach {
                        case Right(charResponse) =>
                          val killerName = charResponse.character.character.name
                          val killerGuild = charResponse.character.character.guild
                          val killerWorld = charResponse.character.character.world
                          val killerVocation = vocEmoji(charResponse.character.character.vocation)
                          val killerLevel = charResponse.character.character.level.toInt
                          val killerGuildName = if(killerGuild.isDefined) killerGuild.head.name else ""
                          var guildCheck = true
                          if (killerGuildName != "") {
                            if (alliedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(killerGuildName)) || huntedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(killerGuildName))) {
                              guildCheck = false // player guild is already ally/hunted
                            }
                          }
                          if (guildCheck) { // player is not in a guild or is in a guild that is not tracked
                            if (alliedPlayersData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(killerName)) || huntedPlayersData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(killerName))) {
                              // already tracked, nothing to do
                            } else {
                              if (!huntedBuffer.exists(_._1.equalsIgnoreCase(killerName))) {
                                huntedBuffer += ((killerName, killerWorld, killerVocation, killerLevel))
                              }
                            }
                          }
                        case Left(_) => // do nothing
                      }

                      // process the new batch of players to add to hunted list
                      if (huntedBuffer.nonEmpty) {
                        val adminTextChannel = guild.getTextChannelById(adminChannel)
                        if (adminTextChannel != null) {
                          huntedBuffer.foreach { case (player, world, vocation, level) =>
                            val playerString = player.toLowerCase()
                            BotApp.huntedAlliedService.modifyHuntedPlayersData(m => m + (guildId -> (BotApp.Players(playerString, "false", "killed an allied player", BotApp.botUser) :: m.getOrElse(guildId, List()))))
                            // add them to the database
                            BotApp.huntedAlliedService.addHuntedToDatabase(guild, "player", playerString, "false", "killed an allied player", BotApp.botUser)
                            val commandUser = com.tibiabot.presentation.Names.user(BotApp.botUserName)
                            val adminEmbed = new EmbedBuilder()
                            adminEmbed.setTitle(":robot: enemy automatically detected:")
                            adminEmbed.setDescription(s"$commandUser added the player\n$vocation **$level** — **[$player](${charUrl(player)})**\nto the hunted list for **$world**\n*(they killed the allied player **[${charName}](${charUrl(charName)})***.")
                            adminEmbed.setThumbnail(creatureImageUrl("Dark_Mage_Statue"))
                            adminEmbed.setColor(14397256) // orange for bot auto command
                            sendMessageWithRateLimit(adminTextChannel, "admin", embed = Some(adminEmbed), suppressNotifications = true)
                          }
                        }
                      }
                    case Failure(exception) =>
                      logger.warn(s"Failed to scan the exiva list for auto-hunt detection on world '$world': ${exception.getMessage}")
                  }
                }
              }

              // convert formatted killer list to one string ("a, b and c")
              var killerText = domain.Killers.joinNatural(killerBuffer.toSeq)

              // this should only occur to pure suicides on bomb runes, or pure 'assists' deaths in yellow-skull friendy fire or retro/hardcore situations
              if (killerText == "") {
                  embedThumbnail = presentation.DeathEffect.suicide
                  killerText = s"""`suicide`"""
              }

              val epochSecond = ZonedDateTime.parse(charDeath.death.time).toEpochSecond

              // this is the actual embed description
              var embedText = s"$guildText$context <t:$epochSecond:R> at level ${charDeath.death.level.toInt}\nby $killerText.$exivaList"

              // if the length is over 4065 truncate it
              val embedLength = embedText.length
              val limit = 4065
              if (embedLength > limit) {
                val newlineIndex = embedText.lastIndexOf('\n', limit)
                embedText = embedText.substring(0, newlineIndex) + "\n:scissors: `out of space`"
              }

              val showNeutralDeaths = worldData.headOption.map(_.showNeutralDeaths).getOrElse("true")
              val showAlliesDeaths = worldData.headOption.map(_.showAlliesDeaths).getOrElse("true")
              val showEnemiesDeaths = worldData.headOption.map(_.showEnemiesDeaths).getOrElse("true")
              val embedCheck = presentation.DeathEmbeds.shouldShow(embedColor, showNeutralDeaths, showAlliesDeaths, showEnemiesDeaths)
              val embed = presentation.DeathEmbeds.build(charName, charDeath.char.character.character.vocation, embedText, embedThumbnail, embedColor)

              // return embed + poke
              (embed, notablePoke, charName, embedText, charDeath.death.level.toInt, embedCheck, epochSecond, charDeath.char.character.character.vocation, killer)
            }
            val fullblessLevel = worldData.headOption.map(_.fullblessLevel).getOrElse(250)
            val minimumLevel = worldData.headOption.map(_.deathsMin).getOrElse(20)
            // Deaths are top priority — send immediately, no artificial pacing. JDA's
            // own rate limiter already queues/paces REST calls safely against Discord's
            // real limits; this used to add its own delay on top (up to ~25s for a
            // burst of 20), which only worked against the "post fast" goal without
            // buying any real additional protection.
            val validEmbeds = embeds.filter(_._6) // Filter only valid embeds
            def recordDeath(charName: String, level: Int, vocation: String, killer: String): Unit = {
              worldMetrics.incrementDeaths()
              // Plain Unicode here, not vocEmoji's Discord shortcode text (":shield:" etc.) —
              // Discord auto-renders shortcodes as emoji, but a browser won't. Real HTML
              // markup (not Discord markdown) since the dashboard injects this text as-is.
              val vocationEmoji = vocation.toLowerCase.split(' ').last match {
                case "knight"   => "🛡️"
                case "druid"    => "❄️"
                case "sorcerer" => "🔥"
                case "paladin"  => "🏹"
                case "monk"     => "👊🏽"
                case "none"     => "🐣"
                case _          => ""
              }
              val nameLink = s"""<a href="${charUrl(charName)}" target="_blank">$charName</a>"""
              // The same death posts once per discord tracking this world, so without
              // identifying which one, the feed shows what looks like duplicate rows —
              // this is the enclosing discordsList.foreach's discord, not a repeat.
              val discordLabel = s"""<span class="muted" title="Discord ID: $guildId">&middot; ${guild.getName}</span>"""
              recentEvents.record("death", s"$vocationEmoji $nameLink died at level $level by $killer $discordLabel")
            }
            validEmbeds.foreach { embed =>
              try {
                // Create screenshot button
                val screenshotButton = Button.secondary(
                  s"death_screenshot_${embed._3}_${embed._7}_placeholder",
                  "Add Screenshot"
                )
                val actionRow = ActionRow.of(screenshotButton)

                // nemesis and enemy fullbless ignore the level filter
                if (embed._2 == "nemesis") {
                  val shouldPing = guild.getRoleById(nemesisRole) != null && canPing(deathsTextChannel.getId)
                  if (shouldPing) {
                    deathsTextChannel.sendMessage(s"<@&$nemesisRole>")
                      .setEmbeds(embed._1.build())
                      .queue()
                  } else {
                    deathsTextChannel.sendMessageEmbeds(embed._1.build())
                      .queue()
                  }
                  recordDeath(embed._3, embed._5, embed._8, embed._9)
                } else if (embed._2 == "allypk") {
                  if (embed._5 >= minimumLevel) {
                    val shouldPing = guild.getRoleById(allyHelpRole) != null && canPing(deathsTextChannel.getId)
                    if (shouldPing) {
                      deathsTextChannel.sendMessage(s"<@&$allyHelpRole>")
                        .setEmbeds(embed._1.build())
                        .queue()
                    } else {
                      deathsTextChannel.sendMessageEmbeds(embed._1.build())
                        .queue()
                    }
                    recordDeath(embed._3, embed._5, embed._8, embed._9)
                  }
                } else if (embed._2 == "fullbless") {
                  if (embed._5 >= minimumLevel) {
                    // send adjusted embed for fullblesses
                    val adjustedMessage = embed._4 + s"""\n${Config.exivaEmoji} `exiva "${embed._3}"`"""
                    val adjustedEmbed = embed._1.setDescription(adjustedMessage)
                    if (embed._5 >= fullblessLevel && guild.getRoleById(fullblessRole) != null) { // only poke for 250+
                      deathsTextChannel.sendMessage(s"<@&$fullblessRole>")
                        .setEmbeds(adjustedEmbed.build())
                        .queue()
                    } else {
                      deathsTextChannel.sendMessageEmbeds(adjustedEmbed.build())
                        .queue()
                    }
                    recordDeath(embed._3, embed._5, embed._8, embed._9)
                  }
                } else if (embed._2 == "screenshot") {
                  if (embed._5 >= minimumLevel) {
                    deathsTextChannel.sendMessageEmbeds(embed._1.build())
                      .setComponents(actionRow)
                      .queue()
                    recordDeath(embed._3, embed._5, embed._8, embed._9)
                    }
                } else {
                  // for regular deaths check if level > /filter deaths <level>
                  if (embed._5 >= minimumLevel) {
                    deathsTextChannel.sendMessageEmbeds(embed._1.build())
                      .setSuppressedNotifications(true)
                      .queue()
                    recordDeath(embed._3, embed._5, embed._8, embed._9)
                  }
                }
              } catch {
                case ex: Exception => logger.error(s"Failed to send message to 'deaths' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}")
                case _: Throwable => logger.error(s"Failed to send message to 'deaths' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
              }
            }
          }
        }
        }
      }
    }

    cleanUp()

    Future.successful(())
  }.withAttributes(logAndResume)

  private def onlineList(onlineData: List[tracking.OnlinePlayer], guildId: String, alliesChannel: String, neutralsChannel: String, enemiesChannel: String, categoryChannel: String, onlineCombined: String, world: String): Unit = {

    val vocationBuffers = ListMap(
      domain.Vocations.displayOrder.map(_ -> ListBuffer[CharSort]()): _*
    )

    // Indexed once for this whole render, not scanned per player. This runs
    // over every character online on the world (routinely 1000+) and asked four
    // "is this name on that list?" questions each, every one a full scan of a
    // guild's allied/hunted lists — so a guild tracking a few hundred entries
    // did six figures of string comparisons per refresh. Safe to snapshot here
    // because rendering the online list is strictly read-only; the death path
    // deliberately keeps reading the live lists, since it mutates them as it
    // goes and must see its own writes.
    val alliedGuildNames = alliedGuildsData.getOrElse(guildId, Nil).iterator.map(_.name.toLowerCase).toSet
    val huntedGuildNames = huntedGuildsData.getOrElse(guildId, Nil).iterator.map(_.name.toLowerCase).toSet
    val alliedPlayerNames = alliedPlayersData.getOrElse(guildId, Nil).iterator.map(_.name.toLowerCase).toSet
    val huntedPlayerNames = huntedPlayersData.getOrElse(guildId, Nil).iterator.map(_.name.toLowerCase).toSet

    val sortedList = onlineData.sortWith(_.level > _.level)
    var zapCount = 0
    sortedList.foreach { player =>
      val voc = player.vocation.toLowerCase.split(' ').last
      val vocationEmoji = vocEmoji(voc)
      val durationInSec = player.duration
      val durationString = presentation.OnlineListEmbeds.durationString(durationInSec)
      val guildNameLower = player.guildName.toLowerCase
      val playerNameLower = player.name.toLowerCase
      val allyGuildCheck = alliedGuildNames.contains(guildNameLower)
      val huntedGuildCheck = huntedGuildNames.contains(guildNameLower)
      val allyPlayerCheck = alliedPlayerNames.contains(playerNameLower)
      val huntedPlayerCheck = huntedPlayerNames.contains(playerNameLower)
      val guildIcon = presentation.GuildIcons.guildIcon(player.guildName, allyGuildCheck, huntedGuildCheck, allyPlayerCheck, huntedPlayerCheck)

      // Masslog: only shows characters :zap: if they have been logged in for
      // less than tracking.MasslogDetector.RecentLoginSeconds. That constant is
      // shared with the per-user mass-log DM below, whose threshold is a count
      // of exactly these people.
      val justLogged = durationInSec < tracking.MasslogDetector.RecentLoginSeconds && (huntedGuildCheck || huntedPlayerCheck)
      val masslogIcon = if (justLogged) " :zap:" else if (durationInSec > 18000 && (huntedGuildCheck || huntedPlayerCheck)) " :zzz:" else ""
      if (justLogged) zapCount += 1
      vocationBuffers(voc) += CharSort(player.guildName,allyGuildCheck,huntedGuildCheck,allyPlayerCheck,huntedPlayerCheck,voc,player.level.toInt,s"$vocationEmoji **${player.level}** — **[${player.name}](${charUrl(player.name)})** $guildIcon $durationString ${player.flag}${masslogIcon}"
      )
    }

    // run channel checks before updating the channels
    val guild = BotApp.discordGateway.guildById(guildId)

    // default online list
    val alliesList: List[String] = vocationBuffers.values
      .flatMap(_.filter(charSort => charSort.allyPlayer || charSort.allyGuild))
      .map(_.message)
      .toList
    val enemiesList: List[String] = vocationBuffers.values
      .flatMap(_.filter(charSort => charSort.huntedPlayer || charSort.huntedGuild))
      .map(_.message)
      .toList
    val neutralsList: List[String] = vocationBuffers.values
      .flatMap(_.filter(charSort => !charSort.huntedPlayer && !charSort.huntedGuild && !charSort.allyPlayer && !charSort.allyGuild))
      .map(_.message)
      .toList

    // Masslog threshold (sensitivity fixed at 0 today; formula in tracking.MasslogDetector).
    // Drives the "⚡" suffix on the category name below.
    val masslogCategory = tracking.MasslogDetector.isMasslog(zapCount, enemiesList.size, sensitivity = 0)
    // Suppressed for the first 30 minutes after a restart: on startup every
    // tracked player looks like a fresh login, which would read as a mass-log.
    val recentStart = BotApp.startTime.isAfter(Instant.now().minusSeconds(30 * 60))
    val masslogIcon = if (masslogCategory && !recentStart) "⚡" else ""

    // The per-user mass-log DM. Deliberately not gated on `masslogCategory`:
    // that formula is a proportion, tuned to decide whether a whole world's
    // category deserves a warning icon, whereas a subscriber picked a flat count
    // of enemies. A guild watching thirty enemies never trips the proportion at
    // eight arrivals, and eight arriving at once is exactly what they asked to
    // hear about. `recentStart` still applies, for the reason directly above.
    if (!recentStart) {
      try BotApp.notifyService.onMasslog(guildId, world, guild.getName, zapCount, enemiesList.size)
      catch {
        // Never let a notification failure cost this guild its online list.
        case ex: Throwable => logger.warn(s"Mass-log notification failed for Guild ID: '$guildId'", ex)
      }
    }

    // combined online list into one channel
    if (onlineCombined == "true") {
      val combinedTextChannel = guild.getTextChannelById(alliesChannel)
      if (combinedTextChannel != null) {
        if (combinedTextChannel.canTalk() || (!Config.prod)) {

          // neutrals grouped by Guild
          val guildNameCounts: Map[String, Int] = vocationBuffers.values
            .flatMap(_.map(_.guildName))
            .groupBy(identity)
            .view.mapValues(_.size)
            .toMap

          val updatedVocationBuffers = vocationBuffers.view.mapValues { charSorts =>
            val updatedCharSorts = charSorts.map { charSort =>
              if (charSort.guildName != "" && guildNameCounts.getOrElse(charSort.guildName, 0) < 3) {
                charSort.copy(guildName = "")
              } else {
                charSort
              }
            }
            updatedCharSorts
          }

          val neutralsGroupedByGuild: List[(String, List[String])] = presentation.OnlineListGrouping.groupByGuild(
            updatedVocationBuffers.values.flatten
              .filter(charSort => !charSort.huntedPlayer && !charSort.huntedGuild && !charSort.allyPlayer && !charSort.allyGuild)
              .map(charSort => charSort.guildName -> charSort.message))

          val flattenedNeutralsList: List[String] =
            presentation.OnlineListGrouping.withHeaders(neutralsGroupedByGuild, n => s"### Others $n")

          val totalCount = alliesList.size + neutralsList.size + enemiesList.size

          val combinedList = presentation.OnlineListGrouping.combinedChannelBody(
            alliesList, enemiesList, neutralsList, flattenedNeutralsList, Config.ally, Config.enemy)

          val channelName = combinedTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "online")
          renameOnlineChannelIfDue(combinedTextChannel, s"$customName-$totalCount", "online list channel", guildId, guild.getName)

          if (combinedList.nonEmpty) {
            updateMultiFields(combinedList, combinedTextChannel, "allies", guildId, guild.getName)
          } else {
            updateMultiFields(List("*Nobody is online right now.*"), combinedTextChannel, "allies", guildId, guild.getName)
          }
        }
      }
      val neutralsTextChannel = guild.getTextChannelById(neutralsChannel)
      if (neutralsTextChannel != null) {
        if (neutralsTextChannel.canTalk() || (!Config.prod)) {
          val channelName = neutralsTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "neutrals")
          renameOnlineChannelIfDue(neutralsTextChannel, s"$customName-0", "disabled neutral channel", guildId, guild.getName)
          updateMultiFields(List("*This channel is `disabled` and can be deleted.*"), neutralsTextChannel, "neutrals", guildId, guild.getName)
        }
      }
      val enemiesTextChannel = guild.getTextChannelById(enemiesChannel)
      if (enemiesTextChannel != null) {
        if (enemiesTextChannel.canTalk() || (!Config.prod)) {
          val channelName = enemiesTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "enemies")
          renameOnlineChannelIfDue(enemiesTextChannel, s"$customName-0", "disabled enemies channel", guildId, guild.getName)
          updateMultiFields(List("*This channel is `disabled` and can be deleted.*"), enemiesTextChannel, "enemies", guildId, guild.getName)
        }
      }

      // add allies/enemies count to the category
      renameOnlineCategoryIfDue(guild, categoryChannel, world, alliesList.size, enemiesList.size, masslogIcon)
    } else {
      // separated online list channels
      val alliesCount = alliesList.size
      val neutralsCount = neutralsList.size
      val enemiesCount = enemiesList.size

      // add allies/enemies count to the category
      renameOnlineCategoryIfDue(guild, categoryChannel, world, alliesList.size, enemiesList.size, masslogIcon)
      // allies grouped by Guild
      val alliesGroupedByGuild: List[(String, List[String])] = presentation.OnlineListGrouping.groupByGuild(
        vocationBuffers.values.flatten
          .filter(charSort => charSort.allyPlayer || charSort.allyGuild)
          .map(charSort => charSort.guildName -> charSort.message))

      val flattenedAlliesList: List[String] =
        presentation.OnlineListGrouping.withHeaders(alliesGroupedByGuild, n => s"### No Guild  $n")

      val alliesTextChannel = guild.getTextChannelById(alliesChannel)
      if (alliesTextChannel != null) {
        if (alliesTextChannel.canTalk() || (!Config.prod)) {
          val channelName = alliesTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "allies")
          renameOnlineChannelIfDue(alliesTextChannel, s"$customName-$alliesCount", "allies channel", guildId, guild.getName)
          if (alliesList.nonEmpty) {
            updateMultiFields(flattenedAlliesList, alliesTextChannel, "allies", guildId, guild.getName)
          } else {
            updateMultiFields(List("*No `allies` are online right now.*"), alliesTextChannel, "allies", guildId, guild.getName)
          }
        }
      }

      // neutrals grouped by Guild
      val neutralsGroupedByGuild: List[(String, List[String])] = presentation.OnlineListGrouping.groupByGuild(
        vocationBuffers.values.flatten
          .filter(charSort => !charSort.huntedPlayer && !charSort.huntedGuild && !charSort.allyPlayer && !charSort.allyGuild)
          .map(charSort => charSort.guildName -> charSort.message))

      val flattenedNeutralsList: List[String] =
        presentation.OnlineListGrouping.withHeaders(neutralsGroupedByGuild, n => s"### No Guild  $n")

      val neutralsTextChannel = guild.getTextChannelById(neutralsChannel)
      if (neutralsTextChannel != null) {
        if (neutralsTextChannel.canTalk() || (!Config.prod)) {
          val channelName = neutralsTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "neutrals")
          renameOnlineChannelIfDue(neutralsTextChannel, s"$customName-$neutralsCount", "neutrals channel", guildId, guild.getName)
          if (neutralsList.nonEmpty) {
            updateMultiFields(flattenedNeutralsList, neutralsTextChannel, "neutrals", guildId, guild.getName)
          } else {
            updateMultiFields(List("*No `neutrals` are online right now.*"), neutralsTextChannel, "neutrals", guildId, guild.getName)
          }
        }
      }

      // enemies grouped by Guild
      val enemiesGroupedByGuild: List[(String, List[String])] = presentation.OnlineListGrouping.groupByGuild(
        vocationBuffers.values.flatten
          .filter(charSort => charSort.huntedPlayer || charSort.huntedGuild)
          .map(charSort => charSort.guildName -> charSort.message))

      val flattenedEnemiesList: List[String] =
        presentation.OnlineListGrouping.withHeaders(enemiesGroupedByGuild, n => s"### No Guild  $n")

      val enemiesTextChannel = guild.getTextChannelById(enemiesChannel)
      if (enemiesTextChannel != null) {
        if (enemiesTextChannel.canTalk() || (!Config.prod)) {
          val channelName = enemiesTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "enemies")
          renameOnlineChannelIfDue(enemiesTextChannel, s"$customName-$enemiesCount", "enemies channel", guildId, guild.getName)
          if (enemiesList.nonEmpty) {
            updateMultiFields(flattenedEnemiesList, enemiesTextChannel, "enemies", guildId, guild.getName)
          } else {
            updateMultiFields(List("*No `enemies` are online right now.*"), enemiesTextChannel, "enemies", guildId, guild.getName)
          }
        }
      }
    }

  }

  /** Is this channel's 6-hourly "wipe and repost from scratch" purge due? Reads
   *  and advances the per-purge-type timer, so it must only ever be called from
   *  the world stream's own thread (see updateMultiFields) — the timers are
   *  plain vars. */
  private def onlineListPurgeDue(purgeType: String, guildId: String): Boolean = {
    val epoch = ZonedDateTime.parse("2022-01-01T01:00:00Z")
    def due(timers: Map[String, ZonedDateTime]): Boolean =
      ZonedDateTime.now().isAfter(timers.getOrElse(guildId, epoch).plusHours(6))
    purgeType match {
      case "allies" if due(alliesListPurgeTimer) =>
        alliesListPurgeTimer = alliesListPurgeTimer + (guildId -> ZonedDateTime.now()); true
      case "neutrals" if due(neutralsListPurgeTimer) =>
        neutralsListPurgeTimer = neutralsListPurgeTimer + (guildId -> ZonedDateTime.now()); true
      case "enemies" if due(enemiesListPurgeTimer) =>
        enemiesListPurgeTimer = enemiesListPurgeTimer + (guildId -> ZonedDateTime.now()); true
      case _ => false
    }
  }

  private def updateMultiFields(values: List[String], channel: TextChannel, purgeType: String, guildId: String, guildName: String): Unit = {
    // Decided here, on the stream thread, rather than inside a callback, so the
    // purge timers stay single-threaded. The window therefore advances even if
    // the history read below fails — a skipped purge just waits out another 6
    // hours, which is preferable to racing the timers across threads.
    val purgeDue = onlineListPurgeDue(purgeType, guildId)
    val channelId = channel.getId
    val fields = presentation.OnlineListEmbeds.packFields(values)

    // The steady-state path: we already know which messages we posted here and
    // what we last put in them, so the whole update is decided locally with no
    // read of Discord at all. Only a cold cache (first cycle after a restart,
    // or after the cache was invalidated) or the 6-hourly purge falls through
    // to reading history.
    if (!purgeDue && onlineListState.isWarm(channelId)) {
      dispatchOnlineListUpdate(channel, fields, guildId, guildName)
    } else {
      resyncOnlineListCache(channel, purgeDue, guildId, guildName) { () =>
        dispatchOnlineListUpdate(channel, fields, guildId, guildName)
      }
    }
  }

  /** Rebuild this channel's cache from its actual recent history, then run
   *  `andThen`. On the purge path the existing messages are deleted first and
   *  the cache seeded empty, so the list is reposted from scratch.
   *
   *  This is the only remaining read of Discord in the online-list path. It
   *  used to run on every refresh for every (guild, channel) — as a blocking
   *  `.complete()` inside the per-world stream's mapAsync(1) stage, so one
   *  guild's online list delayed every death on that world. It is now both
   *  non-blocking and rare. */
  private def resyncOnlineListCache(channel: TextChannel, purgeDue: Boolean, guildId: String, guildName: String)(andThen: () => Unit): Unit =
    channel.getHistory.retrievePast(100).queue(
      history => {
        try {
          val existing = history.asScala.toList.filter(_.getAuthor.getId.equals(BotApp.botUser)).reverse
          val seeded =
            if (purgeDue) {
              if (existing.nonEmpty) channel.purgeMessages(existing.asJava)
              Nil
            } else {
              existing.map { m =>
                tracking.OnlineListMessage(Some(m.getId), m.getEmbeds.asScala.headOption.map(_.getDescription).getOrElse(""))
              }
            }
          onlineListState.seed(channel.getId, seeded)
          andThen()
        } catch {
          case ex: Throwable =>
            logger.error(s"Failed to rebuild online list state for Guild ID: '$guildId' Guild Name: '$guildName': ${ex.getMessage}")
        }
      },
      (ex: Throwable) =>
        logger.error(s"Failed to read online list history for Guild ID: '$guildId' Guild Name: '$guildName': ${ex.getMessage}")
    )

  /** Diff `fields` against what we believe is posted and enqueue whatever makes
   *  Discord match.
   *
   *  Sends go through their own dedicated lane (onlineListSender), separate
   *  from the general background lane — Discord rate-limits message-edit PATCH
   *  calls far harder than the general REST budget (confirmed via live 429s),
   *  so this traffic needs a slower, isolated pace. Each send is keyed by
   *  (channel id, message index): demand here can exceed what that slower pace
   *  can drain, so without keying, a backed-up queue fills with many stale
   *  updates to the same handful of channels, each superseded by the next
   *  before it ever sends (observed depth in the thousands, 25+ minute average
   *  wait). Keying caps the backlog at one pending update per channel/message
   *  and guarantees whatever finally sends is current.
   *
   *  Each send is also grouped by channel id, because Discord's limit here is
   *  per-channel while the lane's pace is bot-wide. A list that packs into
   *  several embeds enqueues them all at once and in FIFO they would drain
   *  back-to-back, putting 5+ edits into one channel inside a second; the group
   *  makes the lane spend those slots on other channels and come back to this
   *  one after its gap. */
  private def dispatchOnlineListUpdate(channel: TextChannel, fields: List[String], guildId: String, guildName: String): Unit = {
    val channelId = channel.getId
    val lastIndex = fields.size - 1

    def buildEmbed(field: String, last: Boolean): net.dv8tion.jda.api.entities.MessageEmbed = {
      val embed = new EmbedBuilder()
      embed.setDescription(field)
      embed.setColor(3092790)
      if (last) {
        embed.setFooter("Last updated")
        embed.setTimestamp(OffsetDateTime.now())
      }
      embed.build()
    }
    def failed(ex: Throwable): Unit = {
      // Whatever went wrong, our picture of the channel may no longer match
      // it — drop the cache so the next cycle rebuilds from history.
      onlineListState.invalidate(channelId)
      logger.error(s"Failed to update online list for Guild ID: '$guildId' Guild Name: '$guildName': ${ex.getMessage}")
    }

    onlineListState.plan(channelId, fields).foreach {
      case tracking.EditOnlineListMessage(index, messageId, field) =>
        worldMetrics.incrementEdits()
        onlineListSender.enqueue("editmessage", Some(s"$channelId:$index"), Some(channelId)) { () =>
          try channel.editMessageEmbedsById(messageId, buildEmbed(field, index == lastIndex))
            .queue(null, onlineListErrorHandler(channelId))
          catch { case ex: Throwable => failed(ex) }
        }
      case tracking.SendOnlineListMessage(index, field) =>
        worldMetrics.incrementEdits()
        onlineListSender.enqueue("send", Some(s"$channelId:$index"), Some(channelId)) { () =>
          try channel.sendMessageEmbeds(buildEmbed(field, index == lastIndex)).setSuppressedNotifications(true)
            .queue(
              message => onlineListState.recordMessageId(channelId, index, message.getId),
              // A send that never lands would otherwise leave its slot pending
              // forever (see OnlineListState.plan), so this must invalidate.
              (ex: Throwable) => failed(ex)
            )
          catch { case ex: Throwable => failed(ex) }
        }
      case tracking.DeleteOnlineListMessages(messageIds) =>
        // Left over from a previously longer list.
        try channel.purgeMessagesById(messageIds.asJava)
        catch { case ex: Throwable => failed(ex) }
    }
  }

  /** Like [[ignoreDeletedTarget]], except a message/channel that has gone away
   *  also means our cached picture of this channel is wrong — drop it so the
   *  next cycle rebuilds from history instead of editing a message that no
   *  longer exists forever. */
  private def onlineListErrorHandler(channelId: String): ErrorHandler =
    new ErrorHandler()
      .handle(
        java.util.EnumSet.of(ErrorResponse.UNKNOWN_MESSAGE, ErrorResponse.UNKNOWN_CHANNEL),
        new java.util.function.Consumer[ErrorResponseException] {
          def accept(ex: ErrorResponseException): Unit = onlineListState.invalidate(channelId)
        }
      )
      .ignore(ErrorResponse.MISSING_PERMISSIONS, ErrorResponse.MISSING_ACCESS)

  // Remove players from the list who haven't logged in for a while. Remove old saved deaths.
  private def cleanUp(): Unit = {
    val now = ZonedDateTime.now()
    recentOnline.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < onlineRecentDuration
    }
    recentDeaths.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < deathRecentDuration
    }
    levelTracker.prune(now, recentLevelExpiry)
  }

  private def vocEmoji(vocation: String): String = presentation.Emojis.vocEmoji(vocation)

  private def guildUrl(guild: String): String = presentation.Urls.guildUrl(guild)

  private def charUrl(char: String): String = presentation.Urls.charUrl(char)

  /** The level to show beside a PvP killer's name. A pure lookup: this world's
   *  online table first, then whatever prefetchKillerLevels already resolved.
   *  Deliberately never fetches — it is called while building embeds, once per
   *  killer *per discord* tracking this world, so a fetch here multiplies. */
  private def getKillerLevel(killerName: String, now: ZonedDateTime): Option[Int] =
    onlineListTable.get(killerName.toLowerCase).map(_.level)
      .orElse(killerLevelCache.levelFor(killerName, now))

  /** Every character name whose level this batch of deaths will want to show —
   *  exactly the names the embed builder passes to getKillerLevel. */
  private def killerNamesNeedingLevels(charDeaths: Set[CharDeath]): Set[String] =
    charDeaths.flatMap { charDeath =>
      domain.Killers.levelLookupNames(
        charDeath.char.character.character.name,
        charDeath.death.killers.map(k => (k.name, k.player))
      )
    }

  /** Resolve, in one bounded parallel batch, the killer levels this death batch
   *  needs and does not already have.
   *
   *  This used to happen lazily inside the embed builder as a blocking
   *  `Await.result(..., 10.seconds)` per killer — and the embed builder runs
   *  once per discord tracking the world, so a world tracked by five discords
   *  made five separate blocking lookups for the same killer, serially, inside
   *  the stream's mapAsync(1) stage. That stalled not just the deaths but the
   *  next poll tick behind them.
   *
   *  Names already in `onlineListTable` or freshly cached are skipped, so the
   *  common case (every killer online on this world) fetches nothing and waits
   *  for nothing. A single mass-PvP tick is capped: past the cap the remaining
   *  killers simply render without a level, which is strictly better than
   *  delaying the deaths themselves. */
  private def prefetchKillerLevels(charDeaths: Set[CharDeath], now: ZonedDateTime): Unit = {
    killerLevelCache.prune(now)
    val wanted = killerNamesNeedingLevels(charDeaths).filter { name =>
      !onlineListTable.contains(name.toLowerCase) && killerLevelCache.needsLookup(name, now)
    }
    if (wanted.nonEmpty) {
      val batch = wanted.take(killerLevelBatchCap)
      if (wanted.size > batch.size)
        logger.debug(s"Death batch on world '$world' needs ${wanted.size} killer-level lookups; resolving ${batch.size} and showing the rest without a level")
      // Each lookup caches its own outcome as it lands, rather than the batch
      // recording them together at the end — so a batch that times out still
      // keeps whatever did resolve in time. Recovered per element, never per
      // batch: one failed lookup must not fail the others or this stage.
      val resolved = Source(batch)
        .mapAsyncUnordered(killerLevelConcurrency) { name =>
          tibiaDataClient.getKillerFallback(name)
            .map {
              case Right(response) => killerLevelCache.record(name, Some(response.character.character.level.toInt), now)
              case Left(error) =>
                logger.warn(s"Failed to get character '$name' from TibiaData API: $error")
                killerLevelCache.record(name, None, now)
            }
            .recover { case ex: Throwable =>
              logger.warn(s"Exception when calling TibiaData API for '$name': ${ex.getMessage}")
              killerLevelCache.record(name, None, now)
            }
        }
        .runWith(Sink.ignore)
      try {
        // One bounded wait for the whole batch, in place of the per-killer,
        // per-discord serial waits this replaces.
        Await.result(resolved, killerLevelBatchTimeout)
      } catch {
        case ex: Throwable =>
          // Cache a miss for whatever still hasn't answered. When the API is
          // merely slow or hung (rather than refusing quickly) the requests
          // never complete, so without this nothing is recorded and the very
          // next death batch pays the full timeout again for the same names —
          // a sustained outage costs 10s per batch forever. Recording the miss
          // makes a hang behave like a fast failure: one timeout per killer per
          // killer-level-ttl. The trade is that a killer whose lookup was in
          // flight when the API recovered shows no level until that TTL
          // expires; a late-completing request still overwrites this with the
          // real level if it arrives. Write-off is if-absent so it can never
          // clobber a level that landed just as the wait gave up.
          val writtenOff = batch.count(killerLevelCache.recordMissIfAbsent(_, now))
          logger.warn(s"Killer-level lookups for world '$world' did not finish within ${killerLevelBatchTimeout.toSeconds}s ($writtenOff of ${batch.size} unresolved, cached as no-level until the TTL expires): ${ex.getMessage}")
      }
    }
  }

  private def creatureImageUrl(creature: String): String =
    presentation.Urls.creatureImageUrl(creature, Config.creatureUrlMappings)

  lazy val stream: RunnableGraph[Cancellable] =
    sourceTick via
      getWorld via
      getCharacterData via
      scanForDeaths via
      postToDiscordAndCleanUp to Sink.ignore

  // Often enough to notice a guild coming due without adding meaningful work —
  // a sweep that finds nothing to do is just a timer comparison per guild. The
  // actual refresh pace is the per-guild interval inside onlineListSweep.
  private val onlineListSweepInterval = 15.seconds

  /** Start this world: the poll stream plus the online-list sweep. Cancelling
   *  the returned handle stops both — [[com.tibiabot.app.StreamSupervisor]]
   *  cancels it once no guild uses this world any more, and a sweep left
   *  running past that would keep refreshing lists for a world nothing tracks. */
  def run(): Cancellable = {
    val streamHandle = stream.run()
    val sweepHandle = system.scheduler.scheduleWithFixedDelay(
      onlineListSweepInterval, onlineListSweepInterval
    )(new Runnable { def run(): Unit = onlineListSweep() })(ex)
    new Cancellable {
      private val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)
      def cancel(): Boolean =
        if (cancelled.compareAndSet(false, true)) {
          sweepHandle.cancel()
          streamHandle.cancel()
          true
        } else false
      def isCancelled: Boolean = cancelled.get()
    }
  }

  def canPing(channelId: String): Boolean = {
      pingCleanup()

      val now = ZonedDateTime.now()
      val lastPing = cooldowns.get(channelId)

      if (lastPing != null &&
          java.time.Duration.between(lastPing, now).toMinutes < cooldownMinutes) {

        false
      } else {
        cooldowns.put(channelId, now)
        true
      }
    }

  private def pingCleanup(): Unit = {
    val now = ZonedDateTime.now()

    cooldowns.entrySet().removeIf(entry =>
      java.time.Duration.between(entry.getValue, now).toMinutes >= cooldownMinutes
    )
  }

  /** Renames a world's online-list category to reflect the live ally/enemy
   *  counts (and the mass-log ⚡), throttled to at most once per 6-minute
   *  window *actually spent renaming* — the window only advances when a
   *  rename is genuinely dispatched, not on every check, so a channel whose
   *  name is already correct doesn't burn its window and go stale later.
   *  The name-change guard intentionally ignores the ⚡ suffix, matching the
   *  original — so the category re-renames once after a mass-log toggle.
   *  The actual send goes through the shared bot-wide background lane
   *  (`outboundSender`), keyed by category id so a burst of many entities
   *  coming due at once (many guilds sharing a world) drains at a safe
   *  shared pace instead of firing all at once — and if this category
   *  somehow gets re-queued before an earlier rename for it has sent, the
   *  older one is superseded rather than both firing. */
  private def renameOnlineCategoryIfDue(guild: Guild, categoryId: String, world: String, alliesCount: Int, enemiesCount: Int, masslogIcon: String): Unit = {
    val category = guild.getCategoryById(categoryId)
    if (category != null) {
      val lastRename = onlineListCategoryTimer.getOrElse(categoryId, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
      if (ZonedDateTime.now().isAfter(lastRename.plusMinutes(6))) {
        val baseName = presentation.OnlineListEmbeds.categoryName(world, alliesCount, enemiesCount)
        if (category.getName != baseName) {
          val renamedAt = ZonedDateTime.now()
          onlineListCategoryTimer = onlineListCategoryTimer + (categoryId -> renamedAt)
          BotApp.recordRenameCooldown(world, categoryId, renamedAt)
          outboundSender.enqueue("editchannel", Some(categoryId)) { () =>
            try {
              category.getManager.setName(s"$baseName$masslogIcon").queue(null, ignoreDeletedTarget)
            } catch {
              case ex: Throwable => logger.warn(s"Failed to rename the category channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
            }
          }
        }
      }
    }
  }

  /** Renames an online-list text channel to `targetName`, throttled to at most
   *  once per 6-minute window *actually spent renaming* (tracked in
   *  onlineListCategoryTimer, only advanced when a rename is genuinely
   *  dispatched — see renameOnlineCategoryIfDue) and skipped when the name is
   *  already correct. The actual send goes through the shared bot-wide
   *  background lane (`outboundSender`), keyed by channel id, same reasoning
   *  as above. Rename failures (e.g. missing Manage Channels) are logged,
   *  not fatal — `label` names the channel in the log line. */
  private def renameOnlineChannelIfDue(channel: TextChannel, targetName: String, label: String, guildId: String, guildName: String): Unit = {
    val lastRename = onlineListCategoryTimer.getOrElse(channel.getId, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
    if (ZonedDateTime.now().isAfter(lastRename.plusMinutes(6))) {
      if (channel.getName != targetName) {
        val renamedAt = ZonedDateTime.now()
        onlineListCategoryTimer = onlineListCategoryTimer + (channel.getId -> renamedAt)
        BotApp.recordRenameCooldown(world, channel.getId, renamedAt)
        outboundSender.enqueue("editchannel", Some(channel.getId)) { () =>
          try {
            channel.getManager.setName(targetName).queue(null, ignoreDeletedTarget)
          } catch {
            case ex: Throwable => logger.warn(s"Failed to rename the $label for Guild ID: '$guildId' Guild Name: '$guildName'", ex)
          }
        }
      }
    }
  }

  // Helper method to queue messages with rate limiting. `label` buckets the
  // send's queue-wait stats (see RateLimitedSender) — these are all message
  // POSTs, so they are labelled by what the post is ("activity", "admin",
  // "level-up") rather than by the Discord call, which would collapse them all
  // onto one uninformative "send" and lose any sense of which kind of traffic
  // is the one waiting.
  private def sendMessageWithRateLimit(
    channel: TextChannel,
    label: String,
    message: String = "",
    embed: Option[EmbedBuilder] = None,
    suppressNotifications: Boolean = true
  ): Unit = {
    outboundSender.enqueue(label) { () =>
      embed match {
        case Some(e) =>
          if (message.nonEmpty)
            channel.sendMessage(message).setEmbeds(e.build()).setSuppressedNotifications(suppressNotifications).queue(null, ignoreDeletedTarget)
          else
            channel.sendMessageEmbeds(e.build()).setSuppressedNotifications(suppressNotifications).queue(null, ignoreDeletedTarget)
        case None =>
          channel.sendMessage(message).setSuppressedNotifications(suppressNotifications).queue(null, ignoreDeletedTarget)
      }
    }
  }

}

object TibiaBot {
  /** How often a world re-polls. Named because two things depend on it and
   *  they must not drift apart: the stream's own tick, and the character age
   *  cache, which rounds each character's next fetch to the nearest poll and
   *  would lose a whole interval of death-detection latency if it were working
   *  from a different number than the tick actually uses. */
  val PollInterval: FiniteDuration = 60.seconds

  /** A moment for the process to finish starting before any world polls. */
  private[tibiabot] val SettleDelay: FiniteDuration = 2.seconds

  /** How long a world waits before its first poll: the settle delay, plus a
   *  random offset spread across one whole interval.
   *
   *  Every stream is built in the same startup loop and used to wait the same
   *  two seconds, so every world polled on the same second and went on doing so
   *  forever — tens of thousands of character requests leaving in one burst a
   *  second or two wide, then near silence until the next minute. Averaged over
   *  the minute that looks modest; arriving at somebody else's gateway it is a
   *  spike, and a spike from one address is what load shedding is for.
   *
   *  Offsetting the start spreads those bursts across the interval instead.
   *  Each world still polls exactly once per interval, so nothing waits any
   *  longer than it did — only the phase differs, and phase is the one property
   *  of a poll schedule nothing downstream depends on. */
  /** The same spreading applied to a fleet-fetch poller — see
   *  [[com.tibiabot.app.FetchOnlyWorldPoller]]. A primary can take on several
   *  of these at once when a secondary joins, and starting them together would
   *  put every one of those worlds' characters on the wire in the same second. */
  def fleetFetchFirstDelay(jitterSeconds: Int => Int): FiniteDuration =
    firstPollDelay(jitterSeconds)

  private[tibiabot] def firstPollDelay(jitterSeconds: Int => Int): FiniteDuration =
    SettleDelay + jitterSeconds(PollInterval.toSeconds.toInt).seconds
}
