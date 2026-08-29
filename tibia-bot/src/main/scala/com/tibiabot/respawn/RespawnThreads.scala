package com.tibiabot.respawn

import com.tibiabot.domain.{Respawn, RespawnSchedule, RespawnSettings}
import com.tibiabot.presentation.{Embeds, RespawnBoardImage, RespawnEmbeds}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.{EmbedBuilder, Permission}
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.channel.concrete.{Category, ForumChannel, ThreadChannel}
import net.dv8tion.jda.api.entities.channel.forums.{ForumTag, ForumTagData, ForumTagSnowflake}
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.{Guild, Message, MessageEmbed, Role}
import net.dv8tion.jda.api.managers.channel.concrete.ThreadChannelManager
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.{MessageCreateBuilder, MessageEditBuilder}

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/** Everything the respawn system does to Discord: the forum channel, the board
 *  post, and the one reused post per spawn.
 *
 *  Split out from [[RespawnService]] so the claim rules stay separable from the
 *  JDA calls. Anything whose result is needed to carry on — creating a thread,
 *  because the next step needs its id — is blocking (`.complete()`) in the same
 *  style as `setup.ChannelService`, and callers are expected to be on the
 *  slash-command pool or the respawn sweep's own thread, never on JDA's event
 *  thread or the Akka dispatcher.
 *
 *  The three that merely *tell Discord about a change* — rewriting a card,
 *  swapping a tag, archiving a post — are handed over instead (`.queue()`).
 *  Nothing downstream reads their result, and they were the reason a button
 *  press sat through two or three sequential round trips after it had already
 *  been acknowledged. Failures still surface: they are logged from the callback
 *  rather than the call.
 */
object RespawnThreads extends StrictLogging {

  val ChannelName: String = "📅・sᴘᴀᴡɴs"

  /** Forum tags, so the channel can be filtered down to what's actually
   *  available. Names are matched case-insensitively when applying, so renaming
   *  a tag in Discord's UI breaks the mapping — that's acceptable for a
   *  cosmetic filter, and `/repair` restores the originals. */
  val TagFree: String = "Free"
  val TagClaimed: String = "Claimed"

  /** What every Claim button wears. A custom emoji, so it is parsed from the
   *  configured `<:name:id>` form rather than built from a code point. */
  private def claimEmoji: Emoji = Emoji.fromFormatted(com.tibiabot.Config.dailyEmoji)

  /** The other three buttons' faces, as text.
   *
   *  Named here rather than written into [[boardButtons]] because the board's
   *  description lists the buttons and wears the same emoji against each line.
   *  Two copies of "📅" that could drift is exactly the kind of thing nobody
   *  notices until the picture and the label disagree. Claim's is
   *  `Config.dailyEmoji`, already in that form and already shared. */
  private val BookEmoji: String = "📅"
  private val ConfigEmoji: String = "⚙️"
  private val DashboardEmoji: String = "🌐"

  private val tagSeeds: List[(String, String)] =
    List(TagFree -> "🟢", TagClaimed -> "🔴")

  /** Buttons under a spawn's claim card. The respawn id is encoded into the id
   *  so a click needs no lookup of which post it came from — see
   *  [[RespawnButtonId]]. */
  def claimButtons(respawnId: Long, claimed: Boolean): ActionRow =
    ActionRow.of(claimRow(respawnId, claimed).asJava)

  private def claimRow(respawnId: Long, claimed: Boolean): List[Button] =
    if (claimed)
      // One Leave button, not a separate Release: which of the two it means is
      // decided by whether the presser holds the spawn or is waiting for it, and
      // making the member pick the right word for their own state was needless.
      List(
        Button.primary(RespawnButtonId.next(respawnId), "Next").withEmoji(Emoji.fromUnicode("⏭️")),
        Button.secondary(RespawnButtonId.spawnSchedule(respawnId), "Bookings").withEmoji(Emoji.fromUnicode("📅")),
        Button.secondary(RespawnButtonId.spawnConfig(respawnId), "Config").withEmoji(Emoji.fromUnicode("⚙️")),
        Button.danger(RespawnButtonId.leave(respawnId), "Leave")
      )
    else
      // Config is here as well as on a claimed spawn. It means the same thing to
      // a member either way — their own claim defaults — and for a moderator it
      // is the way to the claim log, which is most worth reading about a spawn
      // nobody is on.
      List(
        Button.success(RespawnButtonId.claim(respawnId), "Claim").withEmoji(claimEmoji),
        Button.secondary(RespawnButtonId.spawnSchedule(respawnId), "Bookings").withEmoji(Emoji.fromUnicode("📅")),
        Button.secondary(RespawnButtonId.spawnConfig(respawnId), "Config").withEmoji(Emoji.fromUnicode("⚙️"))
      )

  /** The buttons on the pinned board post, which is what makes the whole system
   *  usable without touching a slash command: a spawn with no post yet can't
   *  have a Claim button of its own, so the board carries one. Book is here for
   *  the same reason — booking a spawn nobody has claimed yet would otherwise
   *  mean finding a post that does not exist.
   *
   *  Dashboard is a link button, so it carries no custom id: Discord opens the
   *  URL itself and the press never reaches the bot. That is why it can sit in
   *  the same row as the three that do — [[RespawnButtonId.parse]] never sees
   *  it, and there is no handler to add.
   *
   *  In the order [[boardIntro]] lists them, which is roughly how often they are
   *  wanted: the three ways to get a spawn first, and settings last. */
  def boardButtons: ActionRow =
    ActionRow.of(
      Button.success(RespawnButtonId.boardClaim, "Claim").withEmoji(claimEmoji),
      Button.secondary(RespawnButtonId.boardBook, "Book").withEmoji(Emoji.fromUnicode(BookEmoji)),
      Button.link(dashboardLink, "Dashboard").withEmoji(Emoji.fromUnicode(DashboardEmoji)),
      Button.secondary(RespawnButtonId.boardConfig, "Config").withEmoji(Emoji.fromUnicode(ConfigEmoji))
    )

  /** The Config panel a moderator gets from a spawn's card, instead of going
   *  straight to their own duration. `ownClaim` adds a button for their own claim
   *  when they have one, since the moderator actions target whoever holds the
   *  spawn — which may not be them.
   *
   *  Never empty: Log and Max Claim are offered whatever state the spawn is in,
   *  so a moderator opening this on a spawn nobody holds still has the two
   *  things worth doing about an idle spawn — reading its history and setting
   *  how long anybody may hold it.
   *
   *  Five is Discord's limit for one action row, and with a holder and a claim
   *  of the moderator's own this is now exactly five. There is no sixth slot: a
   *  further spawn-level moderator action needs a second row or a menu. */
  def spawnModeratorButtons(respawnId: Long, hasHolder: Boolean, ownClaim: Boolean): ActionRow = {
    val buttons = List(
      if (hasHolder) Some(Button.primary(RespawnButtonId.holderConfig(respawnId), "Edit Claim")) else None,
      if (hasHolder) Some(Button.danger(RespawnButtonId.forceLeave(respawnId), "Cancel Claim")) else None,
      if (ownClaim) Some(Button.secondary(RespawnButtonId.selfConfig(respawnId), "My Defaults")) else None,
      Some(Button.secondary(RespawnButtonId.logPage(LogScope.Spawn(respawnId), 0), "Log")
        .withEmoji(Emoji.fromUnicode("📜"))),
      Some(Button.secondary(RespawnButtonId.spawnMax(respawnId), "Max Claim")
        .withEmoji(Emoji.fromUnicode("⏳")))
    ).flatten
    // The Collection overload, not the varargs one: `: _*` doesn't apply to a
    // Java method whose first parameter is a single component.
    ActionRow.of(buttons.asJava)
  }

