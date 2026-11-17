package com.andy8401.mygo.car

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * UI smoke test: the car layout must reach a usable state on a real device,
 * where Compose has actual density/insets to lay out against.
 *
 * The rail is asserted instead of the shelves because the shelves depend on the
 * network; the rail renders from bundled plugin metadata and is always there.
 */
@RunWith(AndroidJUnit4::class)
class CarUiInstrumentedTest {

    /**
     * MainActivity asks for POST_NOTIFICATIONS on first launch (the media
     * notification needs it). Gradle reinstalls the app before every run, so
     * without this the synthetic grant is gone and the system permission
     * dialog covers the activity — Compose then reports "No compose
     * hierarchies found" instead of a layout failure.
     */
    @get:Rule(order = 0)
    val notificationPermission: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { base, _ -> base }
        }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun leftRailRenders() {
        rule.waitUntil(timeoutMillis = 20_000) {
            rule.onAllNodesWithText("首页").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("首页").assertExists()
        rule.onNodeWithText("搜索").assertExists()
        rule.onNodeWithText("本地").assertExists()
        rule.onNodeWithText("设置").assertExists()
    }

    @Test
    fun settingsShowsForkProvenance() {        rule.waitUntil(timeoutMillis = 20_000) {
            rule.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("设置").performClick()

        // AGPL-3.0: the fork must say who it is and where the source lives.
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("关于").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("关于").assertExists()
    }

    /**
     * Lyrics are optional: the switch has to be reachable from 设置 and must
     * default to on, because a source without lyrics already answers with its
     * own explanation.
     *
     * Read-only on purpose — the switch persists into the bridge, so flipping
     * it here would leak into every later test of the same run.
     */
    @Test
    fun settingsOffersLyricsSwitchOnByDefault() {
        rule.waitUntil(timeoutMillis = 20_000) {
            rule.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("设置").performClick()

        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("显示歌词").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("显示歌词").assertExists()
        rule.onNodeWithText("开启").assertExists()
    }

    /**
     * The plugin manager is the only place install / configure / uninstall /
     * update live, so it must be reachable and must list the bundled plugins
     * even before any network call happens.
     */
    @Test
    fun pluginManagerReachableAndListsBundledPlugins() {
        rule.waitUntil(timeoutMillis = 20_000) {
            rule.onAllNodesWithText("插件").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("插件").performClick()

        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("插件管理").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("插件管理").assertExists()
        // The install section sits at the end of the LazyColumn and is not
        // composed until scrolled to, so assert the always-visible header and
        // the first bundled row instead of a below-the-fold node.
        rule.onNodeWithText("已安装", substring = true).assertExists()

        // cnradio ships in assets, so its row exists without any install step.
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("cnradio", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // One 配置 button per plugin that declares settings, so assert "at
        // least one" rather than "exactly one".
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("配置").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
