package io.churnloop.sdk

import java.util.Locale

/**
 * Auto-attached `$context` block on every event. Universal SDK
 * pattern (Segment, PostHog, Mixpanel all do this) — costs the
 * customer nothing and gives ops a free pivot ("how many events
 * from SDK 0.1.x?", "did this bug only affect Android 14?").
 *
 * Built fresh per event so things like the current locale or
 * (eventually) the active activity name reflect runtime state.
 * Cost is negligible.
 *
 * Note: Android-specific fields (device model, OS version) are NOT
 * captured here because that would require an Android Gradle Plugin
 * dependency — see [JvmContextBuilder]. Android consumers can
 * subclass or wrap the SDK to add `Build.MODEL` etc. as additional
 * properties; we'll ship that helper in a separate
 * `@churnloop/sdk-android` package once we know what consumers want.
 */
public object JvmContextBuilder {
    /** SDK identity. Bump in lockstep with the published version. */
    public const val LIBRARY_NAME: String = "churnloop-sdk-kotlin"
    public const val LIBRARY_VERSION: String = "0.2.0"

    /**
     * Build the context object. Returned as a [JsonValue.Obj] so it
     * plugs into the wire payload directly.
     */
    public fun build(): JsonValue.Obj {
        val javaVersion = System.getProperty("java.version") ?: "unknown"
        val osName = System.getProperty("os.name") ?: "unknown"
        val osVersion = System.getProperty("os.version") ?: "unknown"
        val locale = Locale.getDefault().toLanguageTag()

        return JsonValue.Obj(
            linkedMapOf(
                "library" to JsonValue.Obj(
                    linkedMapOf(
                        "name" to JsonValue.Str(LIBRARY_NAME),
                        "version" to JsonValue.Str(LIBRARY_VERSION),
                    )
                ),
                "runtime" to JsonValue.Obj(
                    linkedMapOf(
                        "name" to JsonValue.Str("jvm"),
                        "version" to JsonValue.Str(javaVersion),
                    )
                ),
                "os" to JsonValue.Obj(
                    linkedMapOf(
                        "name" to JsonValue.Str(osName),
                        "version" to JsonValue.Str(osVersion),
                    )
                ),
                "locale" to JsonValue.Str(locale),
            )
        )
    }
}
