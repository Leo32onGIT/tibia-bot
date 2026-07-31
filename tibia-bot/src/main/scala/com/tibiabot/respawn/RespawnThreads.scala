package com.tibiabot.respawn

import com.tibiabot.domain.{Respawn, RespawnSchedule, RespawnSettings}
import com.tibiabot.presentation.{RespawnBoardImage, RespawnEmbeds}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.channel.concrete.{Category, ForumChannel, ThreadChannel}
import net.dv8tion.jda.api.entities.channel.forums.{ForumTag, ForumTagData, ForumTagSnowflake}
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed, Role}
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.{MessageCreateBuilder, MessageEditBuilder}

import scala.jdk.CollectionConverters._
import scala.util.Try

/** Everything the respawn system does to Discord: the forum channel, the board
 *  post, and the one reused post per spawn.
 *
 *  Split out from [[RespawnService]] so the claim rules stay separable from the
 *  JDA calls. Calls here are blocking (`.complete()`) in the same style as
 *  `setup.ChannelService`, because each step depends on the previous one's id —
 *  callers are expected to be on the slash-command pool or the respawn sweep's
 *  own thread, never on JDA's event thread or the Akka dispatcher.
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
        Button.secondary(RespawnButtonId.spawnSchedule(respawnId), "Book").withEmoji(Emoji.fromUnicode("📅")),
        Button.secondary(RespawnButtonId.spawnConfig(respawnId), "Config").withEmoji(Emoji.fromUnicode("⚙️")),
        Button.danger(RespawnButtonId.leave(respawnId), "Leave")
      )
    else
      List(
        Button.success(RespawnButtonId.claim(respawnId), "Claim").withEmoji(claimEmoji),
        Button.secondary(RespawnButtonId.spawnSchedule(respawnId), "Book").withEmoji(Emoji.fromUnicode("📅"))
      )

  /** The buttons on the pinned board post, which is what makes the whole system
   *  usable without touching a slash command: a spawn with no post yet can't
   *  have a Claim button of its own, so the board carries one. */
  def boardButtons: ActionRow =
    ActionRow.of(
      Button.success(RespawnButtonId.boardClaim, "Claim").withEmoji(claimEmoji),
      Button.secondary(RespawnButtonId.boardConfig, "Config").withEmoji(Emoji.fromUnicode("⚙️"))
    )

  /** The Config panel a moderator gets from a spawn's card, instead of going
   *  straight to their own duration. `ownClaim` adds a button for their own claim
   *  when they have one, since the moderator actions target whoever holds the
   *  spawn — which may not be them. */
  def spawnModeratorButtons(respawnId: Long, hasHolder: Boolean, ownClaim: Boolean): Option[ActionRow] = {
    val buttons = List(
      if (hasHolder) Some(Button.primary(RespawnButtonId.holderConfig(respawnId), "Edit Claim")) else None,
      if (hasHolder) Some(Button.danger(RespawnButtonId.forceLeave(respawnId), "Cancel Claim")) else None,
      if (ownClaim) Some(Button.secondary(RespawnButtonId.selfConfig(respawnId), "My Defaults")) else None
    ).flatten
    // The Collection overload, not the varargs one: `: _*` doesn't apply to a
    // Java method whose first parameter is a single component.
    if (buttons.isEmpty) None else Some(ActionRow.of(buttons.asJava))
  }

  /** The Config panel a moderator gets from the board: their own settings, or the
   *  server's rules. */
  def boardModeratorButtons: ActionRow =
    ActionRow.of(
      Button.secondary(RespawnButtonId.boardMySettings, "My settings"),
      Button.primary(RespawnButtonId.boardClaimRules, "Claim rules"),
      Button.primary(RespawnButtonId.boardTimers, "Timers")
    )

  /** The Yes/No pair on a "are you hunting tonight?" DM. */
  def slotAnswerButtons(guildId: String, claimId: Long): ActionRow =
    ActionRow.of(
      Button.success(RespawnButtonId.keepSlot(guildId, claimId), "Yes, I'm hunting"),
      Button.danger(RespawnButtonId.passSlot(guildId, claimId), "Not tonight")
    )

  /** What somebody with bookings on a spawn can do: add another slot, or drop
   *  the ones they have.
   *
   *  One cancel, not one per booking. A member's bookings on a single spawn are
   *  one decision to them, and a button each turned the panel into a row of
   *  near-identical red buttons that had to be read to tell apart. */
  def scheduleButtons(schedules: List[RespawnSchedule], respawnId: Long): ActionRow =
    ActionRow.of(
      Button.success(RespawnButtonId.bookAnother(respawnId), "Book Another")
        .withEmoji(Emoji.fromUnicode("📅")),
      Button.danger(RespawnButtonId.cancelSpawnBookings(respawnId),
        if (schedules.size == 1) "Cancel Booking" else s"Cancel All ${schedules.size} Bookings")
    )

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

  /** What a moderator gets under the same panel: a way to book for themselves,
   *  and a way to clear the spawn.
   *
   *  The panel itself is identical to a member's — same title, same state, same
   *  list of who has what — because a moderator looking at a spawn is asking the
   *  same question as anybody else, and two layouts for one question is one to
   *  learn twice. Only the buttons differ, which is the only part that actually
   *  does differ. */
  def moderatorSpawnBookingButtons(respawnId: Long, bookings: Int): ActionRow = {
    val clear =
      if (bookings <= 0) Nil
      else List(Button.danger(RespawnButtonId.cancelSpawnAll(respawnId),
        if (bookings == 1) "Cancel Booking" else s"Cancel All $bookings Bookings"))
    ActionRow.of((Button.success(RespawnButtonId.bookAnother(respawnId), "Book Yourself")
      .withEmoji(Emoji.fromUnicode("📆")) :: clear).asJava)
  }

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

  /** Post (or repost) the informational board thread, pinned to the top of the
   *  forum.
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
    // The image is the whole post: no embed above it, because everything one
    // would have said is either on the image or on the buttons under it.
    val message = new MessageCreateBuilder().setComponents(boardButtons)
    RespawnBoardImage.render(spawns)
      .foreach(png => message.setFiles(FileUpload.fromData(png, RespawnBoardImage.FileName)))

    val post = forum.createForumPost("📅 Respawn Claims", message.build())
      .setAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.TIME_1_WEEK)
      .complete()
    val thread = post.getThreadChannel

    Try(thread.getManager.setPinned(true).complete()).failed.foreach { error =>
      logger.warn(s"Could not pin the respawn board post in guild '${forum.getGuild.getId}'", error)
    }
    thread.getId
  }

  /** Redraw an existing board in place, for a catalogue that has changed.
   *
   *  Edits the post's own opening message rather than replacing the post, so the
   *  thread keeps its id, its pin and anything said in it. The old attachment has
   *  to be cleared explicitly — an edit that only adds files keeps the ones
   *  already there, which would leave two boards stacked in one message.
   *
   *  Returns whether it managed to. Nothing here is fatal: a board that fails to
   *  redraw is out of date, not broken, and the codes it shows still work. */
  def redrawBoard(guild: Guild, settings: RespawnSettings, spawns: List[Respawn]): Boolean =
    (for {
      forum <- findForum(guild, settings)
      thread <- resolveThread(guild, forum, settings.boardThread)
      png <- RespawnBoardImage.render(spawns)
    } yield Try {
      val start = thread.retrieveStartMessage().complete()
      start.editMessage(new MessageEditBuilder()
          // Cleared explicitly: a board posted by an older build carries an embed
          // above the image, and an edit that only sets the attachment keeps it.
          .setEmbeds(java.util.Collections.emptyList[MessageEmbed]())
          .setComponents(boardButtons)
          .setAttachments(FileUpload.fromData(png, RespawnBoardImage.FileName))
          .build())
        .complete()
      true
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

  /** Get the spawn's post ready to show `card`, creating it on first claim and
   *  un-archiving it if the spawn has been idle. Returns the thread, or None if
   *  Discord refused (missing permission, deleted channel) — the caller keeps
   *  the claim either way, since the database is the source of truth and the
   *  post is a view of it.
   *
   *  `onCreated` reports a freshly created thread's id so the caller can store
   *  it on the catalogue row; it isn't called when an existing post is reused.
   */
  def openThread(guild: Guild, forum: ForumChannel, respawn: Respawn, card: MessageEmbed,
                 buttons: ActionRow, onCreated: String => Unit): Option[ThreadChannel] = {
    val existing = resolveThread(guild, forum, respawn.threadId)
    existing match {
      case Some(thread) =>
        if (thread.isArchived) {
          Try(thread.getManager.setArchived(false).complete()).failed.foreach { error =>
            logger.warn(s"Could not un-archive respawn thread '${respawn.code}' in guild '${guild.getId}'", error)
          }
        }
        updateCard(thread, card, buttons)
        Some(thread)

      case None =>
        Try {
          val message = new MessageCreateBuilder().setEmbeds(card).setComponents(buttons).build()
          val post = forum.createForumPost(respawn.displayName, message).complete()
          val thread = post.getThreadChannel
          onCreated(thread.getId)
          thread
        }.toOption.orElse {
          logger.warn(s"Could not create a respawn thread for '${respawn.code}' in guild '${guild.getId}'")
          None
        }
    }
  }

  /** Rewrite a spawn's claim card in place. A forum post's starter message
   *  shares the thread's id, so this needs no extra fetch to find it. */
  def updateCard(thread: ThreadChannel, card: MessageEmbed, buttons: ActionRow): Unit =
    Try(thread.editMessageEmbedsById(thread.getId, card).setComponents(buttons).complete()).failed.foreach { error =>
      logger.warn(s"Could not update the claim card in thread '${thread.getId}'", error)
    }

  /** Put the spawn's post to sleep once nobody holds it. Not locked — a locked
   *  thread needs MANAGE_THREADS to reopen and would also stop people leaving
   *  notes on a spawn between hunts. */
  def archive(thread: ThreadChannel): Unit =
    Try(thread.getManager.setArchived(true).complete()).failed.foreach { error =>
      logger.warn(s"Could not archive respawn thread '${thread.getId}'", error)
    }

  /** Swap a post's status tag. Tags are looked up by name on the parent forum,
   *  so a guild that deleted them just gets no tag rather than an error. */
  def applyTag(forum: ForumChannel, thread: ThreadChannel, tagName: String): Unit = {
    val tag: Option[ForumTag] = forum.getAvailableTags.asScala.find(_.getName.equalsIgnoreCase(tagName))
    tag.foreach { found =>
      // Skip when the thread already carries exactly this tag. Applying it again
      // is a REST call that changes nothing, and the card is refreshed far more
      // often than a spawn actually flips between taken and free. The current
      // tags come from JDA's cache, so the check itself costs nothing.
      val alreadyApplied = thread.getAppliedTags.asScala.map(_.getId) == Seq(found.getId)
      if (!alreadyApplied) {
        Try(thread.getManager.setAppliedTags(ForumTagSnowflake.fromId(found.getId)).complete()).failed.foreach { error =>
          logger.warn(s"Could not apply the '$tagName' tag to thread '${thread.getId}'", error)
        }
      }
    }
  }

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

  /** Config on a spawn's own card, for whoever holds it or is waiting on it. */
  def spawnConfig(respawnId: Long): String = s"${Prefix}config:$respawnId"

  /** Ask the owner of a booked slot whether they are actually hunting it. */
  /** The owner's answer, pressed in a DM — so it carries the guild. */
  def keepSlot(guildId: String, claimId: Long): String = s"${Prefix}keepslot:$guildId:$claimId"
  def passSlot(guildId: String, claimId: Long): String = s"${Prefix}passslot:$guildId:$claimId"

  /** Book, or cancel, a repeating slot on this spawn. */
  def spawnSchedule(respawnId: Long): String = s"${Prefix}schedule:$respawnId"
  /** Book a *further* slot on a spawn you already have one on. A separate id
   *  from `schedule` because that one now opens the panel — pressing it again
   *  would only reopen what you are looking at. */
  def bookAnother(respawnId: Long): String = s"${Prefix}booknew:$respawnId"
  def cancelSchedule(scheduleId: Long): String = s"${Prefix}unschedule:$scheduleId"
  /** Drop every booking the presser has on one spawn — so this one carries the
   *  *respawn* id, unlike `cancelSchedule` above. */
  def cancelSpawnBookings(respawnId: Long): String = s"${Prefix}unschedules:$respawnId"
  /** Clear *everybody's* bookings on one spawn — a moderator action, so it is a
   *  different id from the one that clears only the presser's. */
  def cancelSpawnAll(respawnId: Long): String = s"${Prefix}unschedulesall:$respawnId"

  /** Moderator actions reached from a spawn's Config panel. */
  def holderConfig(respawnId: Long): String = s"${Prefix}holdercfg:$respawnId"
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
  val boardTimers: String = s"${Prefix}board:timers"

  /** Modal ids, kept next to the buttons that open them. */
  val ModalPrefix: String = s"${Prefix}modal:"
  val modalClaim: String = s"${ModalPrefix}claim"
  val modalConfig: String = s"${ModalPrefix}config"
  /** Carry the spawn, since a modal submission arrives with no memory of which
   *  card opened it. */
  def modalDuration(respawnId: Long): String = s"${ModalPrefix}duration:$respawnId"
  def modalSchedule(respawnId: Long): String = s"${ModalPrefix}schedule:$respawnId"
  def modalHolderDuration(respawnId: Long): String = s"${ModalPrefix}holder:$respawnId"

  /** Guild-wide settings, split across two modals because Discord caps a modal at
   *  five inputs and there are six settings. */
  val modalClaimRules: String = s"${ModalPrefix}claimrules"
  val modalTimers: String = s"${ModalPrefix}timers"
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
  /** A Claim/Cancel button on a handover offer DM. */
  final case class OfferButton(accept: Boolean, guildId: String, claimId: Long) extends Action

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
      case Array("accept", guildId, claimId) =>
        Try(claimId.toLong).toOption.map(OfferButton(accept = true, guildId, _))
      case Array("decline", guildId, claimId) =>
        Try(claimId.toLong).toOption.map(OfferButton(accept = false, guildId, _))
      case Array(action, id) =>
        Try(id.toLong).toOption.map(SpawnButton(action, _))
      case _ => None
    }
}
