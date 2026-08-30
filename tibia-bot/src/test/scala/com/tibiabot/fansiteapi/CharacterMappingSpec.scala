package com.tibiabot.fansiteapi

import com.tibiabot.fansiteapi.response.FansiteCharacterResponse
import com.tibiabot.tibiadata.OriginTimestamp
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.time.Instant

/** Decoding and translation of real fansite API payloads.
 *
 *  Every fixture here is a verbatim capture from the live API, so these pin the
 *  actual wire format rather than a hand-written idea of it. What the suite is
 *  really defending is the claim the whole migration rests on: that a fansite
 *  payload can be presented to the rest of the bot as the `CharacterResponse`
 *  it already reads, with nothing downstream aware of the swap. */
class CharacterMappingSpec extends AnyFunSuite with Matchers with FansiteJsonSupport {

  private def fixture(name: String): FansiteCharacterResponse = {
    val is = getClass.getResourceAsStream(s"/fansiteapi/$name")
    require(is != null, s"missing fixture /fansiteapi/$name")
    try scala.io.Source.fromInputStream(is, "UTF-8").mkString.parseJson.convertTo[FansiteCharacterResponse]
    finally is.close()
  }

  private val origin = Instant.parse("2026-08-30T06:11:47Z")

  test("the narrowed request shape decodes, with unrequested sections absent") {
    // The client always sends include=characterDeathsData, so the other three
    // sections come back null. Decoding must not depend on them.
    val payload = fixture("character_summon_death.json")
    payload.characterGameInformation.characterName shouldBe "Gatorbeug"
    payload.characterDeathsData.map(_.deaths.size) shouldBe Some(2)
  }

  test("the full payload decodes too, ignoring the sections the bot does not model") {
    val payload = fixture("character_full.json")
    payload.characterGameInformation.characterName shouldBe "Violent Beams"
    payload.characterGameInformation.guildName shouldBe Some("Ruckus")
  }

  test("a summon kill keeps the player as the killer and the creature as the summon") {
    // The case that could not be verified from sampling and had to be found by
    // hand. TibiaData reports this death as name="Beams of Justice" with
    // summon="fire elemental" — NOT as "fire elemental of Beams of Justice" —
    // and the mapping has to land on exactly that shape.
    val mapped = CharacterMapping.toCharacterResponse(fixture("character_summon_death.json"), Some(origin))
    val deaths = mapped.character.deaths.getOrElse(fail("expected deaths"))
    val summonDeath = deaths.head

    summonDeath.killers should have size 1
    summonDeath.killers.head.name shouldBe "Beams of Justice"
    summonDeath.killers.head.player shouldBe true
    summonDeath.killers.head.summon shouldBe "fire elemental"

    // The second death is the same killer with no summon, which must map to the
    // empty string TibiaData uses rather than to a null or a literal "None".
    deaths(1).killers.head.summon shouldBe ""
  }

  test("killers and assists are split out of one list, in order") {
    // TibiaData splits these into two lists; this API returns one tagged list.
    // Order matters downstream: the death embed takes its thumbnail from
    // killers.lastOption.
    val payload = fixture("character_assists.json")
    val mapped = CharacterMapping.toCharacterResponse(payload, Some(origin))
    val deaths = mapped.character.deaths.getOrElse(fail("expected deaths"))

    val wire = payload.characterDeathsData.getOrElse(fail("expected deaths")).deaths
    val withAssists = wire.indexWhere(_.murderers.exists(_.assist))
    withAssists should be >= 0

    val expectedKillers = wire(withAssists).murderers.filterNot(_.assist).map(_.name)
    val expectedAssists = wire(withAssists).murderers.filter(_.assist).map(_.name)
    expectedAssists should not be empty

    deaths(withAssists).killers.map(_.name) shouldBe expectedKillers
    deaths(withAssists).assists.map(_.name) shouldBe expectedAssists
  }

  test("a traded murderer keeps its flag") {
    val payload = fixture("character_assists.json")
    val mapped = CharacterMapping.toCharacterResponse(payload, Some(origin))
    val wireTraded = payload.characterDeathsData.getOrElse(fail("expected deaths"))
      .deaths.flatMap(_.murderers).count(_.tradedMurderer)
    val mappedTraded = mapped.character.deaths.getOrElse(Nil)
      .flatMap(d => d.killers ++ d.assists).count(_.traded)
    mappedTraded shouldBe wireTraded
  }

