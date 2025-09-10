package com.tibiabot.tibiadata.response

case class BoostedCreature(
    name: String,
    race: String,
    image_url: String,
    featured: Boolean
)

case class CreatureListItem(
    name: String,
    race: String,
    image_url: String,
    featured: Boolean
)

case class Creatures(
    boosted: BoostedCreature,
    creature_list: List[CreatureListItem]
)

case class CreaturesResponse(creatures: Creatures, information: Information)