  /** The Config panel a moderator gets from the board: the server's rules, or
   *  their own settings.
   *
   *  Timers used to sit between them. Everything it held is under Claim rules
   *  now — see [[com.tibiabot.interactions.RespawnModals.claimRulesModal]].
   *
   *  Autoclaim is a button of its own rather than a sixth field in that form,
   *  because the form is at Discord's five-component ceiling. It is labelled with
   *  the state it is *in*, not the one pressing it would move to: a toggle whose
   *  label is an instruction has to be pressed to find out what it was.
   *
   *  Pressing it answers with this panel again, redrawn, so the label and the
   *  embed's matching field both settle on the new value. */
  def boardModeratorButtons(autoClaim: Boolean): ActionRow =
    ActionRow.of(
      Button.primary(RespawnButtonId.boardClaimRules, "Claim rules"),
      Button.secondary(RespawnButtonId.boardAutoClaim,
        if (autoClaim) "Autoclaim: On" else "Autoclaim: Off").withEmoji(Emoji.fromUnicode("🎯")),
      Button.secondary(RespawnButtonId.logPage(LogScope.Everything, 0), "Log").withEmoji(Emoji.fromUnicode("📜")),
      Button.secondary(RespawnButtonId.boardMySettings, "My settings")
    )

  /** Previous/Next under a page of the claim log, and Find beside them.
   *
   *  Only the directions that lead somewhere are offered, so neither ever
   *  answers with the page you are already on. The log runs newest first, so
   *  Next goes backwards in time — which Newer/Older used to say outright and
   *  this pair does not, traded for the arrangement people expect under a page
   *  number.
   *
   *  Find is always there, including on a log with a single page and nothing to
   *  turn: a search is not a direction, and a one-page log is exactly where
   *  somebody is most likely to want a different one. That is also why this now
   *  always returns a row. */
  def logButtons(scope: LogScope, page: LogPage): ActionRow = {
    val buttons = List(
      if (page.hasNewer) Some(Button.secondary(RespawnButtonId.logPage(scope, page.page - 1), "Previous")) else None,
      if (page.hasOlder) Some(Button.secondary(RespawnButtonId.logPage(scope, page.page + 1), "Next")) else None,
      Some(Button.primary(RespawnButtonId.logFind, "Find").withEmoji(Emoji.fromUnicode("🔍")))
    ).flatten
    ActionRow.of(buttons.asJava)
  }

  /** The Yes/No pair on a "are you hunting tonight?" DM. */
  def slotAnswerButtons(guildId: String, claimId: Long): ActionRow =
    ActionRow.of(
      Button.success(RespawnButtonId.keepSlot(guildId, claimId), "Yes, I'm hunting"),
      Button.danger(RespawnButtonId.passSlot(guildId, claimId), "Not tonight")
    )

  /** What anybody can do about their bookings on a spawn: add one, or drop the
   *  ones they have.
   *
   *  One panel for everybody, moderator or not. Somebody pressing Book is asking
   *  what is already spoken for here, which is the same question whoever asks
   *  it, and two layouts for one question is one to learn twice. The moderator
   *  variant this replaces differed only in that its cancel cleared the whole
   *  spawn — a blunt instrument sitting exactly where a member's own cancel sits,
   *  which is the last place a button that touches everybody's bookings should
   *  be. Cancel here is always and only the presser's own.
   *
   *  One cancel, not one per booking. A member's bookings on a single spawn are
   *  one decision to them, and a button each turned the panel into a row of
   *  near-identical red buttons that had to be read to tell apart. Dropped
   *  entirely when they have nothing here, since a cancel with nothing to cancel
   *  is a button whose only answer is "you have no bookings".
   *
   *  Dashboard last, and a link button like the board's — Discord opens the URL
   *  itself and the press never reaches the bot, so it needs no id and no
   *  handler. It is the way out of what this panel cannot do: a modal asks for
   *  one start time in a box, where the week on the dashboard shows what is
   *  already taken and lets somebody point at a gap. Which is why it opens on
   *  *this* spawn rather than on the dashboard's front page. */
  def spawnBookingButtons(guildId: String, respawnId: Long, code: String, mine: Int): ActionRow = {
    val cancel =
      if (mine <= 0) Nil
      else List(Button.danger(RespawnButtonId.cancelSpawnBookings(respawnId),
        if (mine == 1) "Cancel Booking" else s"Cancel All $mine Bookings"))
    // The two ways to make a booking first, then the one way to undo one. Cancel
    // last also keeps the destructive button away from the one somebody reaches
    // for most, rather than between the pair that belong together.
    val buttons =
      Button.success(RespawnButtonId.bookAnother(respawnId), "Book")
        .withEmoji(Emoji.fromUnicode(BookEmoji)) ::
        Button.link(spawnDashboardLink(guildId, code), "Dashboard")
          .withEmoji(Emoji.fromUnicode(DashboardEmoji)) :: cancel
    ActionRow.of(buttons.asJava)
  }

  /** Under a moderator's /stamina. Everybody sees their own tank; a moderator
   *  also gets the one thing they might want to do about somebody else's. */
  def staminaButtons: ActionRow =
    ActionRow.of(Button.secondary(RespawnButtonId.giveStamina, "Give Stamina")
      .withEmoji(Emoji.fromUnicode("⚡")))

  /** Under a member's own /bookings list: the one button that clears the lot. */
  def bookingsButtons(count: Int): ActionRow =
    ActionRow.of(Button.danger(RespawnButtonId.cancelAllBookings,
      if (count == 1) "Cancel booking" else s"Cancel all $count bookings"))

  /** Under the whole-server list a moderator gets from /bookings. No cancel here:
   *  "cancel all" against everybody's bookings is not something anyone means to
   *  press, and cancelling one of somebody else's belongs on that spawn's own
   *  panel where the spawn is named. */
  def moderatorBookingsButtons: ActionRow =
    ActionRow.of(Button.secondary(RespawnButtonId.myBookings, "My bookings")
      .withEmoji(Emoji.fromUnicode("📅")))

  /** The single button on a booking's confirmation DMs.
   *
   *  `label` is the only difference between the two: **Confirm** on the reminder
   *  before a slot starts, where it is optional and settles the slot early, and
   *  **Take Claim** on the started hunt, where the hunt is lost without it. Both
   *  carry the same id — the claim's own status is what decides which question
   *  was answered — so a reminder pressed a minute late still works. */
  def confirmSlotButtons(guildId: String, claimId: Long, label: String): ActionRow =
    ActionRow.of(Button.success(RespawnButtonId.confirmSlot(guildId, claimId), label).withEmoji(claimEmoji))

  /** The Claim/Cancel pair on a handover offer DM. Cancel is styled as the
   *  destructive option because it drops them out of the queue entirely —
   *  exactly like leaving it. */
  def offerButtons(guildId: String, claimId: Long): ActionRow =
    ActionRow.of(
      Button.success(RespawnButtonId.accept(guildId, claimId), "Claim").withEmoji(claimEmoji),
      Button.danger(RespawnButtonId.decline(guildId, claimId), "Cancel")
    )

  // --- forum channel ------------------------------------------------------

  def findForum(guild: Guild, settings: RespawnSettings): Option[ForumChannel] =
    Option(settings.forumChannel)
      .filter(id => id.nonEmpty && id != "0")
      .flatMap(id => Option(guild.getForumChannelById(id)))

  /** Drop any @everyone override on the forum so it inherits from the parent
   *  category.
   *
   *  Called on create (a no-op on a fresh channel) and on repair, where it
   *  migrates forums that were built with an explicit override before this was
   *  the intent — an override left in place would keep winning over whatever the
   *  category says, which is exactly what inheriting is meant to avoid.
   *
   *  Note this also drops the old deny on creating posts, so members can open
   *  their own posts here if the category or server default allows it. The bot
   *  only ever manages posts it created, so a stray one is inert rather than
   *  harmful. */
  def inheritPublicPermissions(forum: ForumChannel, publicRole: Role): Unit =
    Option(forum.getPermissionOverride(publicRole)).foreach { existing =>
      Try(existing.delete().complete()).failed.foreach { error =>
        logger.warn(s"Could not clear the @everyone override on the respawn forum " +
          s"in guild '${forum.getGuild.getId}'", error)
      }
    }

