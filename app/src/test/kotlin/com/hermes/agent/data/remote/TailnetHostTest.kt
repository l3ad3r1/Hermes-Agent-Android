package com.hermes.agent.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetHostTest {

    @Test
    fun `MagicDNS names and the CGNAT range are tailnet hosts`() {
        assertTrue(TailnetNode.isTailnetHost("mfi-nb-0102.tail7dc9bd.ts.net"))
        assertTrue(TailnetNode.isTailnetHost("MFI-NB-0102.TAIL7DC9BD.TS.NET."))
        assertTrue(TailnetNode.isTailnetHost("100.88.103.5"))
        assertTrue(TailnetNode.isTailnetHost("100.64.0.1"))
        assertTrue(TailnetNode.isTailnetHost("100.127.255.254"))
    }

    @Test
    fun `everything else connects directly`() {
        assertFalse(TailnetNode.isTailnetHost("api.openai.com"))
        assertFalse(TailnetNode.isTailnetHost("ts.net.evil.example"))
        assertFalse(TailnetNode.isTailnetHost("notts.net"))
        assertFalse(TailnetNode.isTailnetHost("100.63.255.255"))
        assertFalse(TailnetNode.isTailnetHost("100.128.0.1"))
        assertFalse(TailnetNode.isTailnetHost("192.168.0.117"))
        assertFalse(TailnetNode.isTailnetHost("127.0.0.1"))
        assertFalse(TailnetNode.isTailnetHost(null))
    }
}
