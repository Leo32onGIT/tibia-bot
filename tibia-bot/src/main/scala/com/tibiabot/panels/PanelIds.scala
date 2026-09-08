package com.tibiabot.panels

import scala.util.Try

/** Component ids for the three command panels — `/settings`, `/hunted` and
 *  `/allies` — and how BotListener must acknowledge each one.
 *
 *  These commands used to be subcommand trees: twenty-four leaves between them,
 *  every one its own row in Discord's command picker, and every setting
 *  write-only because nothing could show you what it currently was. They are now
 *  three bare commands that answer with a panel of buttons, each opening a form
 *  that both shows the current value and takes the new one.
 *
 *  Ids are `panel:<panel>:<action>` for a button and `panelform:<panel>:<action>`
 *  for the form it opens, so a press and its submission stay recognisably paired
 *  while routing tells them apart on the prefix alone. Everything here is far
 *  inside Discord's hundred-character component-id limit.
 */
object PanelIds {

  val ButtonPrefix: String = "panel:"
  val FormPrefix: String = "panelform:"

  /** Which panel a component belongs to. `/hunted` and `/allies` are the same
   *  panel with different words and one extra button, so they travel as a value
   *  rather than as two parallel sets of ids. */
  sealed trait Panel {
    def token: String
    /** What this panel's list is called in a sentence. */
    def noun: String
  }

  object Panel {
    case object Settings extends Panel { val token = "settings"; val noun = "settings" }
    case object Hunted extends Panel { val token = "hunted"; val noun = "hunted list" }
    case object Allies extends Panel { val token = "allies"; val noun = "allies list" }

    val all: List[Panel] = List(Settings, Hunted, Allies)
    def fromToken(token: String): Option[Panel] = all.find(_.token == token)
  }

  /** How a press must be acknowledged, decided before the handler runs.
   *
   *  Same three cases as the respawn buttons, and for the same reasons: a form
   *  cannot be deferred at all because `replyModal` has to be the interaction's
   *  first response, and a press that rewrites the panel it sits on defers an
   *  edit rather than replying underneath it. */
  sealed trait Ack
  object Ack {
    case object OpensModal extends Ack
    case object EditsMessage extends Ack
    case object Replies extends Ack
  }

  // --- settings actions ----------------------------------------------------

  val Fullbless = "fullbless"
  val Exiva = "exiva"
  val Layout = "layout"
  val Neutral = "neutral"
  val ChannelFilter = "chanfilter"
  val OnlineFilter = "onlinefilter"
  /** Where the bot's command log is posted. The only setting on this panel that
   *  is about the server rather than about a world — hence no world picker on its
   *  form, and last on the panel. */
  val CommandLog = "cmdlog"

  /** Every button on `/settings`, in the order they are drawn: the two that set a
   *  level or a toggle for the whole world, then what the channels and the online
   *  list show, then neutrals — the one that is about players nobody here tracks,
   *  and so the least often wanted — and the command log after them, which is set
   *  once for the server and then forgotten about.
   *
   *  Seven, so they no longer fit one row; Panels.rows splits them 5 and 2. */
  val settingsActions: List[String] = List(Fullbless, Exiva, ChannelFilter, Layout, OnlineFilter, Neutral, CommandLog)

  // --- hunted/allies actions ----------------------------------------------

  val Add = "add"
  val Remove = "remove"
  val Info = "info"
  val Config = "config"
  /** Tag the one player a Look up reply is about. Hunted only — see
   *  panels.ListTags. Carries the name, so its form has nothing to ask for
   *  beyond the tag itself. */
  val TagOne = "tagone"
  val Clear = "clear"
  /** The second press, after the first one asked whether they meant it. */
  val ClearConfirm = "clearconfirm"
  /** Backing out of that question. Its own action rather than reusing another
   *  button: every other one either opens a form or changes something, and this
   *  must do neither — it only puts the panel back. */
  val Cancel = "cancel"

  def listActions(panel: Panel): List[String] = {
    // No "view list" button: the panel's own reply is the list. It costs
    // nothing to draw — see HuntedAlliedService.playersEmbeds — so putting it
    // behind a press only hid what somebody ran the command to see.
    //
    // Five, so they sit on one row: a sixth pushed Clear All onto a second row
    // of its own. Tagging lives in the Add form instead, which can retag an
    // entry already on the list — see HuntedAlliedService.addMany.
    List(Add, Remove, Config, Info, Clear)
  }

  // --- building ------------------------------------------------------------

  def button(panel: Panel, action: String): String = s"$ButtonPrefix${panel.token}:$action"
  def form(panel: Panel, action: String): String = s"$FormPrefix${panel.token}:$action"

  /** The same, carrying a subject — a player's name, for a component that acts on
   *  one particular player rather than on the list.
   *
   *  Third segment onward, so `parse` still reads the panel and action off the
   *  first two and every existing component keeps working. Tibia names contain
   *  spaces but never a colon, and the longest is far inside Discord's
   *  hundred-character id limit.
   */
  def buttonFor(panel: Panel, action: String, subject: String): String =
    s"${button(panel, action)}:$subject"
  def formFor(panel: Panel, action: String, subject: String): String =
    s"${form(panel, action)}:$subject"

  // --- routing -------------------------------------------------------------

  def handlesButton(componentId: String): Boolean = componentId.startsWith(ButtonPrefix)
  def handlesForm(modalId: String): Boolean = modalId.startsWith(FormPrefix)

  private def body(componentId: String): String =
    if (componentId.startsWith(FormPrefix)) componentId.stripPrefix(FormPrefix)
    else componentId.stripPrefix(ButtonPrefix)

  /** None for anything malformed or from an older deploy, so a stale component is
   *  answered rather than throwing. Extra segments are the subject and are
   *  ignored here — see [[subjectOf]]. */
  def parse(componentId: String): Option[(Panel, String)] =
    Try {
      body(componentId).split(':') match {
        case Array(panelToken, action, _*) => Panel.fromToken(panelToken).map(_ -> action)
        case _                             => None
      }
    }.toOption.flatten

  /** What the component acts on, for the ones that name a player. Everything
   *  after the action, rejoined, so a subject is returned whole even if one ever
   *  contains the separator. */
  def subjectOf(componentId: String): Option[String] =
    Try {
      body(componentId).split(':').toList match {
        case _ :: _ :: rest if rest.nonEmpty => Some(rest.mkString(":")).filter(_.nonEmpty)
        case _                               => None
      }
    }.toOption.flatten

  /** Everything opens a form except the three that answer from what the bot
   *  already knows: listing, asking whether they meant Clear, and doing it. */
  def ackFor(componentId: String): Ack =
    parse(componentId) match {
      case Some((_, Clear))        => Ack.EditsMessage
      case Some((_, ClearConfirm)) => Ack.EditsMessage
      case Some((_, Cancel))       => Ack.EditsMessage
      case Some(_)                 => Ack.OpensModal
      // Unparseable: it gets an ephemeral "that button is out of date" reply,
      // which is a message, so it defers one.
      case None                    => Ack.Replies
    }

  def opensModal(componentId: String): Boolean = ackFor(componentId) == Ack.OpensModal
}