  /** Delete the post that represents a spawn, for a spawn that no longer exists.
   *
   *  Archiving is what happens when a spawn merely goes free; this is for a code
   *  removed from the catalogue outright, where leaving the post would offer a
   *  Claim button for something nothing can resolve.
   *
   *  Best effort, and says so in the log rather than to the caller: the spawn is
   *  gone from the catalogue either way, and a post that outlives it is untidy
   *  rather than broken. */
  def deleteThread(guild: Guild, settings: RespawnSettings, threadId: String): Unit = {
    deleteThreads(guild, settings, List(threadId))
    ()
  }

  /** The same for a batch of removed spawns, returning how many posts actually
   *  went.
   *
   *  Not a loop over [[deleteThread]]. A spawn's post is archived for most of
   *  its life, and finding an archived post means paging the forum's archive —
   *  so a loop pays for that paging once per code retired, which for a seed file
   *  that has just split a dozen codes is a dozen times over the same pages.
   *  This reads the forum once and matches every id against it. */
  def deleteThreads(guild: Guild, settings: RespawnSettings, threadIds: List[String]): Int = {
    val wanted = threadIds.filter(_.nonEmpty).toSet
    if (wanted.isEmpty) 0
    else findForum(guild, settings).fold(0) { forum =>
      val live = forum.getThreadChannels.asScala.toList.filter(thread => wanted.contains(thread.getId))
      // The archive is only paged for what the cache could not answer. Most of a
      // batch of retired codes will be in there, but the single-post caller — a
      // moderator removing a spawn from the dashboard — is usually deleting one
      // somebody was just looking at, and that one is awake.
      val missing = wanted -- live.map(_.getId).toSet
      val archived =
        if (missing.isEmpty) Nil
        else archivedThreads(forum).filter(thread => missing.contains(thread.getId))
      (live ++ archived).count(deleteOne)
    }
  }

  /** Delete this bot's posts in the respawn forum that `keep` does not name,
   *  returning how many went.
   *
   *  The backstop under [[deleteThreads]], and the only way to reach a post
   *  whose catalogue row is already gone: nothing else records that the post
   *  exists, so a delete that failed — or a retirement from before it deleted
   *  posts at all — leaves a card in the forum that no id in the database
   *  points at. It is found by not being pointed at.
   *
   *  Three guards, because "delete what I don't recognise" is how a forum gets
   *  emptied by a bad read:
   *
   *   - the board post is kept whatever the caller passed, since losing it
   *     costs the guild the one place its codes are written down;
   *   - only posts this bot created are touched, so the posts members open in
   *     the forum themselves are not ours to tidy;
   *   - `limit` caps a pass, so a mistake takes a handful of posts and shows up
   *     in the log rather than clearing the channel in one boot.
   *
   *  A spawn whose post exists but whose row lost its `threadId` is deleted
   *  here too. That is the right outcome rather than a missed guard: the row can
   *  no longer find that post, so it would open a second one on the next claim
   *  and leave the first sitting there for ever. */
  def deleteUnknownThreads(guild: Guild, settings: RespawnSettings,
                           keep: Set[String], limit: Int): Int =
    findForum(guild, settings).fold(0) { forum =>
      val threads = allThreads(forum)
      val doomed = orphanIds(
        threads.map(thread => thread.getId -> thread.getOwnerId),
        keep + settings.boardThread,
        guild.getSelfMember.getId,
        limit).toSet
      threads.filter(thread => doomed.contains(thread.getId)).count { thread =>
        val gone = deleteOne(thread)
        if (gone) logger.info(s"Deleted the orphaned respawn post '${thread.getName}' " +
          s"in guild '${guild.getId}' — no catalogue row points at it")
        gone
      }
    }

  /** Which of a forum's posts, as `(id, ownerId)` pairs, are ours to delete.
   *
   *  Pure, and separate from the call that acts on it, because this is the part
   *  that can be wrong in a way nobody can undo: what the guards actually spare
   *  is checkable here without a Discord to point at.
   *
   *  An owner Discord did not give us reads as somebody else's, which spares the
   *  post. That is the direction to be wrong in — a post we skipped comes back
   *  round on the next sweep, and one we should not have deleted does not. */
  private[respawn] def orphanIds(threads: List[(String, String)], keep: Set[String],
                                 selfId: String, limit: Int): List[String] =
    threads.iterator
      .filterNot { case (id, _) => keep.contains(id) }
      .filter { case (_, ownerId) => ownerId != null && ownerId == selfId }
      .map { case (id, _) => id }
      .take(limit)
      .toList

  private def deleteOne(thread: ThreadChannel): Boolean =
    Try(thread.delete().complete()) match {
      case Success(_) => true
      case Failure(error) =>
        logger.warn(s"Could not delete the post for a removed spawn in guild " +
          s"'${thread.getGuild.getId}'", error)
        false
    }

  /** Every post the forum holds, archived ones included and deduplicated.
   *
   *  `getThreadChannels` is cache-only and lists the un-archived posts; a
   *  spawn's post spends most of its life on the other side of that, so the
   *  archive has to be paged as well. Bounded by [[ArchiveSearchLimit]], the
   *  same bound [[resolveThread]] works to — a post further back than that is
   *  old enough that leaving it one more boot costs nothing. */
  private def allThreads(forum: ForumChannel): List[ThreadChannel] =
    (forum.getThreadChannels.asScala.toList ++ archivedThreads(forum)).distinctBy(_.getId)

  /** The forum's archived posts, bounded by [[ArchiveSearchLimit]] and empty
   *  rather than fatal when Discord refuses — a sweep that could not read the
   *  archive should do less, not fail the boot it is running on. */
  private def archivedThreads(forum: ForumChannel): List[ThreadChannel] =
    Try(forum.retrieveArchivedPublicThreadChannels()
      .takeAsync(ArchiveSearchLimit).get().asScala.toList).toOption.getOrElse(Nil)

  /** Give the guild's moderator role a working set of powers over the spawns
   *  forum: see it, talk in a claim, and manage or delete posts when a thread
   *  needs cleaning up.
   *
   *  The only override the forum carries. @everyone is left to inherit from the
   *  bot's category (see [[inheritPublicPermissions]]), so this is what a
   *  moderator gets *on top* of whatever ordinary members have. Applied
   *  separately from [[createForum]] so `/repair` can hand the powers to a forum
   *  that already exists. */
  def grantModeratorAccess(forum: ForumChannel, moderatorRole: Role): Unit =
    Try(
      forum.upsertPermissionOverride(moderatorRole)
        .grant(Permission.VIEW_CHANNEL)
        .grant(Permission.MESSAGE_SEND_IN_THREADS)
        .grant(Permission.MESSAGE_HISTORY)
        .grant(Permission.MANAGE_THREADS)
        .complete()
    ).failed.foreach { error =>
      logger.warn(s"Could not grant the moderator role access to the respawn forum " +
        s"in guild '${forum.getGuild.getId}'", error)
    }

