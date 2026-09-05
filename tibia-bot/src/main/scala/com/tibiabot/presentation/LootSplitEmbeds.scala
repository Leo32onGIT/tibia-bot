package com.tibiabot.presentation

import com.tibiabot.lootsplit.{HuntMember, HuntSession, HuntTransfer}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

import java.time.temporal.ChronoUnit
import java.util.Locale

/** The reply to a pasted party hunt analyser: what the party made, who did what,
 *  and the exact commands that square everyone up.
 *
 *  The transfers are the reason this exists, so they are the only thing here in
 *  code blocks — one block each, so a phone can copy a line with a single tap
 *  rather than a text selection, and so an amount can never run into the name
 *  beside it. Everything above them is a reading of the session, and reads as
 *  prose.
 */
object LootSplitEmbeds {

  /** The same green the respawn cards use for a spawn that is free — this is a
   *  result rather than a warning, and the bot's other "here is your answer"
   *  embeds are this colour. */
  val SplitColor: Int = 3066993

  /** The client's own bullet for a party member. */
  private val Bullet = "‣"

  private val Ellipsis = "…"

  /** Enough for the overflow marker at any party size the game allows. */
  private val MarkerRoom = 24

  /** Room held back for [[leftOut]] — a field name plus a line of names, bounded
   *  by what [[fit]] will let through. */
  private val LeftOutRoom = 10 + MessageEmbed.VALUE_MAX_LENGTH

  /** `goldEmoji` is passed in rather than read from Config, which cannot
   *  initialise without a populated environment and so cannot be reached from a
   *  test — the same reason [[RespawnEmbeds]] takes its emoji as an argument. */
  def session(hunt: HuntSession, goldEmoji: String): MessageEmbed = {
    val embed = new EmbedBuilder()
      .setColor(SplitColor)
      .setTitle(title(hunt))
      .setDescription(headline(hunt, goldEmoji).mkString("\n"))

    // Two columns rather than two stacked lists: they are the same shape and the
    // same length, and side by side they can be read against each other. Dropped
    // entirely when nobody dealt any, which is a fishing trip rather than a bug.
    shareField(embed, "Damage", hunt.damageShares)
    shareField(embed, "Healing", hunt.healingShares)

    transferFields(embed, hunt)

    footer(hunt).foreach(text => embed.setFooter(text))
    embed.build()
  }

  private def title(hunt: HuntSession): String =
    hunt.members.size match {
      case 0 => "Hunt Session"
      case 1 => "Party Hunt Session – 1 member"
      case n => s"Party Hunt Session – $n members"
    }

  /** The three numbers the split turns on. Loot per hour is missing only when the
   *  header's timestamps didn't parse; the individual balance is missing when
   *  there is nobody to split with. */
  private def headline(hunt: HuntSession, goldEmoji: String): List[String] =
    List(
      Some(s"**Balance:** ${gold(hunt.balance, goldEmoji)}"),
      if (hunt.members.size >= 2) Some(s"**Individual balance:** ${gold(hunt.individualBalance, goldEmoji)}") else None,
      hunt.lootPerHour.map(rate => s"**Loot per hour:** ${gold(rate, goldEmoji)}")
    ).flatten

  private def shareField(embed: EmbedBuilder, name: String, shares: List[(HuntMember, Double)]): Unit =
    if (shares.nonEmpty) {
      val lines = shares.map { case (member, share) =>
        s"$Bullet ${member.name} (${percent(share)}%)"
      }
      embed.addField(name, fit(lines), true)
    }

