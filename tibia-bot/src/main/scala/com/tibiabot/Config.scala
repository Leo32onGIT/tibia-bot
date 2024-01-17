package com.tibiabot

import com.typesafe.config.ConfigFactory

import scala.jdk.CollectionConverters._

object Config {
  // prod or dev environment
  val prod = true

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
    "If you type `/` and click on **Violent Bot** - you will see all the commands available to you.\n\n" +
    "*If you have any issues or suggestions or would like to donate, use the links below or simply send <:tibiacoin:1117280875818778637> to* **`Violent Beams`** 👍\n\n" +
    "[Website](https://violentbot.xyz) | [Discord](https://discord.gg/SWMq9Pz8ud) | [Donate](http://donate.violentbot.xyz)"

  // worlds
  val worldList = List(
    "Ambra",
    "Antica",
    "Astera",
    "Axera",
    "Belobra",
    "Bombra",
    "Bona",
    "Calmera",
    "Castela",
    "Celebra",
    "Celesta",
    "Collabra",
    "Damora",
    "Descubra",
    "Dia",
    "Epoca",
    "Esmera",
    "Etebra",
    "Ferobra",
    "Firmera",
    "Flamera",
    "Gentebra",
    "Gladera",
    "Gravitera",
    "Guerribra",
    "Harmonia",
    "Havera",
    "Honbra",
    "Impulsa",
    "Inabra",
    "Issobra",
    "Jacabra",
    "Jadebra",
    "Jaguna",
    "Kalibra",
    "Kardera",
    "Kendria",
    "Lobera",
    "Luminera",
    "Lutabra",
    "Menera",
    "Monza",
    "Mykera",
    "Nadora",
    "Nefera",
    "Nevia",
    "Ombra",
    "Obscubra",
    "Ousabra",
    "Pacera",
    "Peloria",
    "Premia",
    "Pulsera",
    "Quelibra",
    "Quintera",
    "Rasteibra",
    "Refugia",
    "Retalia",
    "Runera",
    "Secura",
    "Serdebra",
    "Solidera",
    "Syrena",
    "Talera",
    "Thyria",
    "Tornabra",
    "Ustebra",
    "Utobra",
    "Venebra",
    "Vitera",
    "Vunira",
    "Wadira",
    "Wildera",
    "Wintera",
    "Yonabra",
    "Yovera",
    "Zuna",
    "Zunera"
  )
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
    "Batabra"
  )

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

  // emojis
  val nemesisEmoji: String = discord.getString("nemesis-emoji")
  val archfoeEmoji: String = discord.getString("archfoe-emoji")
  val baneEmoji: String = discord.getString("bane-emoji")
  val summonEmoji: String = discord.getString("summon-emoji")
  val allyGuild: String = discord.getString("allyguild-emoji")
  val otherGuild: String = discord.getString("otherguild-emoji")
  val enemyGuild: String = discord.getString("enemyguild-emoji")
  val ally: String = discord.getString("ally-emoji")
  val enemy: String = discord.getString("enemy-emoji")
  val neutral: String = discord.getString("neutral-emoji")
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
  val levelUpEmoji: String = discord.getString("levelup-emoji")
  val desireEmoji: String = discord.getString("desire-emoji")
  val covetEmoji: String = discord.getString("covet-emoji")
  val primalEmoji: String = discord.getString("primal-emoji")
  val hazardEmoji: String = discord.getString("hazard-emoji")
  val boostedBossEmoji: String = discord.getString("boosted-boss-emoji")
  val boostedCreatureEmoji: String = discord.getString("boosted-creature-emoji")
  val yesEmoji: String = discord.getString("yes-emoji")
  val noEmoji: String = discord.getString("no-emoji")
  val letterEmoji: String = discord.getString("letter-emoji")
  val goldEmoji: String = discord.getString("gold-emoji")
  val bossEmoji: String = discord.getString("boss-emoji")
  val creatureEmoji: String = discord.getString("creature-emoji")
  val torchOnEmoji: String = discord.getString("torch-on-emoji")
  val torchOffEmoji: String = discord.getString("torch-off-emoji")

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
}