  /** Create the spawns forum under the bot's admin category and post the pinned
   *  board thread. Returns (forumChannelId, boardThreadId).
   *
   *  @param category the guild's existing "Violent Bot" category — the forum is
   *                  placed alongside the command-log and notifications channels
   *                  rather than in its own category.
   */
  def createForum(guild: Guild, category: Category, settings: RespawnSettings,
                  moderatorRole: Option[Role], spawns: List[Respawn]): (String, String) = {
    val botRole = guild.getBotRole
    val publicRole = guild.getPublicRole

    val forum = guild.createForumChannel(ChannelName, category)
      .setTopic("One post per respawn, showing who's on it and who's next. Every code is on the board post.")
      .setAvailableTags(tagSeeds.map { case (name, emoji) =>
        new ForumTagData(name).setEmoji(Emoji.fromUnicode(emoji))
      }.asJava)
      .complete()

    // The bot needs MANAGE_THREADS specifically: it archives a spawn's post when
    // the spawn goes free and un-archives it on the next claim, and pins/locks
    // the board post. The rest mirrors ChannelService.grantWorldPerms.
    forum.upsertPermissionOverride(botRole)
      .grant(Permission.VIEW_CHANNEL)
      .grant(Permission.MESSAGE_SEND)
      .grant(Permission.MESSAGE_SEND_IN_THREADS)
      .grant(Permission.CREATE_PUBLIC_THREADS)
      .grant(Permission.MESSAGE_EMBED_LINKS)
      .grant(Permission.MESSAGE_HISTORY)
      .grant(Permission.MANAGE_THREADS)
      .grant(Permission.MANAGE_CHANNEL)
      .complete()

    // No @everyone override at all: the forum inherits whatever the bot's
    // category grants ordinary members, so a server that adjusts access there
    // has it apply here too instead of being silently overridden per channel.
    inheritPublicPermissions(forum, publicRole)

    moderatorRole.foreach(grantModeratorAccess(forum, _))

    val boardId = postBoard(forum, settings, spawns)
    (forum.getId, boardId)
  }

  /** Where the board's Dashboard button sends a member.
   *
   *  Built from the origin members reach the dashboard at, not from the one this
   *  bot answers on — most of the fleet serves no dashboard and has no address
   *  of its own, and posting the board is not something only the bot with a
   *  dashboard does. See `Config.Web.dashboardOrigin`. */
  def dashboardLink: String = s"${com.tibiabot.Config.Web.dashboardOrigin}/dashboard"

  /** The same dashboard, opened on one spawn's card with its week already drawn.
   *
   *  The guild is named in the path so the link skips the server picker, which
   *  otherwise asks somebody in two guilds a question their own link already
   *  answered. The spawn rides in the query, by code: the page is keyed on codes
   *  throughout, a code is unique and nothing ever rewrites one, and it is the
   *  same word the panel this button sits on is titled with.
   *
   *  Encoded even though a code is only ever letters, digits and hyphens (see
   *  RespawnService.spawnFault) — the rule lives over there, and a link that
   *  quietly depends on it would break somewhere far from wherever it changed.
   */
  def spawnDashboardLink(guildId: String, code: String): String =
    s"$dashboardLink/g/$guildId?spawn=${java.net.URLEncoder.encode(code, "UTF-8")}"

  /** The text above the board, inside the card.
   *
   *  One line per button, in the order they sit underneath and each wearing the
   *  same emoji, so the list reads as a key to the row rather than as prose that
   *  happens to mention it. A label says what a button is called; these say what
   *  it is for, which is the part a member arriving at the board for the first
   *  time does not have. */
  def boardIntro: String =
    s"${com.tibiabot.Config.dailyEmoji} **Claim** **·** and type a code to claim a spawn right now\n" +
      s"$BookEmoji **Book** **·** to schedule/lock-in a hunt in the future (up to 12h)\n" +
      s"$DashboardEmoji **Dashboard** **·** if you want to book further in advance (webui)\n" +
      s"$ConfigEmoji **Config** **·** to change your default claim time & reminder settings"

  /** The board's card: the whole post bar the buttons.
   *
   *  The bot builds this itself rather than pasting the link and letting Discord
   *  unfurl `web.LinkPreview`'s page into a card of its own. Same shape, but this
   *  one can hold the board image, which the unfurled card cannot, and it appears
   *  with the message instead of whenever the crawler gets round to it.
   *
   *  The title is a second way to the dashboard, for anyone who reads a card
   *  before they look at a button — and it is a plain link rather than a masked
   *  one, so nothing here can unfurl a second card underneath the first.
   *
   *  Thumbnail and image are both set and are not the same picture: Discord puts
   *  the thumbnail small in the top corner (the bot's avatar, so the card is
   *  recognisably ours at a glance) and the image full width underneath, which is
   *  where the board goes. `attachment://` refers to the file uploaded with the
   *  message — the name has to match [[RespawnBoardImage.FileName]] exactly or
   *  the embed renders with an empty space where the board should be. */
  private def boardEmbed(hasImage: Boolean): MessageEmbed = {
    val embed = new EmbedBuilder()
      .setColor(Embeds.NemesisPurple)
      .setTitle("Respawn Claims", dashboardLink)
      .setDescription(boardIntro)
      .setThumbnail(com.tibiabot.Config.webHookAvatar)
    if (hasImage) embed.setImage(s"attachment://${RespawnBoardImage.FileName}")
    embed.build()
  }

  /** Post (or repost) the informational board thread, pinned to the top of the
   *  forum.
   *
   *  One message: the card and the buttons under it are both the post's starter
   *  message. It used to be two, because the dashboard link had to sit above the
   *  board and nothing can be inserted before a forum post's first message —
   *  which stops mattering once the link lives inside a card the bot builds.
   *  [[redrawBoard]] folds an older two-message board back into this shape.
   *
   *  Deliberately **not** locked, even though it is purely informational.
   *  Discord greys out message components for anyone who cannot post in the
   *  channel, and in a locked thread that is everybody without Manage Threads —
   *  so locking it left the Claim and Config buttons dead for exactly the
   *  ordinary members they exist for. There are no per-thread permission
   *  overrides to reach for, so read-only and working buttons cannot both be
   *  had; the buttons win, and a stray reply here is a moderator's tidy-up.
   *
   *  The longest auto-archive Discord offers, for the same reason: an archived
   *  thread's components are disabled too, and this post generates little
   *  activity of its own to keep the timer alive. [[refreshBoard]] revives it if
   *  it slips through anyway. */
  def postBoard(forum: ForumChannel, settings: RespawnSettings, spawns: List[Respawn]): String = {
    val post = forum.createForumPost("📅 Respawn Claims", boardMessage(spawns))
      .setAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.TIME_1_WEEK)
      .complete()
    val thread = post.getThreadChannel

