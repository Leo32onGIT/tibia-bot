package com.tibiabot

import com.typesafe.config.ConfigFactory

import scala.jdk.CollectionConverters._
import scala.util.Try

object Config {
  // prod or dev environment
  val prod = true
  val verifiedDiscords = List(
    "1082484147492237515" // alpha/testing server
  )

  private val discord = ConfigFactory.load().getConfig("discord-config")
  private val mappings = ConfigFactory.load().getConfig("mapping-config")

  val token: String = discord.getString("token")
  val postgresHost: String = discord.getString("postgres-host")
  val postgresPassword: String = discord.getString("postgres-password")
  val creatureUrlMappings: Map[String, String] = mappings.getObject("creature-url-mappings").asScala.map {
    case (k, v) => k -> v.unwrapped().toString
  }.toMap

  // this is the message sent when the bot joins a discord or a user uses /help
  val helpText = s"**How to use the bot:**\n" +
    "Simply use `/setup <World Name>` to setup the bot.\n\n" +
    "**Commands & Features:**\n" +
    "All interactions with the bot are done through **[slash commands](https://support.discord.com/hc/en-us/articles/1500000368501-Slash-Commands-FAQ)**.\n" +
    "If you type `/` and click on **Lemon Bot** - you will see all the commands available to you.\n\n" +
    "[Website](https://lemonbot.xyz) | [Discord](https://discord.gg/SWMq9Pz8ud) | [Donate](http://donate.lemonbot.xyz)"

  // discord config
  val webHookAvatar: String = discord.getString("avatar-url")
  val aolThumbnail: String = discord.getString("fullbless-avatar-url")
  val nameChangeThumbnail: String = discord.getString("namechange-thumbnail")
  val guildLeaveThumbnail: String = discord.getString("guild-leave-thumbnail")
  val guildSwapGrey: String = discord.getString("guild-swap-thumbnail-grey")
  val guildSwapRed: String = discord.getString("guild-swap-thumbnail-red")
  val guildSwapGreen: String = discord.getString("guild-swap-thumbnail-green")
  val guildJoinGrey: String = discord.getString("guild-join-thumbnail-grey")
  val guildJoinRed: String = discord.getString("guild-join-thumbnail-red")
  val guildJoinGreen: String = discord.getString("guild-join-thumbnail-green")

  // Legacy emoji support (fallback to discord.conf if EmojiManager not available)
  private def getLegacyEmoji(key: String): String = {
    Try(discord.getString(key)).getOrElse("❓")
  }
  
