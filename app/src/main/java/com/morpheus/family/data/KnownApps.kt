package com.morpheus.family.data

/** A pickable app: friendly name + package id. */
data class KnownApp(val label: String, val packageName: String)

/**
 * Curated list of commonly-restricted apps so the parent can pick by name
 * without knowing package ids. The parent can also add any package manually.
 */
object KnownApps {
    val ALL = listOf(
        KnownApp("YouTube", "com.google.android.youtube"),
        KnownApp("TikTok", "com.zhiliaoapp.musically"),
        KnownApp("Instagram", "com.instagram.android"),
        KnownApp("WhatsApp", "com.whatsapp"),
        KnownApp("Facebook", "com.facebook.katana"),
        KnownApp("Messenger", "com.facebook.orca"),
        KnownApp("Snapchat", "com.snapchat.android"),
        KnownApp("X (Twitter)", "com.twitter.android"),
        KnownApp("Kwai", "com.kwai.video"),
        KnownApp("Telegram", "org.telegram.messenger"),
        KnownApp("Discord", "com.discord"),
        KnownApp("Twitch", "tv.twitch.android.app"),
        KnownApp("Netflix", "com.netflix.mediaclient"),
        KnownApp("Roblox", "com.roblox.client"),
        KnownApp("Minecraft", "com.mojang.minecraftpe"),
        KnownApp("Free Fire", "com.dts.freefireth"),
        KnownApp("Chrome", "com.android.chrome"),
    )

    fun labelFor(packageName: String): String =
        ALL.firstOrNull { it.packageName == packageName }?.label ?: packageName
}
