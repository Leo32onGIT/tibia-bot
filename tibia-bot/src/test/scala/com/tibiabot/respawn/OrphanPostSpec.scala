package com.tibiabot.respawn

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The guards on deleting a respawn post that no catalogue row points at.
 *
 *  Worth its own suite because this is the one sweep in the system that can
 *  destroy something nobody can get back. [[RespawnThreads.orphanIds]] is pure
 *  for exactly that reason, so what it spares is checkable here rather than by
 *  watching a real forum.
 */
class OrphanPostSpec extends AnyFunSuite with Matchers {

  private val self = "bot-1"
  private val board = "board-thread"

  /** A forum's posts as the sweep sees them: id, and who opened them. */
  private def forum(posts: (String, String)*): List[(String, String)] = posts.toList

  test("a post of ours the catalogue does not point at is an orphan") {
    val posts = forum("t-live" -> self, "t-orphan" -> self)
    RespawnThreads.orphanIds(posts, Set("t-live", board), self, 25) shouldBe List("t-orphan")
  }

  test("posts somebody else opened are left alone") {
    // Members can open their own posts in the forum — the bot only manages the
    // ones it created, and tidying up is not a licence to delete theirs.
    val posts = forum("t-theirs" -> "member-9", "t-orphan" -> self)
    RespawnThreads.orphanIds(posts, Set(board), self, 25) shouldBe List("t-orphan")
  }

  test("an owner Discord did not tell us about spares the post") {
    RespawnThreads.orphanIds(forum("t-unknown" -> null), Set(board), self, 25) shouldBe empty
  }

  test("the board post is never an orphan, however it reaches the sweep") {
    // It is ours, and no catalogue row points at it — which is exactly the shape
    // of an orphan. Losing it costs the guild the one place its codes are drawn.
    RespawnThreads.orphanIds(forum(board -> self), Set(board), self, 25) shouldBe empty
  }

  test("a pass takes no more than its limit, so a bad read cannot clear a forum") {
    val posts = forum((1 to 10).map(n => s"t-$n" -> self): _*)
    RespawnThreads.orphanIds(posts, Set(board), self, 3) should have size 3
  }

  test("a forum with nothing of ours in it is left untouched") {
    RespawnThreads.orphanIds(forum("t-a" -> "member-1", "t-b" -> "member-2"), Set(board), self, 25) shouldBe empty
  }
}