  // Emoji accessor methods that use EmojiManager when possible, fallback to discord.conf
  def nemesisEmoji(guildId: String): String = EmojiManager.getEmoji("nemesis", guildId)
  def archfoeEmoji(guildId: String): String = EmojiManager.getEmoji("archfoe", guildId)
  def baneEmoji(guildId: String): String = EmojiManager.getEmoji("bane", guildId)
  def summonEmoji(guildId: String): String = EmojiManager.getEmoji("summon", guildId)
  def allyGuild(guildId: String): String = EmojiManager.getEmoji("allyguild", guildId)
  def otherGuild(guildId: String): String = EmojiManager.getEmoji("otherguild", guildId)
  def enemyGuild(guildId: String): String = EmojiManager.getEmoji("enemyguild", guildId)
  def ally(guildId: String): String = EmojiManager.getEmoji("ally", guildId)
  def enemy(guildId: String): String = EmojiManager.getEmoji("enemy", guildId)
  def neutral(guildId: String): String = EmojiManager.getEmoji("neutral", guildId)
  def mkEmoji(guildId: String): String = EmojiManager.getEmoji("mk", guildId)
  def cubeEmoji(guildId: String): String = EmojiManager.getEmoji("cube", guildId)
  def svarGreenEmoji(guildId: String): String = EmojiManager.getEmoji("svar-green", guildId)
  def svarScrapperEmoji(guildId: String): String = EmojiManager.getEmoji("svar-scrapper", guildId)
  def svarWarlordEmoji(guildId: String): String = EmojiManager.getEmoji("svar-warlord", guildId)
  def zelosEmoji(guildId: String): String = EmojiManager.getEmoji("zelos", guildId)
  def libEmoji(guildId: String): String = EmojiManager.getEmoji("library", guildId)
  def hodEmoji(guildId: String): String = EmojiManager.getEmoji("hod", guildId)
  def feruEmoji(guildId: String): String = EmojiManager.getEmoji("feru", guildId)
  def inqEmoji(guildId: String): String = EmojiManager.getEmoji("inq", guildId)
  def kilmareshEmoji(guildId: String): String = EmojiManager.getEmoji("kilmaresh", guildId)
  def exivaEmoji(guildId: String): String = EmojiManager.getEmoji("exiva", guildId)
  def indentEmoji(guildId: String): String = EmojiManager.getEmoji("indent", guildId)
  def levelUpEmoji(guildId: String): String = EmojiManager.getEmoji("levelup", guildId)
  def desireEmoji(guildId: String): String = EmojiManager.getEmoji("desire", guildId)
  def covetEmoji(guildId: String): String = EmojiManager.getEmoji("covet", guildId)
  def primalEmoji(guildId: String): String = EmojiManager.getEmoji("primal", guildId)
  def hazardEmoji(guildId: String): String = EmojiManager.getEmoji("hazard", guildId)
  def boostedBossEmoji(guildId: String): String = EmojiManager.getEmoji("boosted-boss", guildId)
  def boostedCreatureEmoji(guildId: String): String = EmojiManager.getEmoji("boosted-creature", guildId)
  def yesEmoji(guildId: String): String = EmojiManager.getEmoji("yes", guildId)
  def noEmoji(guildId: String): String = EmojiManager.getEmoji("no", guildId)
  def letterEmoji(guildId: String): String = EmojiManager.getEmoji("letter", guildId)
  def goldEmoji(guildId: String): String = EmojiManager.getEmoji("gold", guildId)
  def bossEmoji(guildId: String): String = EmojiManager.getEmoji("boss", guildId)
  def creatureEmoji(guildId: String): String = EmojiManager.getEmoji("creature", guildId)
  def torchOnEmoji(guildId: String): String = EmojiManager.getEmoji("torch-on", guildId)
  def torchOffEmoji(guildId: String): String = EmojiManager.getEmoji("torch-off", guildId)
  def satchelEmoji(guildId: String): String = EmojiManager.getEmoji("satchel", guildId)
  
  // Legacy static emoji values for backward compatibility (use fallback emojis)
  val nemesisEmoji: String = EmojiManager.getFallbackEmoji("nemesis")
  val archfoeEmoji: String = EmojiManager.getFallbackEmoji("archfoe")
  val baneEmoji: String = EmojiManager.getFallbackEmoji("bane")
  val summonEmoji: String = EmojiManager.getFallbackEmoji("summon")
  val allyGuild: String = EmojiManager.getFallbackEmoji("allyguild")
  val otherGuild: String = EmojiManager.getFallbackEmoji("otherguild")
  val enemyGuild: String = EmojiManager.getFallbackEmoji("enemyguild")
  val ally: String = EmojiManager.getFallbackEmoji("ally")
  val enemy: String = EmojiManager.getFallbackEmoji("enemy")
  val neutral: String = EmojiManager.getFallbackEmoji("neutral")
  val mkEmoji: String = EmojiManager.getFallbackEmoji("mk")
  val cubeEmoji: String = EmojiManager.getFallbackEmoji("cube")
  val svarGreenEmoji: String = EmojiManager.getFallbackEmoji("svar-green")
  val svarScrapperEmoji: String = EmojiManager.getFallbackEmoji("svar-scrapper")
  val svarWarlordEmoji: String = EmojiManager.getFallbackEmoji("svar-warlord")
  val zelosEmoji: String = EmojiManager.getFallbackEmoji("zelos")
  val libEmoji: String = EmojiManager.getFallbackEmoji("library")
  val hodEmoji: String = EmojiManager.getFallbackEmoji("hod")
  val feruEmoji: String = EmojiManager.getFallbackEmoji("feru")
  val inqEmoji: String = EmojiManager.getFallbackEmoji("inq")
  val kilmareshEmoji: String = EmojiManager.getFallbackEmoji("kilmaresh")
  val exivaEmoji: String = EmojiManager.getFallbackEmoji("exiva")
  val indentEmoji: String = EmojiManager.getFallbackEmoji("indent")
  val levelUpEmoji: String = EmojiManager.getFallbackEmoji("levelup")
  val desireEmoji: String = EmojiManager.getFallbackEmoji("desire")
  val covetEmoji: String = EmojiManager.getFallbackEmoji("covet")
  val primalEmoji: String = EmojiManager.getFallbackEmoji("primal")
  val hazardEmoji: String = EmojiManager.getFallbackEmoji("hazard")
  val boostedBossEmoji: String = EmojiManager.getFallbackEmoji("boosted-boss")
  val boostedCreatureEmoji: String = EmojiManager.getFallbackEmoji("boosted-creature")
  val yesEmoji: String = EmojiManager.getFallbackEmoji("yes")
  val noEmoji: String = EmojiManager.getFallbackEmoji("no")
  val letterEmoji: String = EmojiManager.getFallbackEmoji("letter")
  val goldEmoji: String = EmojiManager.getFallbackEmoji("gold")
  val bossEmoji: String = EmojiManager.getFallbackEmoji("boss")
  val creatureEmoji: String = EmojiManager.getFallbackEmoji("creature")
  val torchOnEmoji: String = EmojiManager.getFallbackEmoji("torch-on")
  val torchOffEmoji: String = EmojiManager.getFallbackEmoji("torch-off")
  val satchelEmoji: String = EmojiManager.getFallbackEmoji("satchel")
  // Rate limiting configuration
  val messageDelayMs: Int = discord.getInt("message-delay-ms")
  val batchSize: Int = discord.getInt("batch-size")
  val batchDelayMs: Int = discord.getInt("batch-delay-ms")