  /** A field per person who has to send something, in the order the client listed
   *  the party.
   *
   *  A field each rather than one list of every transfer: the person reading is
   *  looking for their own name and then typing what is under it, and a shared
   *  list makes them filter first. Almost always there is exactly one — whoever
   *  was holding the loot. */
  private def transferFields(embed: EmbedBuilder, hunt: HuntSession): Unit = {
    val byPayer = hunt.transfersByPayer
    if (byPayer.isEmpty) {
      if (hunt.members.size >= 2)
        embed.addField("Transfers", "Nobody owes anybody — the party is already square.", false)
    } else {
      // Two limits bind here, and a field over either of them is not a truncated
      // embed but a rejected one — the whole reply fails, not the last column. So
      // both are checked as the fields go on rather than assumed away: Discord takes
      // 25 fields, and 6,000 characters across the lot. In practice a session has
      // one payer, and neither is anywhere near.
      // A slot is held back for the notice below when the payers cannot all have
      // one of their own. Without it the last payer takes the twenty-fifth field
      // and the notice saying the rest were dropped has nowhere to go — which is
      // the one case it was needed.
      val fieldCap =
        if (byPayer.size <= MessageEmbed.MAX_FIELD_AMOUNT - embed.getFields.size) MessageEmbed.MAX_FIELD_AMOUNT
        else MessageEmbed.MAX_FIELD_AMOUNT - 1
      val skipped = List.newBuilder[String]
      byPayer.foreach { case (payer, transfers) =>
        val name = s"Transfers for $payer"
        val value = fit(transfers.map(block))
        val room = embed.getFields.size < fieldCap &&
          embed.length() + name.length + value.length + LeftOutRoom <= MessageEmbed.EMBED_MAX_LENGTH_BOT
        if (room) embed.addField(name, value, false) else skipped += payer
      }
      // Never silently: somebody whose transfers were dropped has to know to ask
      // for them, rather than reading the party as square.
      val left = skipped.result()
      if (left.nonEmpty && embed.getFields.size < MessageEmbed.MAX_FIELD_AMOUNT)
        embed.addField("Transfers", leftOut(left), false)
    }
  }

  /** Named, so whoever is missing can see it is them. Its own length is reserved
   *  before any field is kept, so it always fits. */
  private def leftOut(payers: List[String]): String =
    fit(List(s"Too many to show. Still to send: ${payers.mkString(", ")}."))

  /** One transfer, in its own code block so it can be copied on its own. */
  private def block(transfer: HuntTransfer): String = s"```\n${transfer.command}\n```"

  /** "02:17h hunt on 2026-09-01T21:12" — when it started and how long it ran, which
   *  is what tells two splits from the same evening apart. */
  private def footer(hunt: HuntSession): Option[String] = {
    val length = Option(hunt.sessionLabel).filter(_.nonEmpty).map(label => s"$label hunt")
    val started = hunt.from.map(start => s"on ${start.truncatedTo(ChronoUnit.MINUTES)}")
    val parts = List(length, started).flatten
    if (parts.isEmpty) None else Some(parts.mkString(" "))
  }

  private def gold(amount: Long, goldEmoji: String): String = s"${number(amount)} $goldEmoji"

  /** Grouped in threes with a comma, like the client writes them and like the paste
   *  they came from — explicitly US-formatted, since the bot's host locale is not
   *  something the reader chose. */
  private def number(amount: Long): String = String.format(Locale.US, "%,d", Long.box(amount))

  private def percent(share: Double): String = String.format(Locale.US, "%.2f", Double.box(share))

  /** As many whole lines as Discord will take in one field, and a count of what
   *  didn't fit. Cutting mid-line would leave a transfer command that looks
   *  complete and isn't. */
  private def fit(lines: List[String]): String = {
    val whole = lines.mkString("\n")
    if (whole.length <= MessageEmbed.VALUE_MAX_LENGTH) whole
    else {
      // Room for the marker is reserved before any line is kept, so the marker
      // itself can never be what pushes the field over.
      val kept = lines.foldLeft((List.empty[String], 0)) { case ((taken, used), line) =>
        val length = used + line.length + 1
        if (length <= MessageEmbed.VALUE_MAX_LENGTH - MarkerRoom) (taken :+ line, length) else (taken, used)
      }._1
      (kept :+ s"*${Ellipsis}and ${lines.size - kept.size} more*").mkString("\n")
    }
  }
}
