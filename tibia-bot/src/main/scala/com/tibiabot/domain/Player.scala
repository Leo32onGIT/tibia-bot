package com.tibiabot.domain

import java.time.ZonedDateTime

case class Players(name: String, reason: String, reasonText: String, addedBy: String)
case class PlayerCache(name: String, formerNames: List[String], guild: String, updatedTime: ZonedDateTime)
