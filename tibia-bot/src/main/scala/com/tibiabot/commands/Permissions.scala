package com.tibiabot.commands

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

/** Centralized command authorization checks. */
object Permissions {

  /** The role delegating the bot's list-management commands, created by `/setup`.
   *  A bare marker role carrying no Discord permissions of its own — all it says
   *  is "this person is trusted with the bot". Here rather than on ChannelService
   *  so handlers naming it in a refusal need not reach into the setup package. */
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
   *  and the respawn catalogue. Either **Manage Server** or the guild's "Violent
   *  Bot Moderator" role, so granting the role is purely additive. Checked by id
   *  rather than name, since a server may rename it.
   *
   *  This cannot control command *visibility*: Discord gates commands on
   *  permission flags, not roles, and since command-permissions v2 only admins can
   *  map roles to commands. Fine for what this guards, which is visible to
   *  everyone and decides access here instead. */
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
