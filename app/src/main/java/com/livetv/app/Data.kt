package com.livetv.app

data class Channel(val name: String, val url: String)
data class Item(val label: String, val key: String)

object Data {
    val countries: List<Item> = (
        "Pakistan|pk,India|in,Bangladesh|bd,Afghanistan|af,Iran|ir,Saudi Arabia|sa," +
        "United Arab Emirates|ae,Qatar|qa,Kuwait|kw,Oman|om,Bahrain|bh,Turkey|tr," +
        "Egypt|eg,Algeria|dz,Morocco|ma,Iraq|iq,Jordan|jo,Lebanon|lb,Syria|sy," +
        "Palestine|ps,United Kingdom|uk,United States|us,Canada|ca,Australia|au," +
        "Germany|de,France|fr,Italy|it,Spain|es,Russia|ru,China|cn,Japan|jp," +
        "South Korea|kr,Indonesia|id,Malaysia|my,Singapore|sg,Brazil|br,Mexico|mx," +
        "Argentina|ar,South Africa|za,Nigeria|ng,Kenya|ke,Sri Lanka|lk,Nepal|np"
        ).split(",").map {
        val p = it.split("|")
        Item(p[0], p[1])
    }

    val categories: List<Item> = listOf(
        "animation", "auto", "business", "classic", "comedy", "cooking", "culture",
        "documentary", "education", "entertainment", "family", "general", "kids",
        "legislative", "lifestyle", "movies", "music", "news", "outdoor", "relax",
        "religious", "science", "series", "shop", "sports", "travel", "weather"
    ).map { Item(it.replaceFirstChar { c -> c.uppercase() }, it) }
}
