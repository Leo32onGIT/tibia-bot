package com.tibiabot

import com.typesafe.config.ConfigFactory
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.emoji.{CustomEmoji, Emoji}
import net.dv8tion.jda.api.utils.FileUpload
import com.typesafe.scalalogging.StrictLogging

import java.io.{File, InputStream}
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

case class EmojiInfo(
  name: String,
  filename: String,
  fallback: String,
  description: String,
  animated: Boolean = false
)

/**
 * Manages emoji loading and fallback for the Tibia Bot.
 * Automatically uploads custom emojis to guilds and provides fallback Unicode emojis.
 */
object EmojiManager extends StrictLogging {
  
  private val emojiMappings = ConfigFactory.load("emojis/emoji-mapping").getConfig("emoji-mappings")
  private val emojiCache = mutable.Map[String, String]()
  private val guildEmojiCache = mutable.Map[String, mutable.Map[String, String]]()
  private var jda: Option[JDA] = None
  
  // Load emoji configurations at startup
  private val emojiInfos: Map[String, EmojiInfo] = {
    emojiMappings.root().keySet().asScala.map { key =>
      val config = emojiMappings.getConfig(key)
      key -> EmojiInfo(
        name = config.getString("name"),
        filename = config.getString("filename"),
        fallback = config.getString("fallback"),
        description = config.getString("description"),
        animated = if (config.hasPath("animated")) config.getBoolean("animated") else false
      )
    }.toMap
  }
  
  /**
   * Initialize the emoji manager with JDA instance
   */
  def initialize(jdaInstance: JDA): Unit = {
    jda = Some(jdaInstance)
    logger.info(s"EmojiManager initialized with ${emojiInfos.size} emoji definitions")
  }
  
  /**
   * Setup emojis for a specific guild by uploading missing custom emojis
   */
  def setupGuildEmojis(guild: Guild): Future[Unit] = {
    Future {
      try {
        val guildId = guild.getId
        val guildCache = guildEmojiCache.getOrElseUpdate(guildId, mutable.Map[String, String]())
        val existingEmojis = guild.getEmojis.asScala.map(e => e.getName -> e.getAsMention).toMap
        
        logger.info(s"Setting up emojis for guild: ${guild.getName} (${guildId})")
        
        var uploadedCount = 0
        var skippedCount = 0
        
        emojiInfos.foreach { case (key, emojiInfo) =>
          existingEmojis.get(emojiInfo.name) match {
            case Some(mention) =>
              // Emoji already exists
              guildCache(key) = mention
              skippedCount += 1
              
            case None =>
              // Try to upload the emoji
              uploadEmojiToGuild(guild, emojiInfo) match {
                case Success(mention) =>
                  guildCache(key) = mention
                  uploadedCount += 1
                  Thread.sleep(1000) // Rate limiting
                  
                case Failure(exception) =>
                  logger.warn(s"Failed to upload emoji ${emojiInfo.name} to guild ${guild.getName}: ${exception.getMessage}")
                  guildCache(key) = emojiInfo.fallback
              }
          }
        }
        
        logger.info(s"Guild emoji setup complete for ${guild.getName}: $uploadedCount uploaded, $skippedCount existing")
        
      } catch {
        case e: Exception =>
          logger.error(s"Error setting up emojis for guild ${guild.getName}: ${e.getMessage}", e)
      }
    }(scala.concurrent.ExecutionContext.global)
  }
  
  /**
   * Upload a single emoji to a guild
   */
  private def uploadEmojiToGuild(guild: Guild, emojiInfo: EmojiInfo): Try[String] = {
    Try {
      val resourcePath = s"/emojis/${emojiInfo.filename}"
      val inputStream: InputStream = getClass.getResourceAsStream(resourcePath)
      
      if (inputStream == null) {
        throw new RuntimeException(s"Emoji file not found: $resourcePath")
      }
      
      val iconBytes = inputStream.readAllBytes()
      val icon = net.dv8tion.jda.api.entities.Icon.from(iconBytes)
      val action = guild.createEmoji(emojiInfo.name, icon)
      
      val emoji = action.complete()
      logger.info(s"Successfully uploaded emoji ${emojiInfo.name} to guild ${guild.getName}")
      emoji.getAsMention
    }
  }
  
  /**
   * Get emoji for a specific guild, with fallback to Unicode emoji
   */
  def getEmoji(key: String, guildId: String): String = {
    guildEmojiCache.get(guildId) match {
      case Some(guildCache) =>
        guildCache.getOrElse(key, getFallbackEmoji(key))
      case None =>
        getFallbackEmoji(key)
    }
  }
  
  /**
   * Get emoji with automatic guild detection (for use in events)
   */
  def getEmoji(key: String, guild: Guild): String = {
    getEmoji(key, guild.getId)
  }
  
  /**
   * Get fallback Unicode emoji
   */
  def getFallbackEmoji(key: String): String = {
    emojiInfos.get(key).map(_.fallback).getOrElse("❓")
  }
  
  /**
   * Get emoji formatted for JDA Emoji.fromFormatted()
   */
  def getFormattedEmoji(key: String, guildId: String): Emoji = {
    val emojiString = getEmoji(key, guildId)
    if (emojiString.startsWith("<") && emojiString.endsWith(">")) {
      // Custom emoji
      Emoji.fromFormatted(emojiString)
    } else {
      // Unicode emoji
      Emoji.fromUnicode(emojiString)
    }
  }
  
  /**
   * Get emoji formatted for JDA Emoji.fromFormatted() with guild detection
   */
  def getFormattedEmoji(key: String, guild: Guild): Emoji = {
    getFormattedEmoji(key, guild.getId)
  }
  
  /**
   * Check if all emojis are available for a guild
   */
  def isGuildSetupComplete(guildId: String): Boolean = {
    guildEmojiCache.get(guildId).exists(_.size == emojiInfos.size)
  }
  
  /**
   * Get all available emoji keys
   */
  def getAllEmojiKeys: Set[String] = emojiInfos.keySet
  
  /**
   * Get emoji info for a specific key
   */
  def getEmojiInfo(key: String): Option[EmojiInfo] = emojiInfos.get(key)
  
  /**
   * Force refresh emojis for a guild (useful after manual emoji changes)
   */
  def refreshGuildEmojis(guildId: String): Unit = {
    guildEmojiCache.remove(guildId)
    jda.foreach { j =>
      Option(j.getGuildById(guildId)).foreach(setupGuildEmojis)
    }
  }
  
  /**
   * Get emoji statistics for monitoring
   */
  def getEmojiStats: Map[String, Any] = {
    Map(
      "totalEmojis" -> emojiInfos.size,
      "guildsSetup" -> guildEmojiCache.size,
      "animatedEmojis" -> emojiInfos.count(_._2.animated),
      "staticEmojis" -> emojiInfos.count(!_._2.animated)
    )
  }
}