    Try(thread.getManager.setPinned(true).complete()).failed.foreach { error =>
      logger.warn(s"Could not pin the respawn board post in guild '${forum.getGuild.getId}'", error)
    }
    thread.getId
  }

  /** The whole board post as one message: the card, and the buttons under it.
   *
   *  The image is best effort, which is why the card is told whether there is
   *  one. A board that fails to render still posts — a thread with the card and
   *  the buttons is one people can claim from, the codes are the part they are
   *  missing, and the next redraw brings them — but it posts without an
   *  `attachment://` pointing at a file that was never uploaded. */
  private def boardMessage(spawns: List[Respawn]) = {
    val png = RespawnBoardImage.render(spawns)
    val builder = new MessageCreateBuilder()
      .setEmbeds(boardEmbed(png.isDefined))
      .setComponents(boardButtons)
    png.foreach(bytes => builder.setFiles(FileUpload.fromData(bytes, RespawnBoardImage.FileName)))
    builder.build()
  }

  /** How far into the board thread [[legacyBoardMessages]] will look.
   *
   *  It is looking for the image message of a two-message board, which sat
   *  directly under the opening one, so a page of history is generous already —
   *  it only has to be deeper than the stray replies a moderator left in
   *  between. */
  private val BoardSearchLimit = 25

  /** The bot's own messages below the starter, which on a board built before the
   *  two were merged is where the image and its buttons live.
   *
   *  Only the bot's own, and only ones carrying an attachment or components, so
   *  a moderator's reply in the thread is never a candidate for deletion. */
  private def legacyBoardMessages(thread: ThreadChannel): List[Message] = {
    val self = thread.getJDA.getSelfUser.getId
    Try(thread.getHistoryAfter(thread.getId, BoardSearchLimit).complete()
      .getRetrievedHistory.asScala.toList)
      .recover { case error =>
        logger.warn(s"Could not read the respawn board thread '${thread.getId}'", error)
        Nil
      }
      .getOrElse(Nil)
      .filter(message => message.getAuthor.getId == self)
      .filter(message => !message.getAttachments.isEmpty || !message.getComponents.isEmpty)
  }

  /** Clear out the second message of an older two-message board.
   *
   *  Runs after the starter has been redrawn, never before: if the delete
   *  succeeds and the edit then fails, the thread has lost its board entirely,
   *  whereas this order can only ever leave one duplicate behind for the next
   *  redraw to find. Best effort for the same reason. */
  private def removeLegacyBoardMessages(thread: ThreadChannel): Unit =
    legacyBoardMessages(thread).foreach { message =>
      Try(message.delete().complete()).failed.foreach { error =>
        logger.warn(s"Could not remove the old board image message '${message.getId}' " +
          s"in thread '${thread.getId}'", error)
      }
    }

  /** Redraw an existing board in place, for a catalogue that has changed.
   *
   *  Edits the post's starter message rather than replacing the post, so the
   *  thread keeps its id, its pin and anything said in it. `setReplace` is what
   *  makes this the repair path for the layout too: everything not set here is
   *  cleared, so a board from any older build — one opening with a bare link and
   *  its unfurled card, or with the image and an embed above it — is edited into
   *  the current shape in a single call rather than needing to be recognised
   *  first. It is also what clears the previous attachment, which an edit that
   *  only adds files would keep, leaving two boards stacked in one message.
   *
   *  Returns whether it managed to. Nothing here is fatal: a board that fails to
   *  redraw is out of date, not broken, and the codes it shows still work. */
  def redrawBoard(guild: Guild, settings: RespawnSettings, spawns: List[Respawn]): Boolean =
    (for {
      forum <- findForum(guild, settings)
      thread <- resolveThread(guild, forum, settings.boardThread)
    } yield Try {
      RespawnBoardImage.render(spawns) match {
        case None => false
        case Some(png) =>
          thread.retrieveStartMessage().complete()
            .editMessage(new MessageEditBuilder()
                .setReplace(true)
                .setEmbeds(boardEmbed(hasImage = true))
                .setComponents(boardButtons)
                .setAttachments(FileUpload.fromData(png, RespawnBoardImage.FileName))
                .build())
            .complete()
          removeLegacyBoardMessages(thread)
          true
      }
    }.recover { case error =>
      logger.warn(s"Could not redraw the respawn board in guild '${guild.getId}'", error)
      false
    }.get).getOrElse(false)

  /** Put the board post back into a state where its buttons work. Called from the
   *  daily sweep; cheap and idempotent, since it only acts when something is
   *  actually wrong.
   *
   *  Both an archived thread and a locked one have their components greyed out
   *  for ordinary members, so both are undone here. The unlock also migrates
   *  boards created before that was understood — they were locked on purpose,
   *  and would otherwise stay unusable until somebody ran `/repair`. */
  def refreshBoard(guild: Guild, settings: RespawnSettings): Unit =
    findForum(guild, settings).foreach { forum =>
      resolveThread(guild, forum, settings.boardThread).foreach { thread =>
        if (thread.isArchived || thread.isLocked) {
          Try(
            thread.getManager
              .setArchived(false)
              .setLocked(false)
              .setPinned(true)
              .setAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.TIME_1_WEEK)
              .complete()
          ).failed.foreach { error =>
            logger.warn(s"Could not revive the respawn board post in guild '${guild.getId}'", error)
          }
        }
      }
    }

  // --- per-spawn posts ----------------------------------------------------

  /** Find a thread by id whether or not it's archived.
   *
   *  `getThreadChannelById` only sees threads JDA has cached, which excludes
   *  archived ones — and a spawn's post is archived for most of its life, since
   *  that's the state it's left in whenever nobody holds the spawn. So fall
   *  back to paging the forum's archived posts. That page is only reached when
   *  reviving an idle spawn, not on the hot path.
   */
  def resolveThread(guild: Guild, forum: ForumChannel, threadId: String): Option[ThreadChannel] =
    if (threadId.isEmpty || threadId == "0") None
    else Option(guild.getThreadChannelById(threadId)).orElse {
      // Bounded: a guild's forum could in principle hold thousands of archived
      // posts, and paging all of them to find one is not worth it — a spawn
      // that far down the archive is old enough that starting a fresh post is
      // the better outcome anyway.
      Try(forum.retrieveArchivedPublicThreadChannels().takeAsync(ArchiveSearchLimit).get().asScala.toList)
        .toOption
        .getOrElse(Nil)
        .find(_.getId == threadId)
    }

  /** How far back through a forum's archived posts [[resolveThread]] will look. */
  private val ArchiveSearchLimit = 500

  /** What a spawn's post is called: its display name, cut to what Discord will
   *  accept as a channel name.
   *
   *  Cut here rather than refused, so the comparison in [[openThread]] is against
   *  the title that would actually be set. A name Discord would shorten — or
   *  reject outright — is one the post could never match, and the rename would
   *  then fire again on every claim, for ever, one REST call at a time. Seed
   *  names are not length-checked the way a guild's own are (see
   *  `RespawnService.spawnFault`), so this is the guard for a long one arriving
   *  through respawns.json. */
  private val MaxThreadName = 100
  private[respawn] def threadTitle(respawn: Respawn): String =
    respawn.displayName.take(MaxThreadName)

  /** Get the spawn's post ready to show `card`, creating it on first claim and
   *  un-archiving it if the spawn has been idle. Returns the thread, or None if
   *  Discord refused (missing permission, deleted channel) — the caller keeps
   *  the claim either way, since the database is the source of truth and the
   *  post is a view of it.
   *
   *  `onCreated` reports a freshly created thread's id so the caller can store
   *  it on the catalogue row; it isn't called when an existing post is reused.
   *
   *  A reused post is also brought back into line with its spawn's name. The
   *  post is titled once, when it is created, and `respawns.json` renaming a
   *  spawn afterwards reaches the catalogue and the board picture on the next
   *  boot but never the title — so a renamed spawn kept the old name in the
   *  channel list indefinitely. Corrected here rather than by a pass of its own
   *  because this is the moment the thread is already being edited: on a
   *  sleeping spawn the un-archive and the rename travel as one call, where a
   *  sweep of its own would have to wake every renamed post to retitle it and
   *  then wait for the reconciler to put them all back to sleep.
   *
   *  A spawn nobody touches keeps its old title until somebody claims or books
   *  it. That is the trade for not waking the whole forum over a rename, and
   *  the board picture — which is where anybody actually reads a code — is
   *  right immediately either way.
   */
  def openThread(guild: Guild, forum: ForumChannel, respawn: Respawn, card: MessageEmbed,
                 buttons: ActionRow, onCreated: String => Unit): Option[OpenedThread] = {
    val existing = resolveThread(guild, forum, respawn.threadId)
    existing match {
      case Some(thread) =>
        val renaming = thread.getName != threadTitle(respawn)
        if (thread.isArchived || renaming) {
          val manager = thread.getManager
          val unarchived = if (thread.isArchived) manager.setArchived(false) else manager
          val edit = if (renaming) unarchived.setName(threadTitle(respawn)) else unarchived
          Try(edit.complete()).failed.foreach { error =>
            logger.warn(s"Could not open respawn thread '${respawn.code}' in guild '${guild.getId}'", error)
          }
        }
        Some(OpenedThread(thread, created = false))

      case None =>
        Try {
          val message = new MessageCreateBuilder().setEmbeds(card).setComponents(buttons).build()
          val post = forum.createForumPost(threadTitle(respawn), message).complete()
          val thread = post.getThreadChannel
          onCreated(thread.getId)
          OpenedThread(thread, created = true)
        }.toOption.orElse {
          logger.warn(s"Could not create a respawn thread for '${respawn.code}' in guild '${guild.getId}'")
          None
        }
    }
  }

  /** A spawn's post, and whether it had to be made. A post created a moment ago
   *  already carries the card it was created with, so there is nothing to
   *  rewrite on it. */
  final case class OpenedThread(thread: ThreadChannel, created: Boolean)

  /** Bring a spawn's post into line with what has just happened: its card, its
   *  status tag, and whether it should be awake.
   *
   *  These go out as one ordered chain rather than three requests fired side by
   *  side. Every one of them is asynchronous and each takes a different REST
   *  route, so nothing ordered them against one another — and Discord refuses to
   *  edit a message in, or retag, a thread that has already been archived.
   *  Queued alongside the edit, the archive could overtake it, and the post
   *  would be left showing the state from before whatever had just happened.
   *
   *  It only ever bit a spawn nobody holds, because that is the only time a post
   *  is put to sleep. Claiming one leaves it held and so always looked right;
   *  booking one leaves it unheld, which is why a booking was the thing that
   *  went missing from the card.
   *
   *  `card` is None for a post that was just created with it.
   */
  def settle(forum: ForumChannel, thread: ThreadChannel, card: Option[(MessageEmbed, ActionRow)],
             tagName: String, sleep: Boolean): Unit = {
    // Handed over rather than waited on. The card is the thing a person is
    // looking at, so it wants to be quick — but nothing the caller goes on to do
    // depends on the edit having landed, and waiting for it put a whole Discord
    // round trip between a button press and the reply to it.
    val failed: java.util.function.Consumer[_ >: Throwable] = (error: Throwable) =>
      logger.warn(s"Could not settle respawn thread '${thread.getId}'", error)
    Try {
      (card, postState(forum, thread, tagName, sleep)) match {
        case (Some((embed, buttons)), Some(state)) =>
          editCard(thread, embed, buttons).flatMap((_: net.dv8tion.jda.api.entities.Message) => state)
            .queue(_ => (), failed)
        case (Some((embed, buttons)), None) => editCard(thread, embed, buttons).queue(_ => (), failed)
        case (None, Some(state))            => state.queue(_ => (), failed)
        case (None, None)                   => ()
      }
    }.failed.foreach { error =>
      logger.warn(s"Could not ask to settle respawn thread '${thread.getId}'", error)
    }
  }

  /** A spawn's claim card, rewritten in place. A forum post's starter message
   *  shares the thread's id, so this needs no extra fetch to find it. */
  private def editCard(thread: ThreadChannel, card: MessageEmbed, buttons: ActionRow) =
    thread.editMessageEmbedsById(thread.getId, card).setComponents(buttons)

  /** The post's own state — its tag, and whether it sleeps — as a single
   *  request, or nothing at all when it is already as it should be. Both live on
   *  the same manager, so asking for both costs one PATCH rather than two.
   *
   *  A tag that is already exactly right is skipped: applying it again is a REST
   *  call that changes nothing, and the card is refreshed far more often than a
   *  spawn actually flips between taken and free. Tags come from JDA's cache, so
   *  the check itself costs nothing. A guild that deleted them just gets no tag
   *  rather than an error.
   */
  private def postState(forum: ForumChannel, thread: ThreadChannel,
                        tagName: String, sleep: Boolean): Option[ThreadChannelManager] = {
    val retag = forum.getAvailableTags.asScala
      .find(_.getName.equalsIgnoreCase(tagName))
      .filterNot(found => thread.getAppliedTags.asScala.map(_.getId) == Seq(found.getId))
    if (retag.isEmpty && !sleep) None
    else {
      val base = thread.getManager
      val tagged = retag.fold(base)(found => base.setAppliedTags(ForumTagSnowflake.fromId(found.getId)))
      Some(if (sleep) tagged.setArchived(true) else tagged)
    }
  }

  /** Put a spawn's post back to sleep on its own, without touching its card or
   *  its tag. Returns whether an archive was actually sent.
   *
   *  This is the debounced close — see [[RespawnSleep]] — so unlike [[settle]]
   *  it is waited on rather than handed over: it runs on the respawn sweep,
   *  where blocking is the established style and where the result is what says
   *  whether the post really did go to sleep. Nothing is redrawn, because
   *  nothing changed; the post is the same one the last press left behind, and
   *  the only thing wrong with it is that it is awake.
   *
   *  An already-archived post is not an error and costs no request — a post can
   *  reach its due time having been closed in the meantime by the spawn going
   *  free, or by Discord's own auto-archive. */
  def closeThread(thread: ThreadChannel): Boolean =
    if (thread.isArchived) false
    else Try {
      thread.getManager.setArchived(true).complete()
      true
    }.recover { case error =>
      logger.warn(s"Could not put respawn thread '${thread.getId}' back to sleep " +
        s"in guild '${thread.getGuild.getId}'", error)
      false
    }.getOrElse(false)

  /** The same, for a post known only by id.
   *
   *  Cache-only on purpose, where [[resolveThread]] would page the forum's
   *  archived posts to find one that is missing. Every post this is asked about
   *  is one somebody was clicking on moments ago, so an open one is certainly
   *  cached — and a miss means it is already archived or has been deleted,
   *  which is the outcome this wanted either way. Paging 500 archived posts to
   *  confirm that would be the whole cost of the feature. */
  def closeThread(guild: Guild, threadId: String): Boolean =
    Option(guild.getThreadChannelById(threadId)).exists(closeThread)

  /** The status tag a spawn should be showing right now. Deliberately binary —
   *  a spawn is either taken or available, and whether anyone happens to be
   *  waiting behind it isn't a state of the spawn. */
  def tagFor(claimed: Boolean): String = if (claimed) TagClaimed else TagFree

  // --- direct messages ----------------------------------------------------

  /** DM a user, optionally with buttons. Returns whether it was delivered.
   *
   *  Reminders and handover offers go to the person's DMs and nowhere else: a
   *  spawn's thread is kept clean of notices aimed at one member. Nothing here is
   *  fatal — a member can have DMs closed, or share no mutual guild — and an
   *  undeliverable handover offer simply lapses on schedule and passes to the
   *  next person, so a closed inbox costs its owner their turn but never wedges
   *  the spawn.
   */
  def dm(guild: Guild, userId: String, embed: MessageEmbed, buttons: Option[ActionRow] = None): Boolean =
    Try {
      val user = guild.getJDA.retrieveUserById(userId).complete()
      val channel = user.openPrivateChannel().complete()
      val action = channel.sendMessageEmbeds(embed)
      buttons.fold(action.complete())(row => action.setComponents(row).complete())
      true
    }.recover {
      case error =>
        logger.info(s"Could not DM user '$userId' in guild '${guild.getId}' " +
          s"(DMs closed, or no mutual guild): ${error.getMessage}")
        false
    }.getOrElse(false)

  // --- retirement ---------------------------------------------------------

  /** What a retired forum is renamed to, so it reads as history at a glance and
   *  can't be confused with a live one if the bot is set up again later. */
  val RetiredChannelName: String = "🗄️・sᴘᴀᴡɴs-ᴀʀᴄʜɪᴠᴇ"

  /** Close the forum down but keep it: archive whatever is still open, post a
   *  closing notice, then rename it, lift it out of the bot's category and make
   *  it read-only.
   *
   *  Used when the guild's last world is removed. Nothing is deleted — the
   *  point is that a server keeps its hunt history — but the channel stops
   *  being something the bot owns or writes to.
   */
  def retireForum(guild: Guild, forum: ForumChannel, notice: String): Unit = {
    // Only the threads Discord still considers active need closing, and those
    // are already in JDA's cache — so this costs one REST call per *open*
    // claim, not one per catalogue entry.
    forum.getThreadChannels.asScala.filterNot(_.isArchived).foreach { thread =>
      Try(thread.getManager.setArchived(true).complete()).failed.foreach { error =>
        logger.warn(s"Could not archive respawn thread '${thread.getId}' while retiring the forum", error)
      }
    }

    Try {
      val message = new MessageCreateBuilder().addContent(notice).build()
      val post = forum.createForumPost("⚠️ Respawn tracking has been removed", message).complete()
      post.getThreadChannel.getManager.setLocked(true).setPinned(true).complete()
    }.failed.foreach { error =>
      logger.warn(s"Could not post the closing notice in guild '${guild.getId}'", error)
    }

    // Out of the "Violent Bot" category before the caller deletes it. Deleting
    // a category doesn't delete its channels, it orphans them to the top of the
    // server — doing it explicitly makes that deliberate rather than a surprise.
    Try(forum.getManager.setName(RetiredChannelName).setParent(null).complete()).failed.foreach { error =>
      logger.warn(s"Could not rename/move the retired respawn forum in guild '${guild.getId}'", error)
    }

    // Read-only from here: the history stays visible, but nobody (including the
    // bot) is meant to keep using it.
    Try(
      forum.upsertPermissionOverride(guild.getPublicRole)
        .grant(Permission.VIEW_CHANNEL)
        .grant(Permission.MESSAGE_HISTORY)
        .deny(Permission.CREATE_PUBLIC_THREADS)
        .deny(Permission.MESSAGE_SEND_IN_THREADS)
        .complete()
    ).failed.foreach { error =>
      logger.warn(s"Could not lock down the retired respawn forum in guild '${guild.getId}'", error)
    }
  }
}

