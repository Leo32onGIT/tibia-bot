package com.tibiabot.tibiadata.response

case class BoostableBossList(
    featured: Boolean,
    image_url: String,
    name: String
)
case class BoostableBosses(
    boostable_boss_list: List[BoostableBossList],
    boosted: BoostableBossList,
)
case class BoostedResponse(boostable_bosses: BoostableBosses, information: Information)
