package com.tibiabot.presentation

import java.nio.charset.StandardCharsets

/** Links a race the kill statistics reported to its page on the Tibia wiki.
 *
 *  ==Why this is not a string rewrite==
 *  Kill statistics name a creature the way they count it — lowercase and plural,
 *  `flimsy lost souls` — and the wiki titles its pages in the singular,
 *  `Flimsy Lost Soul`. The endpoint carries no singular anywhere, so the name
 *  has to be derived, and the plural cannot simply be linked as it stands: of
 *  eight rows put through the wiki's API only `Frazzlemaws` had a plural
 *  redirect, so linking what the endpoint hands over would mostly be redlinks.
 *
 *  ==Which way round the guessing goes==
 *  Deriving a singular from a plural is the hard direction — `cyclopes`,
 *  `sabreteeth` and `fungi` have no rule that recovers them — so this goes the
 *  other way. `Config.creaturesListFromApi` is the wiki's own index of creature
 *  pages, already fetched at startup and already singular. Every name on it is
 *  pluralised into the few forms English and Tibia actually use, and the index
 *  is keyed on those. A reported race is then a lookup rather than an inference:
 *  it either matches a page the wiki really has, or it goes unlinked.
 *
 *  That asymmetry is what makes this cheap to be wrong about. The row prints the
 *  reported race either way — capitalised, but never reworded — so a miss costs
 *  a link, never a wrong name, and nothing has to be trusted to guess well.
 *  Measured against a live day: 97% of every race killed, and 100 of 100 of the
 *  rows actually shown, which are the top ten on ten worlds.
 *
 *  Not merged into [[Urls]], whose creature helpers point at tibiawiki.com.br
 *  and carry that wiki's disambiguations (`Sabretooth_(Criatura)`), which 404
 *  on Fandom. The casing rules there solve a different problem too: they build a
 *  page name out of an arbitrary string, where this matches against titles the
 *  wiki has already spelled.
 *
 *  @param singulars the wiki's creature page titles, singular and correctly
 *                   cased, passed in rather than read from Config so this stays
 *                   decoupled from config loading and unit-testable
 *  @param overrides pages no pluralisation rule can reach, by the lowercase
 *                   race, for where the two sources disagree on the word rather
 *                   than its ending — `cyclopes` is not `cyclops` with a
 *                   suffix. Hand-kept in mappings.conf and checked first
 */
final class CreatureWiki(singulars: List[String], overrides: Map[String, String] = Map.empty) {

  /** Titles as the wiki spells them win outright: a page that really is called
   *  this is never given up for some other creature's guessed-at plural. */
  private val exact: Map[String, String] =
    singulars.map(title => title.toLowerCase -> title).toMap

  /** The pluralised forms, minus any that two different pages both claim.
   *
   *  Nothing collides on today's list — the rules are narrow enough that
   *  `slimes` belongs to `Slime` alone rather than being fought over by `Slim` —
   *  but the list is refetched from a wiki anyone can edit, so an ambiguous key
   *  drops out rather than resolving to whichever page happened to be read
   *  first. An unlinked row is a much smaller wrong than a confident link to the
   *  wrong creature. */
  private val guessed: Map[String, String] =
    singulars
      .flatMap(title => CreatureWiki.variants(title).map(_.toLowerCase -> title))
      .groupBy { case (key, _) => key }
      .collect {
        case (key, pairs) if pairs.map { case (_, title) => title }.distinct.sizeIs == 1 =>
          key -> pairs.head._2
      }

  // Hand-checked beats spelled-out beats guessed.
  private val byRace: Map[String, String] =
    guessed ++ exact ++ overrides.map { case (race, title) => race.toLowerCase -> title }

  /** The wiki's title for a reported race, or none if nothing on the list
   *  pluralises to it. */
  def titleFor(race: String): Option[String] = byRace.get(race.trim.toLowerCase)

  /** The page URL for a reported race, ready to sit inside a Discord link. */
  def urlFor(race: String): Option[String] = titleFor(race).map(CreatureWiki.urlForTitle)
}

object CreatureWiki {

  private val Base = "https://tibia.fandom.com/wiki/"

  /** Unreserved in a path and safe inside Discord's `[text](url)`, which ends
   *  the link at the first `)` — and 118 of the wiki's titles are disambiguated
   *  like `Amarie (Creature)`, so the parentheses have to be escaped or those
   *  rows render as broken markdown rather than as links. */
  private val Unreserved: Set[Char] =
    (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).toSet ++ Set('_', '-', '.', '~', '\'')