  // creature mappings
  val notableCreatures: List[String] = mappings.getStringList("notable-creatures").asScala.toList
  val primalCreatures: List[String] = mappings.getStringList("primal-creatures").asScala.toList
  val hazardCreatures: List[String] = mappings.getStringList("hazard-creatures").asScala.toList
  val bossSummons: List[String] = mappings.getStringList("boss-summons").asScala.toList
  val nemesisCreatures: List[String] = mappings.getStringList("nemesis-creatures").asScala.toList
  val archfoeCreatures: List[String] = mappings.getStringList("archfoe-creatures").asScala.toList
  val baneCreatures: List[String] = mappings.getStringList("bane-creatures").asScala.toList
  val mkBosses: List[String] = mappings.getStringList("mk-bosses").asScala.toList
  val cubeBosses: List[String] = mappings.getStringList("cube-bosses").asScala.toList
  val svarGreenBosses: List[String] = mappings.getStringList("svar-green-bosses").asScala.toList
  val svarScrapperBosses: List[String] = mappings.getStringList("svar-scrapper-bosses").asScala.toList
  val svarWarlordBosses: List[String] = mappings.getStringList("svar-warlord-bosses").asScala.toList
  val zelosBosses: List[String] = mappings.getStringList("zelos-bosses").asScala.toList
  val libBosses: List[String] = mappings.getStringList("library-bosses").asScala.toList
  val hodBosses: List[String] = mappings.getStringList("hod-bosses").asScala.toList
  val feruBosses: List[String] = mappings.getStringList("feru-bosses").asScala.toList
  val inqBosses: List[String] = mappings.getStringList("inq-bosses").asScala.toList
  val kilmareshBosses: List[String] = mappings.getStringList("kilmaresh-bosses").asScala.toList

  // worlds - dynamically fetched from TibiaData API
  def worldList: List[String] = WorldManager.getWorldList()
  val mergedWorlds = List(
    // Pulsera
    "Illusera",
    "Wizera",
    "Seanera",
    // Yovera
    "Optera",
    "Marbera",
    // Wildera
    "Fera",
    "Ardera",
    // Kendria
    "Trona",
    "Marcia",
    "Adra",
    "Suna",
    // Nevia
    "Famosa",
    "Karna",
    "Olima",
    // Retalia
    "Versa",
    "Bastia",
    // Jadebra
    "Ocebra",
    "Alumbra",
    "Dibra",
    // Rasteibra
    "Zenobra",
    "Xandebra",
    // Ustebra
    "Tembra",
    "Reinobra",
    // Obscubra
    "Cadebra",
    "Visabra",
    "Libertabra",
    // Guerribra
    "Mudabra",
    "Nossobra",
    "Batabra",
    // Quidera
    "Pulsera",
    "Axera",
    // Fibera
    "Kardera",
    "Mykera",
    // Ourobra
    "Bombra",
    "Utobra",
    // Gladibra
    "Guerribra",
    "Ousabra",
    // Xyla
    "Kendria",
    "Castela",
    // Karmeya
    "Damora",
    "Nadora",
    // Malivora
    "Impulsa",
    "Syrena"
  )
  // creatures - dynamically fetched from TibiaData API
  def creaturesList: List[String] = CreatureManager.getCreaturesList()
}