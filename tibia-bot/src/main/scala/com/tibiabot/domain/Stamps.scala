package com.tibiabot.domain

import java.time.ZonedDateTime

case class SatchelStamp(user: String, when: ZonedDateTime, tag: String)
case class BoostedStamp(user: String, boostedType: String, boostedName: String)
