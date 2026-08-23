package com.hilight.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandTest {

    @Test
    fun `reset and start are separate and root launch is detached and owned`() {
        val reset = RootCommand.reset()
        val start = RootCommand.start("/storage/emulated/0/Android/data/com.hilight.studio/files/hilight")

        assertTrue(reset.contains("pkill -f"))
        assertFalse(reset.contains("app_process"))
        assertTrue(start.contains("nohup app_process"))
        assertTrue(start.contains("--owner root"))
        assertTrue(start.contains("& echo \$!"))
        assertFalse(start.contains("pkill"))
    }

    @Test
    fun `bridge path is safely single quoted for the phone shell`() {
        val start = RootCommand.start("/data/a user's/light")

        assertTrue(start.contains("'/data/a user'\\''s/light'"))
    }

    @Test
    fun `stop validates pid command line before signalling`() {
        val stop = RootCommand.stop(4321)

        assertTrue(stop.contains("/proc/4321/cmdline"))
        assertTrue(stop.contains("com.hilight.core.AdbHelper --owner root"))
        assertTrue(stop.contains("kill 4321"))
    }
}
