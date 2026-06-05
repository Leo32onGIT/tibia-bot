package com.tibiabot.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class BossAliasesSpec extends AnyFunSuite with Matchers {

  test("a known shorthand resolves to the canonical boss name") {
    BossAliases.canonical("oberon")      shouldBe "grand master oberon"
    BossAliases.canonical("zyrtarch")    shouldBe "soul of dragonking zyrtarch"
    BossAliases.canonical("lib final")   shouldBe "the scourge of oblivion"
    BossAliases.canonical("undead seal") shouldBe "ragiaz"
  }

  test("an unknown name (including a canonical name) passes through unchanged") {
    // canonical boss names are the alias VALUES, not keys, so they pass through
    BossAliases.canonical("grand master oberon") shouldBe "grand master oberon"
    BossAliases.canonical("ferumbras")           shouldBe "ferumbras"
    BossAliases.canonical("")                    shouldBe ""
  }

  test("several aliases collapse onto the same canonical name") {
    Seq("despor", "dragon hoard", "vengar", "dragon bosses").foreach { alias =>
      BossAliases.canonical(alias) shouldBe "dragon pack"
    }
  }

  test("resolution is single-step — no canonical value is itself an alias key") {
    // guards against needing chained lookups: canonical() does one getOrElse, so a
    // value that were also a key would resolve inconsistently.
    Seq("grand master oberon", "dragon pack", "ragiaz", "the scourge of oblivion")
      .foreach(v => BossAliases.canonical(v) shouldBe v)
  }
}
