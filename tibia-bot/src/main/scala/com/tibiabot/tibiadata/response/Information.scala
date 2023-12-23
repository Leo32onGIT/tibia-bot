package com.tibiabot.tibiadata.response

case class Api(version: Double, release: String, commit: String)

case class Status(http_code: Double)

case class Information(api: Api, status: Status)