/** Encoding for the respawn buttons' component ids.
 *
 *  Discord gives back only the component id on a click, so the spawn it belongs
 *  to has to be carried in the id itself. The `respawn:` prefix is what lets
 *  `interactions.ButtonHandler` route the whole family to one place instead of
 *  growing its if/else chain by four more branches.
 */
object RespawnButtonId {
  val Prefix: String = "respawn:"

  /** How BotListener must acknowledge a press before queueing it. */
  sealed trait Ack
  object Ack {
    /** Cannot be acknowledged early at all — `replyModal` has to be the
     *  interaction's first response. */
    case object OpensModal extends Ack
    /** Rewrites the message it was pressed on, so it defers an edit. */
    case object EditsMessage extends Ack
    /** Answers with a new ephemeral message. */
    case object Replies extends Ack
  }

  def claim(respawnId: Long): String = s"${Prefix}claim:$respawnId"
  def next(respawnId: Long): String = s"${Prefix}next:$respawnId"
  def leave(respawnId: Long): String = s"${Prefix}leave:$respawnId"
  def release(respawnId: Long): String = s"${Prefix}release:$respawnId"

  /** Handover-offer buttons carry the guild id as well as the claim id.
   *
   *  They are pressed in a DM, where `event.getGuild` is null — and claims live
   *  in per-guild databases, so without the guild in the id there would be no
   *  way to know which database the claim belongs to. Well within Discord's
   *  100-character component-id limit. */
  /** The board post's buttons carry no id of their own — there is only ever one
   *  board per guild, and the guild comes from the interaction. */
  val boardClaim: String = s"${Prefix}board:claim"
  val boardConfig: String = s"${Prefix}board:config"
  /** Book from the board rather than from a spawn's own post — the form asks
   *  which spawn instead of knowing it. */
  val boardBook: String = s"${Prefix}board:book"

