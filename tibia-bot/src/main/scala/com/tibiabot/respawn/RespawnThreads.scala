package com.tibiabot.respawn

import com.tibiabot.domain.{Respawn, RespawnSettings}
import com.tibiabot.presentation.RespawnEmbeds
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.channel.concrete.{Category, ForumChannel, ThreadChannel}
import net.dv8tion.jda.api.entities.channel.forums.{ForumTag, ForumTagData, ForumTagSnowflake}
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder

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

  private val tagSeeds: List[(String, String)] =
    List(TagFree -> "🟢", TagClaimed -> "🔴")

  /** Buttons under a spawn's claim card. The respawn id is encoded into the id
   *  so a click needs no lookup of which post it came from — see
   *  [[RespawnButtonId]]. */
  def claimButtons(respawnId: Long, claimed: Boolean): ActionRow =
    if (claimed)
      // One Leave button, not a separate Release: which of the two it means is
      // decided by whether the presser holds the spawn or is waiting for it, and
      // making the member pick the right word for their own state was needless.
      ActionRow.of(
        Button.primary(RespawnButtonId.next(respawnId), "Next").withEmoji(Emoji.fromUnicode("⏭️")),
        Button.danger(RespawnButtonId.leave(respawnId), "Leave")
      )
    else
      ActionRow.of(
        Button.success(RespawnButtonId.claim(respawnId), "Claim").withEmoji(Emoji.fromUnicode("🏹"))
      )

  /** The buttons on the pinned board post, which is what makes the whole system
   *  usable without touching a slash command: a spawn with no post yet can't
   *  have a Claim button of its own, so the board carries one. */
  def boardButtons: ActionRow =
    ActionRow.of(
      Button.success(RespawnButtonId.boardClaim, "Claim").withEmoji(Emoji.fromUnicode("🏹")),
      Button.secondary(RespawnButtonId.boardConfig, "Config").withEmoji(Emoji.fromUnicode("⚙️"))
    )

  /** The Claim/Cancel pair on a handover offer DM. Cancel is styled as the
   *  destructive option because it drops them out of the queue entirely —
   *  exactly like leaving it. */
  def offerButtons(guildId: String, claimId: Long): ActionRow =
    ActionRow.of(
      Button.success(RespawnButtonId.accept(guildId, claimId), "Claim").withEmoji(Emoji.fromUnicode("🏹")),
      Button.danger(RespawnButtonId.decline(guildId, claimId), "Cancel")
    )

  // --- forum channel ------------------------------------------------------

  def findForum(guild: Guild, settings: RespawnSettings): Option[ForumChannel] =
    Option(settings.forumChannel)
      .filter(id => id.nonEmpty && id != "0")
      .flatMap(id => Option(guild.getForumChannelById(id)))

  /** Create the spawns forum under the bot's admin category and post the pinned,
   *  locked board thread. Returns (forumChannelId, boardThreadId).
   *
   *  @param category the guild's existing "Violent Bot" category — the forum is
   *                  placed alongside the command-log and notifications channels
   *                  rather than in its own category.
   */
  def createForum(guild: Guild, category: Category, settings: RespawnSettings): (String, String) = {
    val botRole = guild.getBotRole
    val publicRole = guild.getPublicRole

    val forum = guild.createForumChannel(ChannelName, category)
      .setTopic("Claim a respawn with /respawn claim — one post per respawn, showing who's on it and who's next.")
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

    // Members may talk inside a claim (coordinating a hunt is the point) but
    // may not open posts of their own — every post here has to correspond to a
    // catalogue entry the bot is tracking, or the channel stops meaning
    // anything.
    forum.upsertPermissionOverride(publicRole)
      .grant(Permission.VIEW_CHANNEL)
      .grant(Permission.MESSAGE_SEND_IN_THREADS)
      .grant(Permission.MESSAGE_HISTORY)
      .deny(Permission.CREATE_PUBLIC_THREADS)
      .complete()

    val boardId = postBoard(forum, settings)
    (forum.getId, boardId)
  }

  /** Post (or repost) the informational board thread, pinned to the top of the
   *  forum and locked so only the bot can write in it. */
  def postBoard(forum: ForumChannel, settings: RespawnSettings): String = {
    val message = new MessageCreateBuilder()
      .setEmbeds(RespawnEmbeds.boardPost(settings))
      .setComponents(boardButtons)
      .build()
    val post = forum.createForumPost("📖 How respawn claims work", message).complete()
    val thread = post.getThreadChannel
    // Locked *after* creation: a locked thread can't receive its own starter
    // message. Pinned keeps it at the top of the forum even once Discord
    // auto-archives it for inactivity, which it will, since nobody can post.
    Try(thread.getManager.setLocked(true).setPinned(true).complete()).failed.foreach { error =>
      logger.warn(s"Could not lock/pin the respawn board post in guild '${forum.getGuild.getId}'", error)
    }
    thread.getId
  }

  /** Re-assert the board post's pinned/unlocked-archive state. Called from the
   *  daily sweep: a locked post has no activity, so Discord eventually archives
   *  it, and an archived board is easy to miss. Cheap and idempotent. */
  def refreshBoard(guild: Guild, settings: RespawnSettings): Unit =
    findForum(guild, settings).foreach { forum =>
      resolveThread(guild, forum, settings.boardThread).foreach { thread =>
        if (thread.isArchived) {
          Try(thread.getManager.setArchived(false).setPinned(true).complete()).failed.foreach { error =>
            logger.warn(s"Could not un-archive the respawn board post in guild '${guild.getId}'", error)
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
      Try(thread.getManager.setAppliedTags(ForumTagSnowflake.fromId(found.getId)).complete()).failed.foreach { error =>
        logger.warn(s"Could not apply the '$tagName' tag to thread '${thread.getId}'", error)
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
   *  Reminders and handover offers go to the person's DMs rather than the
   *  spawn's thread: a thread ping is easy to miss and turns a shared card into
   *  a stream of notices aimed at one person. Nothing here is fatal — a user can
   *  have DMs closed, or share no mutual guild — so callers pass a fallback for
   *  anything that actually has to reach them.
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

  /** DM `userId`, falling back to a mention in the spawn's thread if the DM
   *  can't be delivered. Used for anything the person genuinely needs to see —
   *  a handover offer they have minutes to answer, in particular, would
   *  otherwise lapse without them ever knowing it existed. */
  def dmOrAnnounce(guild: Guild, userId: String, embed: MessageEmbed, buttons: Option[ActionRow],
                   thread: Option[ThreadChannel]): Unit =
    if (!dm(guild, userId, embed, buttons)) {
      thread.foreach { channel =>
        Try {
          // The mention goes in the message content rather than the embed, since
          // a mention inside an embed doesn't notify anyone — and notifying them
          // is the entire reason for this fallback.
          val action = channel.sendMessage(s"<@$userId>").setEmbeds(embed)
          buttons.fold(action.complete())(row => action.setComponents(row).complete())
        }.failed.foreach { error =>
          logger.warn(s"Could not reach user '$userId' by DM or in thread '${channel.getId}'", error)
        }
      }
    }

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

  /** Modal ids, kept next to the buttons that open them. */
  val modalClaim: String = s"${Prefix}modal:claim"
  val modalConfig: String = s"${Prefix}modal:config"

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
  /** A Claim/Cancel button on a handover offer DM. */
  final case class OfferButton(accept: Boolean, guildId: String, claimId: Long) extends Action

  /** None for anything malformed, so a button left over from an older deploy is
   *  ignored rather than throwing. */
  def parse(componentId: String): Option[Action] =
    componentId.stripPrefix(Prefix).split(':') match {
      case Array("board", what) => Some(BoardButton(what))
      case Array("accept", guildId, claimId) =>
        Try(claimId.toLong).toOption.map(OfferButton(accept = true, guildId, _))
      case Array("decline", guildId, claimId) =>
        Try(claimId.toLong).toOption.map(OfferButton(accept = false, guildId, _))
      case Array(action, id) =>
        Try(id.toLong).toOption.map(SpawnButton(action, _))
      case _ => None
    }
}
