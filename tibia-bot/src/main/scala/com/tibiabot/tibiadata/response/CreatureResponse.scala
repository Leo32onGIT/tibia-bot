package com.tibiabot.tibiadata.response

case class CreatureList(
    featured: Boolean,
    image_url: String,
    name: String
)
case class CreatureData(
    boosted: BoostableBossList,
    creature_list: List[CreatureList]
)
case class CreatureResponse(creatures: CreatureData, information: Information)
