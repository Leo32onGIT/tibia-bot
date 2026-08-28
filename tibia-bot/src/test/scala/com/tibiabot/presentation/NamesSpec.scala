package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class NamesSpec extends AnyFunSuite with Matchers {

  test("capitalizeWords upper-cases the first letter of each word") {
    Names.capitalizeWords("violent beams") shouldBe "Violent Beams"
    Names.capitalizeWords("the count") shouldBe "The Count"
  }

  test("capitalizeWords leaves the rest of each word untouched (not a full title-case)") {
    Names.capitalizeWords("violent BEAMS") shouldBe "Violent BEAMS"
    Names.capitalizeWords("mcDonald") shouldBe "McDonald"
  }

  test("capitalizeWords handles a single word and an empty string") {
    Names.capitalizeWords("morgaroth") shouldBe "Morgaroth"
    Names.capitalizeWords("") shouldBe ""
  }

  test("a user reads as the account they are, then the name they go by here") {
    Names.user("Beams", "violentbeams") shouldBe "**`violentbeams`** (**@Beams**)"
  }

  test("no nickname falls back to the account name alone, rather than an empty @") {
    Names.user("", "violentbeams") shouldBe "**`violentbeams`**"
    Names.user("   ", "violentbeams") shouldBe "**`violentbeams`**"
  }

  test("a nickname that merely repeats the username is not said twice") {
    // Most people never set one, and Discord hands back the username in its
    // place — "**`bob`** (**@bob**)" is noise, not information.
    Names.user("violentbeams", "violentbeams") shouldBe "**`violentbeams`**"
    Names.user("ViolentBeams", "violentbeams") shouldBe "**`violentbeams`**"
  }

  test("neither half can break out of its own formatting") {
    // A nickname is free text, unlike a username: it may hold backticks and
    // asterisks, and either would otherwise reach into the markdown around it.
    Names.user("ev*il`", "violentbeams") shouldBe "**`violentbeams`** (**@evil**)"
    Names.user("Beams", "vio`lent") shouldBe "**`violent`** (**@Beams**)"
  }

  test("one name is the one they go by here") {
    Names.called("Beams", "violentbeams") shouldBe "**@Beams**"
  }

  test("one name falls back to the account, in the same shape as a nickname") {
    // Same shape so a column of these reads as a column — and Discord writes
    // an account name as @name too, so nothing is claimed that isn't true.
    Names.called("", "violentbeams") shouldBe "**@violentbeams**"
    Names.called("   ", "violentbeams") shouldBe "**@violentbeams**"
    Names.called("", "") shouldBe "**`someone`**"
  }

  test("one name cannot break out of its own formatting either") {
    Names.called("ev*il`", "violentbeams") shouldBe "**@evil**"
    Names.called("", "vio`lent") shouldBe "**@violent**"
  }

  test("the plain one name makes the same choice, without the markdown") {
    Names.calledPlain("Beams", "violentbeams") shouldBe "Beams"
    Names.calledPlain("", "violentbeams") shouldBe "violentbeams"
    Names.calledPlain("   ", "violentbeams") shouldBe "violentbeams"
  }

  test("the plain one name wears no @ either — the page writes its own") {
    Names.calledPlain("Beams", "violentbeams") should not include "@"
    Names.calledPlain("Beams", "violentbeams") should not include "*"
  }

  test("the plain one name is empty when there is neither, rather than a stand-in") {
    // The dashboard says "someone" in its own markup, and only it knows where.
    Names.calledPlain("", "") shouldBe ""
  }

  test("a user with neither name still reads as somebody") {
    Names.user("", "") shouldBe "**`someone`**"
  }

  test("no rendering of a user is ever a mention") {
    Names.user("Beams", "violentbeams") should not include "<@"
    Names.user("", "violentbeams") should not include "<@"
  }
}
