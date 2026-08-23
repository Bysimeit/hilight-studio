package com.hilight.studio

/** Phone-shell commands used by the direct-root backend. */
object RootCommand {
    fun reset(): String = "pkill -f 'com.hilight.(core.AdbHelper|studio:hilight)'"

    fun start(bridgeDir: String): String =
        "CLASSPATH=${'$'}(pm path com.hilight.studio | head -1 | cut -d: -f2) " +
            "nohup app_process / com.hilight.core.AdbHelper --owner root --dir ${quote(bridgeDir)} " +
            "> /data/local/tmp/hilight-root.log 2>&1 & echo ${'$'}!"

    fun stop(pid: Int): String {
        require(pid > 0) { "pid must be positive" }
        return "if tr '\\000' ' ' < /proc/$pid/cmdline | " +
            "grep -Fq 'com.hilight.core.AdbHelper --owner root'; then kill $pid; fi"
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
