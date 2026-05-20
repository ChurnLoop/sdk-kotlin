package io.churnloop.sdk

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StandardEventTest {
    /**
     * Lock the core Tier-1 vocabulary so an accidental rename or
     * removal in a future refactor fails CI. These names are part
     * of the SDK's SemVer contract and MUST match the JS + Swift
     * SDKs byte-for-byte — cross-platform events from web,
     * Android, and iOS need to land under the same names server-
     * side.
     */
    @Test
    fun `canonical names match the JS and Swift SDKs`() {
        assertEquals("User Signed Up",         StandardEvent.UserSignedUp.eventName)
        assertEquals("User Signed In",         StandardEvent.UserSignedIn.eventName)
        assertEquals("User Signed Out",        StandardEvent.UserSignedOut.eventName)
        assertEquals("User Deleted",           StandardEvent.UserDeleted.eventName)
        assertEquals("Onboarding Started",     StandardEvent.OnboardingStarted.eventName)
        assertEquals("Onboarding Completed",   StandardEvent.OnboardingCompleted.eventName)
        assertEquals("Subscription Started",   StandardEvent.SubscriptionStarted.eventName)
        assertEquals("Subscription Upgraded",  StandardEvent.SubscriptionUpgraded.eventName)
        assertEquals("Subscription Downgraded", StandardEvent.SubscriptionDowngraded.eventName)
        assertEquals("Subscription Canceled",  StandardEvent.SubscriptionCanceled.eventName)
        assertEquals("Payment Failed",         StandardEvent.PaymentFailed.eventName)
        assertEquals("Invited",                StandardEvent.Invited.eventName)
        assertEquals("Invite Accepted",        StandardEvent.InviteAccepted.eventName)
        assertEquals("Feature Used",           StandardEvent.FeatureUsed.eventName)
        assertEquals("Searched",               StandardEvent.Searched.eventName)
    }

    /**
     * Naming convention: Title Case with spaces. Matches the
     * Segment Spec convention.
     */
    @Test
    fun `all values follow Title Case convention`() {
        val pattern = Regex("^[A-Z][a-z]+(?: [A-Z][a-z]+)*$")
        for (event in StandardEvent.values()) {
            assertNotNull(
                pattern.matchEntire(event.eventName),
                "${event.eventName} violates Title Case convention",
            )
        }
    }
}
