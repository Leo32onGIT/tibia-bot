package com.tibiabot.tibiadata.response

case class CreatureList(
    featured: Boolean,
    image_url: String,
    name: String
)
case class Creatures(
    boosted: BoostableBossList,
    creature_list: List[CreatureList]
)
case class CreatureResponse(creatures: Creatures, information: Information)
