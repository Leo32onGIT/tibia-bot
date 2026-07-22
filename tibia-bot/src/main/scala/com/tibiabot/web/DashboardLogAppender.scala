package com.tibiabot.web

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase

/** Registered in logback.xml (needs a public no-arg constructor for Logback's
 *  reflection-based config loader), forwards every event it receives to
 *  [[LogCapture]]. Filtering to WARN+ happens in logback.xml itself (a
 *  ThresholdFilter on this appender), not here. */
final class DashboardLogAppender extends AppenderBase[ILoggingEvent] {
  override def append(event: ILoggingEvent): Unit =
    LogCapture.instance.record(event.getLevel.toString, event.getLoggerName, event.getFormattedMessage)
}
