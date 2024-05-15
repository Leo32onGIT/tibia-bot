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
  val satchelEmoji: String = discord.getString("satchel-emoji")

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
    "Zunera",
    "Victoris",
    "Oceanis",
    "Stralis",
    "Yara",
    "Vandera",
    "Unebra"
  )
  // old worlds that have been merged
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
  // list of creatures for command input validation
  val creaturesList: List[String] = List(
    "abyssal calamary",
    "acid blob",
    "acolyte of darkness",
    "acolyte of the cult",
    "adept of the cult",
    "adult goanna",
    "adventurer",
    "afflicted strider",
    "aggressive chicken",
    "agrestic chicken",
    "albino dragon",
    "amazon",
    "ancient lion knight",
    "ancient scarab",
    "ancient ugly monster",
    "angry adventurer",
    "angry demon",
    "angry plant",
    "animated clomp",
    "animated cyclops",
    "animated feather",
    "animated guzzlemaw",
    "animated moohtant",
    "animated mummy",
    "animated ogre brute",
    "animated ogre savage",
    "animated ogre shaman",
    "animated rotworm",
    "animated skunk",
    "animated snowman",
    "animated sword",
    "arachnophobica",
    "arctic faun",
    "armadile",
    "askarak demon",
    "askarak lord",
    "askarak prince",
    "assassin",
    "azure frog",
    "badger",
    "baleful bunny",
    "bandit",
    "bane bringer",
    "bane of light",
    "banshee",
    "barbarian bloodwalker",
    "barbarian brutetamer",
    "barbarian headsplitter",
    "barbarian skullhunter",
    "barkless devotee",
    "barkless fanatic",
    "bashmu",
    "bat",
    "bear",
    "behemoth",
    "bellicose orger",
    "berrypest",
    "berserker chicken",
    "betrayed wraith",
    "biting book",
    "black cobra",
    "black sheep",
    "black sphinx acolyte",
    "blazing fire elemental",
    "blemished spawn",
    "blightwalker",
    "blistering fire elemental",
    "bloated man-maggot",
    "blood beast",
    "blood crab",
    "blood hand",
    "blood priest",
    "bloom of doom",
    "blue djinn",
    "boar man",
    "boar",
    "bog frog",
    "bog raider",
    "bonebeast",
    "bonelord",
    "bonny bunny",
    "bony sea devil",
    "boogy",
    "bound astral power",
    "brachiodemon",
    "brain squid",
    "braindeath",
    "branchy crawler",
    "breach brood",
    "bride of night",
    "brimstone bug",
    "brittle skeleton",
    "broken shaper",
    "bug",
    "bulltaur alchemist",
    "bulltaur brute",
    "bulltaur forgepriest",
    "burning book",
    "burning gladiator",
    "burster spectre",
    "butterfly",
    "cake golem",
    "calamary",
    "canopic jar",
    "capricious phantom",
    "carniphila",
    "carnisylvan sapling",
    "carnivostrich",
    "carrion worm",
    "cat",
    "cave chimera",
    "cave devourer",
    "cave hydra",
    "cave parrot",
    "cave rat",
    "cellar rat",
    "centipede",
    "chakoya toolshaper",
    "chakoya tribewarden",
    "chakoya windcaller",
    "charged disruption",
    "charged energy elemental",
    "charger",
    "charging Outburst",
    "chasm spawn",
    "chicken",
    "choking fear",
    "clay guardian",
    "cliff strider",
    "cloak of terror",
    "clomp",
    "cobra assassin",
    "cobra scout",
    "cobra vizier",
    "cobra",
    "cocoon",
    "containment crystal",
    "containment machine",
    "control tower",
    "converter",
    "coral frog",
    "corrupt naga",
    "corrupted soul",
    "corym charlatan",
    "corym skirmisher",
    "corym vanguard",
    "cosmic energy prism A",
    "cosmic energy prism B",
    "cosmic energy prism C",
    "cosmic energy prism D",
    "courage leech",
    "cow",
    "crab",
    "crackler",
    "crape man",
    "crawler",
    "crazed beggar",
    "crazed dwarf",
    "crazed summer rearguard",
    "crazed summer vanguard",
    "crazed winter rearguard",
    "crazed winter vanguard",
    "crimson frog",
    "crocodile",
    "crustacea gigantica",
    "crypt defiler",
    "crypt shambler",
    "crypt warden",
    "crypt warrior",
    "crystal spider",
    "crystal wolf",
    "crystalcrusher",
    "cult believer",
    "cult enforcer",
    "cult scholar",
    "cunning werepanther",
    "cursed ape",
    "cursed book",
    "cursed prospector",
    "cyclops drone",
    "cyclops smith",
    "cyclops",
    "damaged crystal golem",
    "damaged worker golem",
    "damned soul",
    "dark apprentice",
    "dark carnisylvan",
    "dark faun",
    "dark magician",
    "dark monk",
    "dark soul reaper",
    "dark soul",
    "dark torturer",
    "darklight construct",
    "darklight emitter",
    "darklight matter",
    "darklight source",
    "darklight striker",
    "dawn bat",
    "dawn scorpion",
    "dawnfire asura",
    "dawnfly",
    "death blob",
    "death dragon",
    "death priest",
    "death reaper",
    "deathling scout",
    "deathling spellsinger",
    "deathslicer",
    "deathspawn",
    "decaying totem",
    "deepling brawler",
    "deepling elite",
    "deepling guard",
    "deepling master librarian",
    "deepling scout",
    "deepling spellsinger",
    "deepling tyrant",
    "deepling warrior",
    "deepling worker",
    "deepsea blood crab",
    "deepworm",
    "deer",
    "defiler",
    "demon outcast",
    "demon parrot",
    "demon skeleton",
    "demon",
    "depolarized crackler",
    "depowered minotaur",
    "desperate white deer",
    "destroyed pillar",
    "destroyer",
    "devourer",
    "diabolic imp",
    "diamond servant replica",
    "diamond servant",
    "dire penguin",
    "diremaw",
    "disgusting ooze",
    "disruption",
    "distorted phantom",
    "dog",
    "domestikion",
    "doom deer",
    "doomsday cultist",
    "dragolisk",
    "dragon hatchling",
    "dragon lord hatchling",
    "dragon lord",
    "dragon servant",
    "dragon wrath",
    "dragonling",
    "dragon",
    "draken abomination",
    "draken elite",
    "draken spellweaver",
    "draken warmaster",
    "draptor",
    "dread intruder",
    "dread minion",
    "dreadbeast",
    "drillworm",
    "dromedary",
    "druid familiar",
    "druid\'s apparition",
    "dryad",
    "duskbringer",
    "dwarf dispenser",
    "dwarf geomancer",
    "dwarf guard",
    "dwarf henchman",
    "dwarf miner",
    "dwarf soldier",
    "dwarf",
    "dworc fleshhunter",
    "dworc venomsniper",
    "dworc voodoomaster",
    "earth elemental",
    "earworm",
    "efreet",
    "egg",
    "elder bonelord",
    "elder forest fury",
    "elder mummy",
    "elder wyrm",
    "elephant",
    "elf arcanist",
    "elf overseer",
    "elf scout",
    "elf",
    "emerald damselfly",
    "emerald tortoise",
    "empowered glooth horror",
    "energetic book",
    "energized raging mage",
    "energuardian of tales",
    "energy elemental",
    "energy pulse",
    "enfeebled silencer",
    "enlightened of the cult",
    "enraged bookworm",
    "enraged crystal golem",
    "enraged sand brood",
    "enraged soul",
    "enraged squirrel",
    "enraged white deer",
    "enslaved dwarf",
    "enthralled demon",
    "eruption of destruction",
    "essence of darkness",
    "eternal guardian",
    "evil prospector",
    "evil sheep lord",
    "evil sheep",
    "execowtioner",
    "exotic bat",
    "exotic cave spider",
    "eyeless devourer",
    "eye of the seven",
    "falcon knight",
    "falcon paladin",
    "faun",
    "feeble glooth horror",
    "feral sphinx",
    "feral werecrocodile",
    "feverish citizen",
    "feversleep",
    "filth toad",
    "fire devil",
    "fire elemental",
    "firestarter",
    "fish",
    "flame of Omrafir",
    "flamethrower",
    "flamingo",
    "flimsy lost soul",
    "floating savant",
    "flying book",
    "foam stalker",
    "forest fury",
    "fox",
    "frazzlemaw",
    "freakish lost soul",
    "freed soul",
    "frost dragon hatchling",
    "frost dragon",
    "frost flower asura",
    "frost giantess",
    "frost giant",
    "frost servant",
    "frost spider",
    "frost troll",
    "frozen minion",
    "fungosaurus",
    "fury",
    "furious fire elemental",
    "furious troll",
    "fury of the emperor",
    "gang member",
    "gargoyle",
    "gazer spectre",
    "gazer",
    "ghastly dragon",
    "ghost wolf",
    "ghost of a planegazer",
    "ghost",
    "ghoulish hyaena",
    "ghoul",
    "giant spider",
    "girtablilu warrior",
    "gladiator",
    "gloom wolf",
    "glooth anemone",
    "glooth bandit",
    "glooth battery",
    "glooth blob",
    "glooth bomb",
    "glooth brigand",
    "glooth golem",
    "glooth horror",
    "glooth masher",
    "glooth-powered minotaur",
    "glooth slasher",
    "glooth trasher",
    "glooth generator",
    "gnarlhound",
    "gnome pack crawler",
    "goblin assassin",
    "goblin leader",
    "goblin scavenger",
    "goblin",
    "golden servant replica",
    "golden servant",
    "goldhanded cultist bride",
    "goldhanded cultist",
    "golem dispenser",
    "gore horn",
    "gorerilla",
    "gozzler",
    "grave guard",
    "grave robber",
    "gravedigger",
    "greater canopic jar",
    "greater death minion",
    "greater energy elemental",
    "greater fire elemental",
    "green djinn",
    "green frog",
    "grim reaper",
    "grimeleech",
    "grynch clan goblin",
    "gryphon",
    "guardian golem",
    "guardian of tales",
    "guzzlemaw",
    "hand of cursed fate",
    "hardened usurper archer",
    "hardened usurper knight",
    "hardened usurper warlock",
    "harpy",
    "haunted dragon",
    "haunted treeling",
    "hazardous phantom",
    "headpecker",
    "hell hole",
    "hellfire fighter",
    "hellflayer",
    "hellhound",
    "hellish portal",
    "hellspawn",
    "herald of gloom",
    "hero",
    "hibernal moth",
    "hideous fungus",
    "high voltage elemental",
    "hive overseer",
    "hive pore",
    "holy bog frog",
    "honour guard",
    "hoodinion",
    "horse",
    "hot dog",
    "hulking carnisylvan",
    "hulking prehemoth",
    "humongous fungus",
    "humorless fungus",
    "hunger worm",
    "hunter",
    "husky",
    "hyaena",
    "hydra",
    "ice dragon",
    "ice golem",
    "ice witch",
    "icecold book",
    "icicle",
    "iks ahpututu",
    "iks aucar",
    "iks chuka",
    "iks churrascan",
    "iks pututu",
    "iks yapunac",
    "infected weeper",
    "infernal demon",
    "infernal frog",
    "infernal phantom",
    "infernalist",
    "ink blob",
    "insane siren",
    "insect swarm",
    "insectoid scout",
    "insectoid worker",
    "instable breach brood",
    "instable sparkion",
    "iron servant replica",
    "iron servant",
    "ironblight",
    "island troll",
    "jagged earth elemental",
    "jellyfish",
    "juggernaut",
    "jungle moa",
    "juvenile bashmu",
    "juvenile cyclops",
    "killer caiman",
    "killer rabbit",
    "knight familiar",
    "knight\'s apparition",
    "knowledge elemental",
    "knowledge raider",
    "kollos",
    "kongra",
    "lacewing moth",
    "ladybug",
    "lamassu",
    "lancer beetle",
    "larva",
    "lava golem",
    "lava lurker attendant",
    "lava lurker",
    "lavafungus",
    "lavahole",
    "lavaworm",
    "leaf golem",
    "lesser death minion",
    "lesser fire devil",
    "lesser magma crystal",
    "lesser swarmer",
    "lich",
    "liodile",
    "lion archer",
    "lion commander",
    "lion knight",
    "lion warlock",
    "lion",
    "little corym charlatan",
    "lizard chosen",
    "lizard dragon priest",
    "lizard high guard",
    "lizard legionnaire",
    "lizard magistratus",
    "lizard noble",
    "lizard sentinel",
    "lizard snakecharmer",
    "lizard templar",
    "lizard zaogun",
    "lonely frazzlemaw",
    "loricate orger",
    "lost basher",
    "lost berserker",
    "lost exile",
    "lost ghost of a planegazer",
    "lost gnome",
    "lost husher",
    "lost soul",
    "lost thrower",
    "lost time",
    "lucifuga aranea",
    "lumbering carnivor",
    "mad scientist",
    "mad sheep",
    "magic pillar",
    "magical sphere",
    "magicthrower",
    "magma crawler",
    "magma crystal",
    "makara",
    "makeshift home",
    "mammoth",
    "manta ray",
    "manticore",
    "mantosaurus",
    "many faces",
    "marid",
    "marsh stalker",
    "massive earth elemental",
    "massive energy elemental",
    "massive fire elemental",
    "massive water elemental",
    "meadow strider",
    "mean lost soul",
    "mean maw",
    "mean minion",
    "meandering mushroom",
    "mearidion",
    "mechanical fighter",
    "medusa",
    "mega dragon",
    "memory of a banshee",
    "memory of a book",
    "memory of a carnisylvan",
    "memory of a dwarf",
    "memory of a faun",
    "memory of a frazzlemaw",
    "memory of a fungus",
    "memory of a golem",
    "memory of a hero",
    "memory of a hydra",
    "memory of a lizard",
    "memory of a mammoth",
    "memory of a manticore",
    "memory of a pirate",
    "memory of a scarab",
    "memory of a shaper",
    "memory of a vampire",
    "memory of a werelion",
    "memory of a wolf",
    "memory of a yalahari",
    "memory of an amazon",
    "memory of an elf",
    "memory of an insectoid",
    "memory of an ogre",
    "menacing carnivor",
    "mercurial menace",
    "mercury blob",
    "merlkin",
    "metal gargoyle",
    "midnight asura",
    "midnight panther",
    "midnight spawn",
    "midnight warrior",
    "minion of Gaz\'haragoth",
    "minion of Versperoth",
    "minotaur amazon",
    "minotaur archer",
    "minotaur bruiser",
    "minotaur cult follower",
    "minotaur cult prophet",
    "minotaur cult zealot",
    "minotaur guard",
    "minotaur hunter",
    "minotaur idol",
    "minotaur invader",
    "minotaur mage",
    "minotaur occultist",
    "minotaur poacher",
    "minotaur totem",
    "minotaur",
    "mirror image",
    "misguided bully",
    "misguided shadow",
    "misguided thief",
    "mitmah scout",
    "mitmah seer",
    "modified gnarlhound",
    "mole",
    "monk of the order",
    "monk",
    "mooh\'tah warrior",
    "moohtant wallbreaker",
    "moohtant",
    "mould phantom",
    "mountain troll",
    "muddy earth elemental",
    "muglex clan assassin",
    "muglex clan feetman",
    "muglex clan scavenger",
    "mummy",
    "murmillion",
    "museum stone golem",
    "museum stone rhino",
    "mushroom sniffer",
    "mutated bat",
    "mutated human",
    "mutated rat",
    "mutated tiger",
    "mycobiontic beetle",
    "mystic energy",
    "naga archer",
    "naga warrior",
    "necromancer servant",
    "necromancer",
    "necromantic energy",
    "necromantic focus",
    "neutral deepling warrior",
    "nightfiend",
    "nighthunter",
    "nightmare scion",
    "nightmare of Gaz\'haragoth",
    "nightmare",
    "nightslayer",
    "nightstalker",
    "noble lion",
    "nomad",
    "northern pike",
    "novice of the cult",
    "noxious ripptor",
    "nymph",
    "ogre brute",
    "ogre rowdy",
    "ogre ruffian",
    "ogre sage",
    "ogre savage",
    "ogre shaman",
    "omnivora",
    "oozing carcass",
    "oozing corpus",
    "orc berserker",
    "orc cult fanatic",
    "orc cult inquisitor",
    "orc cult minion",
    "orc cult priest",
    "orc cultist",
    "orc leader",
    "orc marauder",
    "orc rider",
    "orc shaman",
    "orc spearman",
    "orc warlord",
    "orc warrior",
    "orchid frog",
    "orclops doomhauler",
    "orclops ravager",
    "orc",
    "orewalker",
    "orger",
    "overcharged disruption",
    "overcharged energy element",
    "overcharge",
    "paladin familiar",
    "paladin\'s apparition",
    "panda",
    "parasite",
    "parder",
    "parrot",
    "party skeleton",
    "penguin",
    "percht",
    "phantasm",
    "pigeon",
    "pig",
    "pillar of death",
    "pillar of draining",
    "pillar of healing",
    "pillar of protection",
    "pillar of summoning",
    "pillar",
    "piñata dragon",
    "pirat bombardier",
    "pirat cutthroat",
    "pirat mate",
    "pirat scoundrel",
    "pirate buccaneer",
    "pirate corsair",
    "pirate cutthroat",
    "pirate ghost",
    "pirate marauder",
    "pirate skeleton",
    "pixie",
    "plaguesmith",
    "plaguethrower",
    "planedweller",
    "player",
    "plunder patriarch",
    "poacher",
    "poison spider",
    "poisonous carnisylvan",
    "polar bear",
    "poodle",
    "pooka",
    "possessed tree",
    "priestess of the wild sun",
    "priestess",
    "primal pack beast",
    "putrid mummy",
    "quara constrictor scout",
    "quara constrictor",
    "quara hydromancer scout",
    "quara hydromancer",
    "quara mantassin scout",
    "quara mantassin",
    "quara pincher scout",
    "quara pincher",
    "quara predator scout",
    "quara predator",
    "rabbit",
    "radicular totem",
    "rage squid",
    "raging fire",
    "rat",
    "ravenous lava lurker",
    "reality reaver",
    "reality reavers", // Note: Intentionally left pluralized since there is already a separate `reality reaver` entry.
    "redeemed soul",
    "reflection of a Mage",
    "reflection of Mawhawk",
    "reflection of Obujos",
    "renegade knight",
    "renegade quara constrictor",
    "renegade quara hydromancer",
    "renegade quara mantassin",
    "renegade quara pincher",
    "renegade quara predator",
    "retching horror",
    "rhindeer",
    "rift brood",
    "rift fragment",
    "rift invader",
    "rift scythe",
    "rift worm",
    "ripper spectre",
    "roaring lion",
    "roaring water elemental",
    "roast pork",
    "rogue naga",
    "rorc",
    "rot elemental",
    "rotten golem",
    "rotten man-maggot",
    "rotworm",
    "rustheap golem",
    "sabretooth",
    "sacred snake",
    "sacred spider",
    "salamander trainer",
    "salamander",
    "sand brood",
    "sand vortex",
    "sandcrawler",
    "sandstone scorpion",
    "scar tribe shaman",
    "scar tribe warrior",
    "scarab",
    "schiach",
    "scissorion",
    "scorn of the emperor",
    "scorpion",
    "sea serpent",
    "seacrest serpent",
    "seagull",
    "security golem",
    "serpent spawn",
    "servant golem",
    "servant of Tentugly",
    "shaburak demon",
    "shaburak lord",
    "shaburak prince",
    "shadow fiend",
    "shadow hound",
    "shadow pupil",
    "shadow tentacle",
    "shaper matriarch",
    "shark",
    "sheep",
    "shimmying butterfly",
    "shiversleep",
    "shock head",
    "shredderthrower",
    "shrieking cry-stal",
    "sibang",
    "sight of surrender",
    "silencer",
    "silver rabbit",
    "skeleton elite warrior",
    "skeleton warrior",
    "skeleton",
    "skunk",
    "slick water elemental",
    "slime",
    "slippery northern pike",
    "slug",
    "smuggler",
    "snake god essence",
    "snake",
    "solitary frost dragon",
    "somewhat beatable dragon",
    "son of Verminor",
    "sopping carcass",
    "sopping corpus",
    "sorcerer familiar",
    "sorcerer\'s apparition",
    "soul reaper",
    "soul spark",
    "soul-broken harbinger",
    "soulcatcher",
    "souleater",
    "sparkion",
    "spark of destruction",
    "spawn of Devovorga",
    "spectre",
    "sphere of wrath",
    "sphinx",
    "spider",
    "spidris elite",
    "spidris",
    "spiky carnivor",
    "spirits of fertilit",
    "spit nettle",
    "spitter",
    "spyrat",
    "squid warden",
    "squidgy slime",
    "squirrel",
    "stabilizing dread intruder",
    "stabilizing reality reaver",
    "stalker",
    "stalking stalk",
    "stampor",
    "starving wolf",
    "stone devourer",
    "stone golem",
    "stone rhino",
    "stonerefiner",
    "strange rat",
    "strange slime",
    "streaked devourer",
    "strong glooth horror",
    "sulphider",
    "sulphur geysir",
    "sulphur spouter",
    "sun-marked goanna",
    "superior death minion",
    "swamp troll",
    "swampling",
    "swan maiden",
    "swarmer hatchling",
    "swarmer",
    "sword of vengeance",
    "symbol of fear",
    "symbol of hatred",
    "tainted soul",
    "tarantula",
    "tarnished spirit",
    "terramite",
    "terrified elephant",
    "terror bird",
    "terrorsleep",
    "thanatursus",
    "thief",
    "thieving squirrel",
    "thorn minion",
    "thorn steed",
    "thornback tortoise",
    "thornfire wolf",
    "tiger",
    "time keeper",
    "time waster",
    "toad",
    "tomb servant",
    "tormented ghost",
    "tortoise",
    "toxic swarm",
    "travelling merchant",
    "tremendous tyrant",
    "tremor worm",
    "troll champion",
    "troll guard",
    "troll legionnaire",
    "troll marauder",
    "troll-trained salamander",
    "troll",
    "true dawnfire asura",
    "true frost flower asura",
    "true midnight asura",
    "tunnel devourer",
    "tunnel tyrant",
    "turbulent elemental",
    "twisted pooka",
    "twisted shaper",
    "two-headed turtle",
    "ugly monster",
    "unbeatable dragon",
    "unbound blightwalker",
    "unbound defiler",
    "unbound demon outcast",
    "unbound demon",
    "undead cavebear",
    "undead dragon",
    "undead elite gladiator",
    "undead gladiator",
    "undead jester",
    "undead mine worker",
    "undead prospector",
    "undertaker",
    "unexpected",
    "uninvited",
    "unsolicited",
    "unstable spark",
    "unstable tunnel",
    "unwanted",
    "usurper archer",
    "usurper commander",
    "usurper knight",
    "usurper warlock",
    "valkyrie",
    "vampire bride",
    "vampire pig",
    "vampire viscount",
    "vampire",
    "varnished diremaw",
    "venerable foam stalker",
    "venerable girtablilu",
    "vermin swarm",
    "vexclaw",
    "vibrant phantom",
    "vicious lich",
    "vicious manbat",
    "vicious squire",
    "vile destroyer",
    "vile grandmaster",
    "voidshard",
    "vulcongra",
    "vulnerable cocoon",
    "wailing widow",
    "walker",
    "walking pillar",
    "wandering pillar",
    "war golem",
    "war wolf",
    "wardragon",
    "warlock",
    "waspoid",
    "wasp",
    "water buffalo",
    "water elemental",
    "weakened demon",
    "weakened frazzlemaw",
    "weakened glooth horror",
    "weeper",
    "werebadger",
    "werebear",
    "wereboar",
    "werecrocodile",
    "werefox",
    "werehyaena shaman",
    "werehyaena",
    "werelioness",
    "werelion",
    "werepanther",
    "weretiger",
    "werewolf",
    "white deer",
    "white lion",
    "white shade",
    "white tiger",
    "white weretiger",
    "wiggler",
    "wild dog",
    "wild horse",
    "wild warrior",
    "wilting leaf golem",
    "winter wolf",
    "wisp",
    "witch",
    "wolf",
    "woodling",
    "worker golem",
    "worm priestess",
    "wyrm",
    "wyvern",
    "yalahari despoiler",
    "yeti",
    "yielothax",
    "young goanna",
    "young sea serpent",
    "young troll",
    "zombie"
  )
}