  /** A page of the claim log. Opening it and paging through it are the same id
   *  shape, so a press is handled one way wherever it came from — see
   *  [[LogScope.token]] for what the middle part can say. */
  def logPage(scope: LogScope, page: Int): String = s"${Prefix}log:${scope.token}:$page"

  /** Search the log. Carries no scope of its own: it produces one rather than
   *  paging an existing one, so which log it was pressed on does not matter. */
  val logFind: String = s"${Prefix}logfind"

  /** Config on a spawn's own card, for whoever holds it or is waiting on it. */
  def spawnConfig(respawnId: Long): String = s"${Prefix}config:$respawnId"

  /** Ask the owner of a booked slot whether they are actually hunting it. */
  /** The owner's answer, pressed in a DM — so it carries the guild. */
  def keepSlot(guildId: String, claimId: Long): String = s"${Prefix}keepslot:$guildId:$claimId"
  def passSlot(guildId: String, claimId: Long): String = s"${Prefix}passslot:$guildId:$claimId"

  /** Book, or cancel, a repeating slot on this spawn. */
  def spawnSchedule(respawnId: Long): String = s"${Prefix}schedule:$respawnId"
  /** Open the booking form itself — the panel's Book Yourself. A separate id
   *  from `schedule` because that one opens the panel — pressing it again would
   *  only reopen what you are looking at. */
  def bookAnother(respawnId: Long): String = s"${Prefix}booknew:$respawnId"
  def cancelSchedule(scheduleId: Long): String = s"${Prefix}unschedule:$scheduleId"
  /** Drop every booking the presser has on one spawn — so this one carries the
   *  *respawn* id, unlike `cancelSchedule` above. */
  def cancelSpawnBookings(respawnId: Long): String = s"${Prefix}unschedules:$respawnId"
  /** Clear *everybody's* bookings on one spawn — a moderator action, so it is a
   *  different id from the one that clears only the presser's.
   *
   *  Nothing draws this any more: the Book panel is one panel for everybody now
   *  (see [[RespawnThreads.spawnBookingButtons]]) and its cancel is always the
   *  presser's own. Kept, with its handler, so a panel still sitting in somebody's
   *  scrollback from before that answers rather than erroring — and because a
   *  moderator wanting to clear a spawn outright has nowhere else to press. */
  def cancelSpawnAll(respawnId: Long): String = s"${Prefix}unschedulesall:$respawnId"

  /** Moderator actions reached from a spawn's Config panel. */
  def holderConfig(respawnId: Long): String = s"${Prefix}holdercfg:$respawnId"
  /** This spawn's own ceiling on claim length, rather than the guild's. */
  def spawnMax(respawnId: Long): String = s"${Prefix}spawnmax:$respawnId"
  def forceLeave(respawnId: Long): String = s"${Prefix}forceleave:$respawnId"
  /** The caller's own duration, offered alongside the moderator actions when they
   *  have a claim of their own on the spawn. */
  def selfConfig(respawnId: Long): String = s"${Prefix}selfcfg:$respawnId"

  /** Board Config panel choices. */
  val boardMySettings: String = s"${Prefix}board:mysettings"
  /** Clear every booking the presser has in the guild — pressed from /bookings,
   *  which names no spawn, so the id carries nothing either. */
  val cancelAllBookings: String = s"${Prefix}board:cancelall"
  /** A moderator stepping from the whole-server list to their own. */
  val myBookings: String = s"${Prefix}board:mybookings"
  /** A moderator handing stamina to somebody, from /stamina. */
  val giveStamina: String = s"${Prefix}board:givestamina"
  val boardClaimRules: String = s"${Prefix}board:claimrules"
  /** Flips the guild's autoclaim on or off. A button rather than a field in the
   *  Claim rules form because that form is at Discord's five components exactly
   *  and has no sixth slot — see
   *  [[com.tibiabot.interactions.RespawnModals.claimRulesModal]]. */
  val boardAutoClaim: String = s"${Prefix}board:autoclaim"