  test("death timestamps become the ISO instants the bot parses") {
    val mapped = CharacterMapping.toCharacterResponse(fixture("character_summon_death.json"), Some(origin))
    val death = mapped.character.deaths.getOrElse(fail("expected deaths")).head
    death.time shouldBe "2026-08-30T06:08:46Z"
    // The stream parses this field directly, so a format it cannot read would
    // throw at runtime rather than fail a decode.
    java.time.ZonedDateTime.parse(death.time).toEpochSecond shouldBe 1788070126L
  }

  test("vocation is rebuilt into TibiaData's promoted display name") {
    CharacterMapping.vocationName("knight", promoted = false) shouldBe "Knight"
    CharacterMapping.vocationName("knight", promoted = true) shouldBe "Elite Knight"
    CharacterMapping.vocationName("paladin", promoted = true) shouldBe "Royal Paladin"
    CharacterMapping.vocationName("sorcerer", promoted = true) shouldBe "Master Sorcerer"
    CharacterMapping.vocationName("druid", promoted = true) shouldBe "Elder Druid"
    CharacterMapping.vocationName("monk", promoted = true) shouldBe "Exalted Monk"
    CharacterMapping.vocationName("none", promoted = false) shouldBe "None"
  }

  test("an unknown vocation degrades to a label rather than throwing") {
    // A vocation added to the game before this table is updated must not turn
    // every character carrying it into an unparseable sheet.
    CharacterMapping.vocationName("necromancer", promoted = true) shouldBe "Necromancer"
  }

  test("the vocation the bot renders survives the round trip") {
    // vocEmoji takes the last word, so the promoted form has to keep it.
    val mapped = CharacterMapping.toCharacterResponse(fixture("character_full.json"), Some(origin))
    mapped.character.character.vocation shouldBe "Master Sorcerer"
    com.tibiabot.presentation.Emojis.vocEmoji(mapped.character.character.vocation) shouldBe ":fire:"
  }

  test("a guild-less character maps to None, not to an empty guild") {
    val mapped = CharacterMapping.toCharacterResponse(fixture("character_summon_death.json"), Some(origin))
    mapped.character.character.guild shouldBe None
  }

  test("a guild maps with its rank") {
    val mapped = CharacterMapping.toCharacterResponse(fixture("character_full.json"), Some(origin))
    mapped.character.character.guild.map(_.name) shouldBe Some("Ruckus")
    mapped.character.character.guild.map(_.rank) shouldBe Some("Shot Caller")
  }

  test("the single former world becomes the list the transfer logic reads") {
    CharacterMapping.toCharacterResponse(fixture("character_full.json"), Some(origin))
      .character.character.former_worlds shouldBe Some(List("Quidera"))
    // Absent rather than an empty list, matching what TibiaData sends.
    CharacterMapping.toCharacterResponse(fixture("character_summon_death.json"), Some(origin))
      .character.character.former_worlds shouldBe None
  }

  test("last login becomes an ISO instant, and zero becomes absent") {
    val mapped = CharacterMapping.toCharacterResponse(fixture("character_full.json"), Some(origin))
    mapped.character.character.last_login shouldBe Some("2026-08-30T04:35:13Z")

    val payload = fixture("character_full.json")
    val never = payload.copy(characterGameInformation = payload.characterGameInformation.copy(lastLogin = 0L))
    CharacterMapping.toCharacterResponse(never, Some(origin)).character.character.last_login shouldBe None
  }

  test("the Last-Modified origin lands where OriginTimestamp reads it") {
    // This is the load-bearing wiring: both cache decorators derive freshness
    // from information.timestamp, and nothing else populates it on this path.
    val mapped = CharacterMapping.toCharacterResponse(fixture("character_full.json"), Some(origin))
    OriginTimestamp.of(mapped.information) shouldBe Some(origin)
  }

  test("a response with no Last-Modified yields unknown freshness rather than a guess") {
    // AgeCachedTibiaApi treats None as "do not cache this", so the character is
    // simply re-fetched next cycle — the safe degradation.
    val mapped = CharacterMapping.toCharacterResponse(fixture("character_full.json"), None)
    OriginTimestamp.of(mapped.information) shouldBe None
  }
}
