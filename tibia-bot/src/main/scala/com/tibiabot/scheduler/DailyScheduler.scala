package com.tibiabot.scheduler

import com.tibiabot.{BotApp, Config, EmojiManager}
import com.tibiabot.tibiadata.TibiaDataClient
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.{EmbedBuilder, JDA}
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}

import java.time.{LocalDateTime, ZoneId, ZonedDateTime}
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

/**
 * Daily scheduler for automated bot announcements
 */
object DailyScheduler extends StrictLogging {
  
  private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val tibiaDataClient = new TibiaDataClient()
  
  private var jda: Option[JDA] = None
  
  /**
   * Initialize the scheduler with JDA instance
   */
  def initialize(jdaInstance: JDA): Unit = {
    jda = Some(jdaInstance)
    scheduleDaily845AMTask()
    logger.info("DailyScheduler initialized with 8:45 AM UTC boosted announcements")
  }
  
  /**
   * Schedule task to run daily at 8:45 AM UTC
   */
  private def scheduleDaily845AMTask(): Unit = {
    val now = ZonedDateTime.now(ZoneId.of("UTC"))
    val targetTime = now.withHour(8).withMinute(45).withSecond(0).withNano(0)
    
    // If we've already passed today's 8:45 AM, schedule for tomorrow
    val nextRun = if (now.isAfter(targetTime)) {
      targetTime.plusDays(1)
    } else {
      targetTime
    }
    
    val initialDelay = java.time.Duration.between(now, nextRun).toMillis
    val period = TimeUnit.DAYS.toMillis(1) // 24 hours
    
    logger.info(s"Scheduling daily boosted announcements. Next run: ${nextRun} (in ${initialDelay}ms)")
    
    scheduler.scheduleAtFixedRate(
      () => {
        try {
          sendDailyBoostedAnnouncements()
        } catch {
          case e: Exception =>
            logger.error("Error in daily boosted announcements task", e)
        }
      },
      initialDelay,
      period,
      TimeUnit.MILLISECONDS
    )
  }
  
  /**
   * Send daily boosted announcements to all configured guilds
   */
  private def sendDailyBoostedAnnouncements(): Unit = {
    logger.info("Starting daily boosted announcements")
    
    jda.foreach { j =>
      val guilds = j.getGuilds.asScala.toList
      
      guilds.foreach { guild =>
        try {
          sendBoostedAnnouncementToGuild(guild)
        } catch {
          case e: Exception =>
            logger.error(s"Error sending boosted announcement to guild ${guild.getName}: ${e.getMessage}", e)
        }
      }
    }
  }
  
  /**
   * Send boosted announcement to a specific guild
   */
  private def sendBoostedAnnouncementToGuild(guild: Guild): Unit = {
    // Get guild's boosted channel configuration
    BotApp.getDiscordConfig(guild.getId) match {
      case Some(discordConfig) =>
        val boostedChannelId = discordConfig.boostedChannel
        
        if (boostedChannelId != "0" && boostedChannelId.nonEmpty) {
          val boostedChannel = guild.getTextChannelById(boostedChannelId)
          
          if (boostedChannel != null && boostedChannel.canTalk()) {
            // Fetch boosted data and send announcement
            fetchAndSendBoostedData(guild, boostedChannel)
          } else {
            logger.warn(s"Cannot send to boosted channel in guild ${guild.getName}: channel not found or no permissions")
          }
        } else {
          logger.debug(s"No boosted channel configured for guild ${guild.getName}")
        }
        
      case None =>
        logger.debug(s"No discord configuration found for guild ${guild.getName}")
    }
  }
  
  /**
   * Fetch all daily data and send formatted announcement
   */
  private def fetchAndSendBoostedData(guild: Guild, channel: net.dv8tion.jda.api.entities.channel.concrete.TextChannel): Unit = {
    val guildId = guild.getId
    
    // Fetch all required data: boosted monsters, news, and news ticker
    val boostedBossFuture = tibiaDataClient.getBoostedBoss()
    val boostedCreatureFuture = tibiaDataClient.getBoostedCreature()
    val latestNewsFuture = tibiaDataClient.getLatestNews()
    val newsTickerFuture = tibiaDataClient.getNewsTicker()
    
    for {
      bossResult <- boostedBossFuture
      creatureResult <- boostedCreatureFuture
      newsResult <- latestNewsFuture
      tickerResult <- newsTickerFuture
    } yield {
      val embed = createDailyAnnouncementEmbed(bossResult, creatureResult, newsResult, tickerResult, guildId)
      
      channel.sendMessageEmbeds(embed).queue(
        _ => logger.info(s"Successfully sent daily announcement to ${guild.getName}"),
        error => logger.error(s"Failed to send daily announcement to ${guild.getName}: ${error.getMessage}")
      )
    }
  }
  