  /** Modal ids, kept next to the buttons that open them. */
  val ModalPrefix: String = s"${Prefix}modal:"
  val modalClaim: String = s"${ModalPrefix}claim"
  val modalConfig: String = s"${ModalPrefix}config"
  /** Carry the spawn, since a modal submission arrives with no memory of which
   *  card opened it. */
  def modalDuration(respawnId: Long): String = s"${ModalPrefix}duration:$respawnId"
  def modalSchedule(respawnId: Long): String = s"${ModalPrefix}schedule:$respawnId"
  /** The board's booking form. Carries no spawn id — which spawn is a field in
   *  the form itself, so it cannot be known when the modal is built. */
  val modalBoardSchedule: String = s"${ModalPrefix}boardschedule"
  def modalHolderDuration(respawnId: Long): String = s"${ModalPrefix}holder:$respawnId"
  def modalSpawnMax(respawnId: Long): String = s"${ModalPrefix}spawnmax:$respawnId"

  /** Every guild-wide setting, in one modal — exactly the five Discord allows. */
  val modalClaimRules: String = s"${ModalPrefix}claimrules"
  /** The claim log's search form. */
  val modalLogFind: String = s"${ModalPrefix}logfind"
  val modalGiveStamina: String = s"${ModalPrefix}givestamina"

  /** ("duration", 415L) from "respawn:modal:duration:415" — the kind and the spawn
   *  it applies to, for the modals that name one. */
  def parseSpawnModal(modalId: String): Option[(String, Long)] =
    if (!modalId.startsWith(ModalPrefix)) None
    else modalId.stripPrefix(ModalPrefix).split(':') match {
      case Array(kind, id) => Try(id.toLong).toOption.map(kind -> _)
      case _               => None
    }

  /** Leave, pressed from a DM — so it carries the guild the claim belongs to.
   *
   *  Nothing builds this any more: the claim-ending reminder used to carry it and
   *  no longer does. The id and its handler stay because reminders already sent
   *  are still sitting in inboxes, and a button that still works beats one that
   *  errors months later. */
  def dmLeave(guildId: String, respawnId: Long): String = s"${Prefix}dmleave:$guildId:$respawnId"

  def accept(guildId: String, claimId: Long): String = s"${Prefix}accept:$guildId:$claimId"
  def decline(guildId: String, claimId: Long): String = s"${Prefix}decline:$guildId:$claimId"

  /** One id for both confirmation prompts — the reminder's Confirm and the
   *  started hunt's Take Claim. They ask the same question at two moments, and
   *  the claim's own status is what tells them apart, so a single id keeps a
   *  reminder pressed late (after the slot has started) working rather than
   *  answering "out of date". */
  def confirmSlot(guildId: String, claimId: Long): String = s"${Prefix}confirmslot:$guildId:$claimId"

  def handles(componentId: String): Boolean = componentId.startsWith(Prefix)

  /** What a respawn button press means. Parsing to an ADT rather than a tuple
   *  keeps the two id shapes from being confused: the trailing number is a
   *  *respawn* id for the in-thread buttons and a *claim* id for the offer
   *  buttons, which is exactly the sort of thing a bare `(String, Long)` invites
   *  a caller to get wrong. */
  sealed trait Action
  /** An in-thread button on a spawn's claim card; the guild comes from the event. */
  final case class SpawnButton(action: String, respawnId: Long) extends Action
  /** A button on the pinned board post — "claim" or "config". */
  final case class BoardButton(what: String) extends Action
  /** A spawn button pressed from a DM, which is why it names its own guild. */
  final case class DmSpawnButton(action: String, guildId: String, respawnId: Long) extends Action
  /** A booked slot's owner answering whether they are hunting it, from a DM. */
  final case class SlotAnswerButton(keep: Boolean, guildId: String, claimId: Long) extends Action
  /** A booked slot's owner confirming they are there — from the reminder before
   *  it starts, or from the started-hunt DM after. */
  final case class ConfirmSlotButton(guildId: String, claimId: Long) extends Action
  /** A Claim/Cancel button on a handover offer DM. */
  final case class OfferButton(accept: Boolean, guildId: String, claimId: Long) extends Action
  /** A page of the claim log, for whatever it is scoped to. */
  final case class LogButton(scope: LogScope, page: Int) extends Action
  /** The claim log's Find button, which opens the search form. */
  case object LogFindButton extends Action

  /** Actions that always end in a modal, and so must not be deferred. The two
   *  Config buttons are absent deliberately: what they open depends on whether
   *  the presser is a moderator, so they decide after a single role lookup. */
  private val ModalActions: Set[String] =
    Set("claim", "book", "mysettings", "claimrules", "selfcfg", "holdercfg", "schedule",
        "booknew", "givestamina", "spawnmax")

  /** Whether this press has to answer with a modal, and so cannot be
   *  acknowledged up front — `replyModal` must be an interaction's first
   *  response. An unparseable id reads as "no", which is what lets the
   *  out-of-date-button reply be sent through the hook.
   *
   *  Lives here, beside [[parse]], because it is pure id classification — no
   *  Config, no database, no JDA. That is what lets BotListener call it on
   *  JDA's event thread and defer there, so a press is acknowledged before it
   *  ever queues for a worker. Acknowledging inside the handler instead left
   *  it waiting on a free thread in a pool shared with `/setup`, so a press
   *  could blow Discord's three-second window without any of its own work
   *  being slow. */
  def opensModal(componentId: String): Boolean = ackFor(componentId) == Ack.OpensModal

  /** How a press must be acknowledged, decided from its id alone.
   *
   *  Three answers rather than two, because the log's pages rewrite the message
   *  they were pressed on instead of sending a new one — acknowledging those
   *  with `deferReply` would stack a fresh ephemeral log per click rather than
   *  turning the page. */
  def ackFor(componentId: String): Ack =
    parse(componentId) match {
      case Some(BoardButton(what)) if what == "config" || ModalActions.contains(what) => Ack.OpensModal
      case Some(SpawnButton(action, _)) if action == "config" || ModalActions.contains(action) => Ack.OpensModal
      // Autoclaim is a toggle drawn on the panel it redraws, so it rewrites its
      // own message for the same reason the log's pages do. Replying instead
      // left the panel that was pressed sitting there with the stale label and
      // the stale field, and put a second panel underneath it — one more per
      // press, all but the last of them wrong.
      case Some(BoardButton("autoclaim")) => Ack.EditsMessage
      // Find sits on a log message but must not be deferred at all: it answers
      // with a modal, and `replyModal` has to be an interaction's first response.
      // It is listed before LogButton for exactly that reason.
      case Some(LogFindButton)   => Ack.OpensModal
      case Some(LogButton(_, _)) => Ack.EditsMessage
      case _                     => Ack.Replies
    }

  /** None for anything malformed, so a button left over from an older deploy is
   *  ignored rather than throwing. */
  def parse(componentId: String): Option[Action] =
    componentId.stripPrefix(Prefix).split(':') match {
      case Array("board", what) => Some(BoardButton(what))
      case Array("dmleave", guildId, respawnId) =>
        Try(respawnId.toLong).toOption.map(DmSpawnButton("leave", guildId, _))
      case Array("keepslot", guildId, claimId) =>
        Try(claimId.toLong).toOption.map(SlotAnswerButton(keep = true, guildId, _))
      case Array("passslot", guildId, claimId) =>
        Try(claimId.toLong).toOption.map(SlotAnswerButton(keep = false, guildId, _))
      case Array("confirmslot", guildId, claimId) =>
        Try(claimId.toLong).toOption.map(ConfirmSlotButton(guildId, _))
      case Array("accept", guildId, claimId) =>
        Try(claimId.toLong).toOption.map(OfferButton(accept = true, guildId, _))
      case Array("decline", guildId, claimId) =>
        Try(claimId.toLong).toOption.map(OfferButton(accept = false, guildId, _))
      case Array("logfind") => Some(LogFindButton)
      case Array("log", target, page) =>
        Try(page.toInt).toOption.flatMap(p => LogScope.fromToken(target).map(LogButton(_, p)))
      case Array(action, id) =>
        Try(id.toLong).toOption.map(SpawnButton(action, _))
      case _ => None
    }
}
