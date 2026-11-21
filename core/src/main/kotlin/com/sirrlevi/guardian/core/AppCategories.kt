package com.sirrlevi.guardian.core

/** Friendly category names plus package-name hints for popular apps the system leaves uncategorised. */
object AppCategories {
    val labels: Map<String, String> = linkedMapOf(
        "social" to "Social media",
        "messaging" to "Messaging",
        "video" to "Video & streaming",
        "games" to "Games",
        "music" to "Music & audio",
        "browser" to "Browsers",
        "education" to "Education",
        "productivity" to "Productivity",
        "photo" to "Photo & camera",
        "news" to "News & reading",
        "maps" to "Maps & travel",
        "shopping" to "Shopping",
        "tools" to "Tools & system",
        "other" to "Other"
    )

    fun label(key: String): String = labels[key] ?: "Other"

    fun hintFor(packageName: String): String? {
        val p = packageName.lowercase()
        fun any(vararg prefixes: String) = prefixes.any { p.startsWith(it) }
        return when {
            any("com.instagram.", "com.facebook.katana", "com.facebook.lite", "com.twitter.", "com.snapchat.",
                "com.zhiliaoapp.", "com.ss.android.ugc", "com.reddit.", "com.pinterest", "com.linkedin.",
                "com.tumblr", "in.mohalla.", "com.next.innovation.takatak") -> "social"
            any("com.whatsapp", "org.telegram.", "org.thoughtcrime.", "com.discord", "com.facebook.orca",
                "com.google.android.apps.messaging", "com.android.mms", "com.viber.", "jp.naver.line",
                "com.skype.", "com.google.android.apps.tachyon") -> "messaging"
            any("com.google.android.youtube", "com.netflix.", "in.startv.hotstar", "com.amazon.avod", "com.mxtech.",
                "com.jio.media.ondemand", "tv.twitch.", "com.disney.", "com.sonyliv", "com.graymatrix.did",
                "com.vimeo.", "com.google.android.apps.youtube.kids") -> "video"
            any("com.spotify.", "com.google.android.apps.youtube.music", "com.gaana", "com.jio.media.jiobeats",
                "com.apple.android.music", "com.bsbportal.music", "com.soundcloud.") -> "music"
            any("com.android.chrome", "org.mozilla.", "com.brave.", "com.opera.", "com.microsoft.emmx",
                "com.sec.android.app.sbrowser", "com.android.browser", "com.duckduckgo.", "com.kiwibrowser.",
                "com.sirrlevi.guardian.child") -> "browser"
            any("com.supercell.", "com.roblox.", "com.mojang.", "com.activision.", "com.tencent.ig", "com.pubg.",
                "com.dts.freefire", "com.garena.", "com.king.", "com.miHoYo.", "com.kiloo.", "com.mobile.legends",
                "com.epicgames.", "com.ea.", "com.rovio.", "com.nianticlabs.", "com.innersloth.", "com.gameloft.",
                "com.nexon.", "com.ludo.", "com.moonactive.", "com.playrix.") -> "games"
            any("com.amazon.mshop", "in.amazon.", "com.flipkart.", "com.myntra.", "com.meesho.") -> "shopping"
            any("com.duolingo", "org.khanacademy.", "com.byjus.", "com.unacademy.", "com.google.android.apps.classroom") -> "education"
            any("com.google.android.googlecamera", "com.android.camera", "com.sec.android.app.camera",
                "com.google.android.apps.photos") -> "photo"
            any("com.google.android.apps.maps", "com.ubercab", "com.olacabs.") -> "maps"
            else -> null
        }
    }
}
