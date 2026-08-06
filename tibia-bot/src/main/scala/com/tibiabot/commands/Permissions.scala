package com.tibiabot.commands

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

/** Centralized command authorization checks. */
object Permissions {

  /** The role that delegates the bot's list-management commands, created by
   *  `/setup`. A bare marker role: it carries no Discord permissions of its own,
   *  because all it needs to say is "this person is trusted with the bot".
   *
   *  Lives here rather than on ChannelService so the handlers that name it in
   *  their refusal messages don't have to reach into the setup package for a
   *  string. */
  val ModeratorRoleName: String = "Violent Bot Moderator"

  /** True if the caller is the bot's creator (the Discord application owner). */
  def isBotCreator(callerId: String, ownerId: String): Boolean =
    ownerId.nonEmpty && callerId == ownerId

  /** True if the member may run server-management commands. */
  def hasManageServer(member: Member): Boolean =
    member != null && member.hasPermission(Permission.MANAGE_SERVER)

  /** True if the user who triggered `event` may run server-management commands.
   *  Resolves the caller's Member (a blocking retrieve) then defers to
   *  [[hasManageServer]]. */
  def callerHasManageServer(event: SlashCommandInteractionEvent): Boolean =
    hasManageServer(event.getGuild.retrieveMember(event.getUser).complete())

  /** True if the member may run the delegated commands — the hunted/allies lists
   *  and the respawn catalogue.
   *
   *  Either **Manage Server** or the guild's "Violent Bot Moderator" role, so
   *  granting the role is purely additive: nobody who could use these commands
   *  before loses access.
   *
   *  The role is checked by id rather than by name because a server may rename
   *  it, and it is deliberately a bare marker role — it carries no Discord
   *  permissions of its own, since its whole job is to say "this person is
   *  trusted with the bot".
   *
   *  Note this cannot control command *visibility*: Discord gates commands on
   *  permission flags, not roles, and since command-permissions v2 only server
   *  admins can map roles to commands (Server Settings -> Integrations). That's
   *  fine for the commands this guards, which are visible to everyone and decide
   *  access here instead. */
  def isModerator(member: Member, moderatorRoleId: String): Boolean =
    member != null && grantsAccess(
      hasManageServer(member),
      member.getRoles.asScala.map(_.getId).toSet,
      moderatorRoleId)

  /** The decision itself, free of JDA so it can be tested directly.
   *
   *  An unset role id ("0" or empty) must not match anything: a guild that has
   *  never run `/setup` stores "0", and treating that as a role would either
   *  match nobody (harmless) or — if a role were ever created with that id —
   *  everybody. Reducing cleanly to the Manage Server check is the safe reading. */
  private[commands] def grantsAccess(hasManageServer: Boolean, memberRoleIds: Set[String],
                                     moderatorRoleId: String): Boolean =
    hasManageServer ||
      (moderatorRoleId.nonEmpty && moderatorRoleId != "0" && memberRoleIds.contains(moderatorRoleId))

  /** True if the caller of `event` passes [[isModerator]]. */
  def callerIsModerator(event: SlashCommandInteractionEvent, moderatorRoleId: String): Boolean =
    isModerator(event.getGuild.retrieveMember(event.getUser).complete(), moderatorRoleId)

}
