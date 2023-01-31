package com.tibiabot

import com.typesafe.config.ConfigFactory

import scala.jdk.CollectionConverters._

object Config {
  private val discord = ConfigFactory.load().getConfig("discord-config")
  private val mappings = ConfigFactory.load().getConfig("mapping-config")

  val token: String = discord.getString("token")
  val postgresHost: String = discord.getString("postgres-host")
  val postgresPassword: String = discord.getString("postgres-password")

  val creatureUrlMappings: Map[String, String] = mappings.getObject("creature-url-mappings").asScala.map {
    case (k, v) => k -> v.unwrapped().toString
  }.toMap

  // discord config
  val worldList: List[String] = mappings.getStringList("worlds").asScala.toList
  val webHookAvatar: String = discord.getString("avatar-url")

  // emojis
  val nemesisEmoji: String = discord.getString("nemesis-emoji")
  val archfoeEmoji: String = discord.getString("archfoe-emoji")
  val baneEmoji: String = discord.getString("bane-emoji")
  val summonEmoji: String = discord.getString("summon-emoji")
  val allyGuild: String = discord.getString("allyguild-emoji")
  val otherGuild: String = discord.getString("otherguild-emoji")
  val noGuild: String = discord.getString("noguild-emoji")
  val enemyGuild: String = discord.getString("enemyguild-emoji")
  val enemy: String = discord.getString("enemy-emoji")
  val mkEmoji: String = discord.getString("mk-emoji")
  val cubeEmoji: String = discord.getString("cube-emoji")
  val svarGreenEmoji: String = discord.getString("svar-green-emoji")
  val svarScrapperEmoji: String = discord.getString("svar-scrapper-emoji")
  val svarWarlordEmoji: String = discord.getString("svar-warlord-emoji")
  val zelosEmoji: String = discord.getString("zelos-emoji")
  val libEmoji: String = discord.getString("library-emoji")
  val hodEmoji: String = discord.getString("hod-emoji")
  val feruEmoji: String = discord.getString("feru-emoji")
  val inqEmoji: String = discord.getString("inq-emoji")
  val kilmareshEmoji: String = discord.getString("kilmaresh-emoji")
  val exivaEmoji: String = discord.getString("exiva-emoji")
  val indentEmoji: String = discord.getString("indent-emoji")

  // creature mappings
  val notableCreatures: List[String] = mappings.getStringList("notable-creatures").asScala.toList
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
}
