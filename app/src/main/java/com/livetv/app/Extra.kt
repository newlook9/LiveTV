package com.livetv.app

class Playlist(var name: String, val items: MutableList<Channel>)

object RData {
    val tags: List<Item> = listOf(
        "news", "pop", "rock", "music", "talk", "religion", "islamic", "quran",
        "classical", "jazz", "hits", "dance", "oldies", "sports", "hiphop",
        "electronic", "folk", "bollywood", "kids"
    ).map { Item(it.replaceFirstChar { c -> c.uppercase() }, it) }
}
