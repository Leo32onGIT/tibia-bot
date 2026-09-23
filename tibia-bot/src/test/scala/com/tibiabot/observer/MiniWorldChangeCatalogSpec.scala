package com.tibiabot.observer

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MiniWorldChangeCatalogSpec extends AnyFunSuite with Matchers {

  private def page(name: String) = s"https://tibia.fandom.com/wiki/${name}_Mini_World_Change"

  test("links a change to its wiki page by a keyword in its title, ignoring case") {
    MiniWorldChangeCatalog.wikiUrl("Fury Gates") shouldBe page("Fury_Gates")
    MiniWorldChangeCatalog.wikiUrl("fury gate") shouldBe page("Fury_Gates")
    MiniWorldChangeCatalog.wikiUrl("Chakoya Iceberg") shouldBe page("Chakoya_Iceberg")
  }

  test("sends every regional Spirit Gate to the one Spirit Grounds page") {
    MiniWorldChangeCatalog.wikiUrl("Spirit Gate Vengoth") shouldBe page("Spirit_Grounds")
    MiniWorldChangeCatalog.wikiUrl("Spirit Gate Darama") shouldBe page("Spirit_Grounds")
    MiniWorldChangeCatalog.wikiUrl("Spirit Grounds") shouldBe page("Spirit_Grounds")
  }

  test("links an unrecognised title to the list of every change") {
    MiniWorldChangeCatalog.wikiUrl("Something New") shouldBe "https://tibia.fandom.com/wiki/Mini_World_Changes"
  }
}