  /**
   * Create the comprehensive daily announcement embed
   */
  private def createDailyAnnouncementEmbed(
    bossResult: Either[String, com.tibiabot.tibiadata.response.BoostedResponse],
    creatureResult: Either[String, com.tibiabot.tibiadata.response.CreatureResponse],
    newsResult: Either[String, com.tibiabot.tibiadata.response.NewsResponse],
    tickerResult: Either[String, com.tibiabot.tibiadata.response.NewsTickerResponse],
    guildId: String
  ): MessageEmbed = {
    
    val embed = new EmbedBuilder()
    embed.setTitle("📰 Daily Tibia Update")
    embed.setColor(0x1E90FF) // Dodger blue
    embed.setTimestamp(java.time.Instant.now())
    
    val boostedBossEmoji = Config.boostedBossEmoji(guildId)
    val boostedCreatureEmoji = Config.boostedCreatureEmoji(guildId)
    
    // Process boosted boss
    bossResult match {
      case Right(boostedResponse) =>
        val boostedBoss = boostedResponse.boostable_bosses.boosted
        val bossName = boostedBoss.name
        val bossImageUrl = boostedBoss.image_url
        
        embed.addField(
          s"${boostedBossEmoji} **Boosted Boss**",
          s"### [${bossName}](${BotApp.creatureWikiUrl(bossName)})\n" +
          s"📍 **Location:** Check the [Boostable Bosses](https://www.tibia.com/library/?subtopic=boostablebosses) page\n" +
          s"⭐ **Bonus:** Double XP and loot chance\n" +
          s"🎯 **Tip:** Great for boss hunting today!",
          true
        )
        
        // Set thumbnail to boss image if available
        if (bossImageUrl.nonEmpty) {
          embed.setThumbnail(bossImageUrl)
        }
        
      case Left(error) =>
        embed.addField(
          s"${boostedBossEmoji} **Boosted Boss**",
          "❌ Failed to load boosted boss information",
          true
        )
        logger.warn(s"Failed to fetch boosted boss: $error")
    }
    
    // Process boosted creature
    creatureResult match {
      case Right(creatureResponse) =>
        val boostedCreature = creatureResponse.creatures.boosted
        val creatureName = boostedCreature.name
        val creatureImageUrl = creatureResponse.creatures.boosted.image_url.getOrElse("")
        
        embed.addField(
          s"${boostedCreatureEmoji} **Boosted Creature**",
          s"### [${creatureName}](${BotApp.creatureWikiUrl(creatureName)})\n" +
          s"📍 **Location:** Check the [Creatures](https://www.tibia.com/library/?subtopic=creatures) library\n" +
          s"⭐ **Bonus:** Double XP and improved loot\n" +
          s"🏹 **Tip:** Perfect for hunting and task completion!",
          true
        )
        
        // Set image to creature if no boss image
        if (embed.build().getThumbnail == null && creatureImageUrl.nonEmpty) {
          embed.setThumbnail(creatureImageUrl)
        }
        
      case Left(error) =>
        embed.addField(
          s"${boostedCreatureEmoji} **Boosted Creature**",
          "❌ Failed to load boosted creature information",
          true
        )
        logger.warn(s"Failed to fetch boosted creature: $error")
    }
    
    // Add Latest News section
    newsResult match {
      case Right(newsResponse) =>
        val latestNews = newsResponse.news.news.take(3) // Show only latest 3 news items
        if (latestNews.nonEmpty) {
          val newsText = latestNews.map { newsItem =>
            val truncatedTitle = if (newsItem.title.length > 80) {
              newsItem.title.take(77) + "..."
            } else {
              newsItem.title
            }
            s"• [${truncatedTitle}](${newsItem.url})"
          }.mkString("\n")
          
          embed.addField(
            "📰 **Latest News**",
            newsText,
            false
          )
        }
      case Left(error) =>
        logger.warn(s"Failed to fetch latest news: $error")
        embed.addField("📰 **Latest News**", "❌ Failed to load news", false)
    }
    
    // Add News Ticker section
    tickerResult match {
      case Right(tickerResponse) =>
        val recentTickers = tickerResponse.newstickers.newstickers.take(2) // Show only 2 most recent
        if (recentTickers.nonEmpty) {
          val tickerText = recentTickers.map { ticker =>
            val truncatedMessage = if (ticker.message.length > 100) {
              ticker.message.take(97) + "..."
            } else {
              ticker.message
            }
            s"📢 ${truncatedMessage}"
          }.mkString("\n")
          
          embed.addField(
            "🔔 **Recent Announcements**",
            tickerText,
            false
          )
        }
      case Left(error) =>
        logger.warn(s"Failed to fetch news ticker: $error")
        embed.addField("🔔 **Recent Announcements**", "❌ Failed to load announcements", false)
    }
    
    // Add footer with helpful information
    embed.setFooter(
      "🔄 Boosted monsters reset daily at server save (10:00 CET/CEST)",
      "https://tibia.fandom.com/wiki/Special:Redirect/file/Tibia_logo.png"
    )
    
    // Add description with general info
    embed.setDescription(
      "Your daily Tibia update is here! 🎮\n\n" +
      "**🌟 Boosted Monsters** - Double XP and improved loot rates!\n" +
      "**📰 Latest News** - Stay informed about game updates\n" +
      "**🔔 Announcements** - Important server information\n\n" +
      "💡 **Tip:** Boosted bonuses apply to all characters - perfect for hunting and boss challenges!"
    )
    
    embed.build()
  }
  
  /**
   * Manual trigger for testing (can be called via admin command)
   */
  def triggerManualAnnouncement(guild: Guild): Unit = {
    logger.info(s"Manual daily announcement triggered for guild ${guild.getName}")
    sendBoostedAnnouncementToGuild(guild)
  }
  
  /**
   * Get next scheduled announcement time
   */
  def getNextAnnouncementTime: ZonedDateTime = {
    val now = ZonedDateTime.now(ZoneId.of("UTC"))
    val targetTime = now.withHour(8).withMinute(45).withSecond(0).withNano(0)
    
    if (now.isAfter(targetTime)) {
      targetTime.plusDays(1)
    } else {
      targetTime
    }
  }
  
  /**
   * Shutdown the scheduler
   */
  def shutdown(): Unit = {
    scheduler.shutdown()
    logger.info("DailyScheduler shutdown")
  }
}