package com.hermes.agent.plugin.tasker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowPackageManager

/**
 * The gate that replaced the signature permission on the Tasker fire receiver.
 *
 * The old guard was `protectionLevel="signature"`, which no third-party
 * automation app could ever hold — the integration could not work. Access is
 * now a capability token minted when the user approves a host, so these tests
 * are about the one property that matters: a fire without a live, correctly
 * signed approval must not run.
 */
@RunWith(RobolectricTestRunner::class)
class TaskerHostAuthorityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val shadowPm: ShadowPackageManager
        get() = Shadows.shadowOf(context.packageManager)

    private fun install(packageName: String, signature: String) {
        shadowPm.installPackage(
            android.content.pm.PackageInfo().apply {
                this.packageName = packageName
                @Suppress("DEPRECATION")
                this.signatures = arrayOf(android.content.pm.Signature(signature.toByteArray()))
                this.applicationInfo = android.content.pm.ApplicationInfo().apply {
                    this.packageName = packageName
                }
            },
        )
    }

    private fun authority() = TaskerHostAuthority(context)

    @Test
    fun `an unapproved host has no token`() {
        install("net.dinglisch.android.taskerm", "tasker-cert")
        val authority = authority()

        assertFalse(authority.isApproved("net.dinglisch.android.taskerm"))
        assertNull(authority.tokenFor("net.dinglisch.android.taskerm"))
    }

    @Test
    fun `approving mints a token that resolves back to the host`() {
        install("net.dinglisch.android.taskerm", "tasker-cert")
        val authority = authority()

        val token = authority.approve("net.dinglisch.android.taskerm")
        assertTrue(authority.isApproved("net.dinglisch.android.taskerm"))
        assertEquals("net.dinglisch.android.taskerm", authority.hostForToken(token))
    }

    @Test
    fun `a fire with no token or a made-up token is refused`() {
        install("net.dinglisch.android.taskerm", "tasker-cert")
        val authority = authority()
        authority.approve("net.dinglisch.android.taskerm")

        assertNull(authority.hostForToken(null))
        assertNull(authority.hostForToken(""))
        assertNull(authority.hostForToken("   "))
        assertNull(authority.hostForToken("deadbeef".repeat(8)))
    }

    @Test
    fun `revoking invalidates the token immediately`() {
        install("net.dinglisch.android.taskerm", "tasker-cert")
        val authority = authority()
        val token = authority.approve("net.dinglisch.android.taskerm")

        authority.revoke("net.dinglisch.android.taskerm")

        assertFalse(authority.isApproved("net.dinglisch.android.taskerm"))
        assertNull(authority.hostForToken(token))
    }

    @Test
    fun `an approval does not survive the package being resigned`() {
        // The realistic package-name-squatting attack: the approved app is
        // replaced by a build from someone else's key. The recorded certificate
        // no longer matches, so the token stops resolving.
        install("net.dinglisch.android.taskerm", "tasker-cert")
        val authority = authority()
        val token = authority.approve("net.dinglisch.android.taskerm")
        assertEquals("net.dinglisch.android.taskerm", authority.hostForToken(token))

        install("net.dinglisch.android.taskerm", "attacker-cert")

        assertFalse(authority().isApproved("net.dinglisch.android.taskerm"))
        assertNull(authority().hostForToken(token))
    }

    @Test
    fun `one host's token does not authorize another host`() {
        install("net.dinglisch.android.taskerm", "tasker-cert")
        install("com.example.other", "other-cert")
        val authority = authority()

        val taskerToken = authority.approve("net.dinglisch.android.taskerm")
        val otherToken = authority.approve("com.example.other")

        assertNotEquals(taskerToken, otherToken)
        assertEquals("net.dinglisch.android.taskerm", authority.hostForToken(taskerToken))
        assertEquals("com.example.other", authority.hostForToken(otherToken))
    }

    @Test
    fun `re-approving rotates the token so old configurations stop firing`() {
        install("net.dinglisch.android.taskerm", "tasker-cert")
        val authority = authority()

        val first = authority.approve("net.dinglisch.android.taskerm")
        val second = authority.approve("net.dinglisch.android.taskerm")

        assertNotEquals(first, second)
        assertNull(authority.hostForToken(first))
        assertEquals("net.dinglisch.android.taskerm", authority.hostForToken(second))
    }

    @Test
    fun `an approval survives across instances`() {
        install("net.dinglisch.android.taskerm", "tasker-cert")
        val token = authority().approve("net.dinglisch.android.taskerm")

        // A fire arrives in a fresh process, so the receiver builds its own
        // authority; the approval has to be readable there.
        assertEquals("net.dinglisch.android.taskerm", authority().hostForToken(token))
    }

    @Test
    fun `approving an app that is not installed mints nothing`() {
        assertNull(authority().approve("com.example.not.installed"))
    }

    @Test
    fun `approved hosts are listed for review`() {
        install("net.dinglisch.android.taskerm", "tasker-cert")
        val authority = authority()
        authority.approve("net.dinglisch.android.taskerm")

        val hosts = authority.approvedHosts()
        assertEquals(1, hosts.size)
        assertEquals("net.dinglisch.android.taskerm", hosts[0].packageName)
        assertTrue(hosts[0].signingSha256.isNotBlank())
    }
}
