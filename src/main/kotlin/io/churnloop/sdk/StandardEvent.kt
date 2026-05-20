package io.churnloop.sdk

/**
 * Canonical event-name vocabulary. Shipping a fixed set of names
 * lets us build cross-tenant rollups, ship-with-semantics playbook
 * templates, and (eventually) train cross-tenant models without
 * per-customer schema mapping.
 *
 * Customers MAY pass arbitrary strings as the `event` field; they
 * are not constrained to this enum. Using a standard name enables
 * the cross-tenant features, that's the only difference.
 *
 * **Versioning contract:**
 * - Adding entries → minor version bump.
 * - Renaming or removing an entry would silently break customers'
 *   dashboards; we do NOT do that. If a name needs to evolve, add
 *   the new entry and keep the old.
 *
 * Values match the JS + Swift SDKs exactly so events from web,
 * Android, and iOS land under the same names server-side.
 */
public enum class StandardEvent(public val eventName: String) {
    // User lifecycle
    UserSignedUp("User Signed Up"),
    UserSignedIn("User Signed In"),
    UserSignedOut("User Signed Out"),
    UserDeleted("User Deleted"),

    // Onboarding
    OnboardingStarted("Onboarding Started"),
    OnboardingCompleted("Onboarding Completed"),

    // Subscription / billing
    SubscriptionStarted("Subscription Started"),
    SubscriptionUpgraded("Subscription Upgraded"),
    SubscriptionDowngraded("Subscription Downgraded"),
    SubscriptionCanceled("Subscription Canceled"),
    PaymentFailed("Payment Failed"),

    // Team / workspace
    Invited("Invited"),
    InviteAccepted("Invite Accepted"),

    // Generic engagement (use sparingly; prefer specific names)
    FeatureUsed("Feature Used"),
    Searched("Searched"),
}
