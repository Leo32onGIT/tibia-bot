package com.tibiabot.commands

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member

/** Centralized command authorization checks. */
object Permissions {

  /** True if the caller is the bot's creator (the Discord application owner). */
  def isBotCreator(callerId: String, ownerId: String): Boolean =
    ownerId.nonEmpty && callerId == ownerId

  /** True if the member may run server-management commands. */
  def hasManageServer(member: Member): Boolean =
    member != null && member.hasPermission(Permission.MANAGE_SERVER)
}