  def urlForTitle(title: String): String =
    Base + title.trim.replace(' ', '_')
      .getBytes(StandardCharsets.UTF_8)
      .map { byte =>
        val char = (byte & 0xFF).toChar
        if (Unreserved.contains(char)) char.toString else f"%%${byte & 0xFF}%02X"
      }
      .mkString

  /** The reported race, cased the way the wiki cases the page it matched.
   *
   *  [[Urls.titleCase]] cannot do this on its own. It capitalises the letter
   *  after any punctuation, which is what `Mooh'Tah Warriors` needs and what
   *  `Druid's Apparitions` must not have, and no rule tells those apart — it is
   *  the same ambiguity the creature-url-mappings table exists to paper over for
   *  the other wiki. Fifteen of the races killed on a live day are possessive
   *  like that, so it is not a corner.
   *
   *  The matched title settles it, and it lines up word for word: every rule in
   *  [[pluralForms]] rewrites one word and leaves the count alone. A word the
   *  title also has is taken from the title outright; the one word that was
   *  pluralised keeps the title word's leading case. Should the counts ever not
   *  line up, the regex has the last word rather than this guessing at an
   *  alignment it cannot see.
   */
  def casedLike(reported: String, title: String): String = {
    val words = reported.split(" ")
    val titled = title.split(" ")
    if (words.length != titled.length) Urls.titleCase(reported)
    else words.zip(titled).map {
      case (word, fromTitle) if word.equalsIgnoreCase(fromTitle) => fromTitle
      case (word, fromTitle) if fromTitle.headOption.exists(_.isUpper) => word.capitalize
      case (word, _) => word
    }.mkString(" ")
  }

  /** How one word might be pluralised.
   *
   *  Only the endings English actually triggers on, deliberately: a blanket
   *  `+es` on every word is what lets `Slim` claim `slimes` from `Slime`, and a
   *  key two pages claim is a key [[CreatureWiki]] has to throw away. Tibia's
   *  own oddities are in here too — `patriarches` and `inferniarches` fall out
   *  of the `ch` rule, `heartlesses` out of the `s` one — and `Shaman` gets
   *  `shamans` as well as `shamen`, since the game uses both shapes across
   *  different creatures and an extra key costs nothing.
   *
   *  Irregulars with no rule at all (`cyclopes`, `fungi` from `fungus` aside,
   *  `sabreteeth`, `fish`) are left to miss and go unlinked. They are the long
   *  tail no top ten has yet contained. */
  def pluralForms(word: String): Set[String] = {
    val lower = word.toLowerCase
    Set(word + "s") ++
      Option.when(lower.matches(".*(s|x|z|ch|sh|o)"))(word + "es") ++
      Option.when(lower.endsWith("man"))(word.dropRight(3) + "men") ++
      Option.when(lower.matches(".*[^aeiou]y"))(word.dropRight(1) + "ies") ++
      (if (lower.endsWith("fe")) Some(word.dropRight(2) + "ves")
       else Option.when(lower.endsWith("f"))(word.dropRight(1) + "ves")) ++
      Option.when(lower.endsWith("a"))(word + "e") ++
      Option.when(lower.endsWith("us"))(word.dropRight(2) + "i") ++
      // A name already plural in the singular, so the race reads the same:
      // "Muglex Clan Feetman" is counted as itself, not as "feetmans".
      Option.when(lower.endsWith("s"))(word)
  }

  /** Every string a page's title might be reported as.
   *
   *  Both ends of the name, because Tibia pluralises whichever word is the noun:
   *  `Sineater Inferniarch` becomes `sineater inferniarches` at the tail, while
   *  `Acolyte of the Cult` becomes `acolytes of the cult` at the head. The
   *  unchanged title is in the set as well, for the races the endpoint reports
   *  in the singular. */
  def variants(name: String): Set[String] = {
    val words = name.split(" ").toList
    words match {
      case Nil | _ :: Nil =>
        Set(name) ++ words.headOption.toSet.flatMap(pluralForms)
      case head :: tail =>
        Set(name) ++
          pluralForms(words.last).map(form => (words.init :+ form).mkString(" ")) ++
          pluralForms(head).map(form => (form :: tail).mkString(" "))
    }
  }
}
