package app.skerry.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AboutLinksTest {
    @Test
    fun whatsNewPointsAtTheRunningVersionTag() {
        assertEquals(
            "https://github.com/anthony11122/SkerrySSH/releases/tag/v0.1.1",
            AboutLinks.whatsNew("0.1.1"),
        )
    }
}
