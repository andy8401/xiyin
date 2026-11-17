package com.andy8401.mygo.car

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andy8401.mygo.car.bridge.GoBridge
import facade.Facade
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the Go bridge on a real Android runtime.
 *
 * Unit tests in `android-bridge/facade` run on the host and cannot cover the
 * parts that only exist on a device: the gomobile JNI library loading, bundled
 * plugins being unpacked from assets into the app's private directory, and the
 * moejs plugin runtime executing there. This is the stage-G regression net.
 *
 * Nothing here touches the network on purpose: a CI run must not depend on
 * radio.cn being reachable. Only the bundled demo plugin is played, which
 * resolves to a local file:// inside the app's data dir.
 */
@RunWith(AndroidJUnit4::class)
class BridgeInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // Idempotent: MyGoCarApp may already have initialised the bridge.
        GoBridge.init(context)
    }

    /** The JNI library loads and all three bundled plugins compile and register. */
    @Test
    fun loadsAllBundledPlugins() {
        val count = GoBridge.init(context)
        // Not an equality check: `filesDir/plugins` survives reinstalls, so a
        // device that ever installed a plugin from a zip or a repo keeps it and
        // init() legitimately reports more than the three bundled ones.
        assertTrue("expected at least the 3 bundled plugins, got $count", count >= 3)

        val ids = pluginIds()
        assertTrue("cnradio missing, got $ids", ids.contains("cnradio"))
        assertTrue("demo missing, got $ids", ids.contains("demo"))
        assertTrue("ximalaya-web missing, got $ids", ids.contains("ximalaya-web"))
    }

    /**
     * The facade reports host capability 0.1.25. ximalaya-web declares
     * minAppVersion 0.1.20 and refuses to load below it, so this is a real
     * compatibility gate rather than a cosmetic version string.
     */
    @Test
    fun reportsHostCapabilityVersion() {
        assertEquals("0.1.25", Facade.version())
    }

    /**
     * The login button is driven by this capability bit, not by a hardcoded
     * source list. A source without a signIn export (cnradio) must not offer a
     * button that can never open a page.
     */
    @Test
    fun pluginListCarriesSignInCapability() {
        val list = JSONArray(Facade.listPluginsJSON())
        val byId = (0 until list.length()).associate { i ->
            val o = list.getJSONObject(i)
            o.optString("id") to o
        }
        assertTrue("demo missing, got ${byId.keys}", byId.containsKey("demo"))
        assertTrue("demo must expose signIn", byId.getValue("demo").optBoolean("signIn"))
        assertTrue("ximalaya-web must expose signIn", byId.getValue("ximalaya-web").optBoolean("signIn"))
        assertTrue("cnradio has no signIn export", !byId.getValue("cnradio").optBoolean("signIn"))
    }

    /** A source without an account export answers nobody rather than throwing. */
    @Test
    fun accountWithoutSignInReportsSignedOut() {
        val account = JSONObject(Facade.accountJSON("cnradio"))
        assertTrue("unexpected signedIn: $account", !account.optBoolean("signedIn", false))
        assertTrue("unexpected account name: $account", account.optString("name").isBlank())
    }

    /**
     * Android has no host.spawn, so cnradio's live-stream bridge (Python or
     * ffmpeg) can never start. The facade must have switched it to `direct`,
     * which hands the HLS URL to Media3 instead.
     */
    @Test
    fun cnradioBridgeDefaultsToDirect() {
        val settings = JSONArray(Facade.settingsJSON("cnradio"))
        var found: String? = null
        for (i in 0 until settings.length()) {
            val s = settings.getJSONObject(i)
            if (s.optString("key") == "bridge") found = s.optString("value")
        }
        assertEquals("cnradio bridge setting", "direct", found)
    }

    /**
     * Playing the bundled demo track walks the whole Go path: queue -> plugin
     * stream() -> MediaOutput.load -> state. The file is local, so this passes
     * offline.
     */
    @Test
    fun demoPlaybackReachesPlayingState() {
        Facade.playQueueJSON("demo", demoTracks(), 0L)

        val state = JSONObject(Facade.stateJSON())
        assertTrue("expected playing after playQueueJSON, state=$state", state.optBoolean("playing"))

        val queue = JSONObject(Facade.queueJSON())
        assertEquals("queue plugin", "demo", queue.optString("pluginId"))
        assertEquals("queue length", 2, queue.optJSONArray("tracks")?.length() ?: 0)

        // Leave the device quiet for the next test.
        assertTrue("toggle should pause", !Facade.togglePlay())
    }

    /**
     * The lyrics pane is fed entirely by the facade: Kotlin never parses LRC.
     * `demo` exports two shapes — t1 is plain LRC whose line ends the Go side
     * has to fill in, t2 is already-timed lines with a translation — and both
     * must come back as one normalised line array.
     */
    @Test
    fun lyricsComeThroughTheBridge() {
        val timed = JSONObject(Facade.lyricsJSON("demo", "t2"))
        assertTrue("expected supported lyrics, got $timed", timed.optBoolean("supported"))
        val lines = timed.optJSONArray("lines") ?: JSONArray()
        assertTrue("expected lyric lines, got $timed", lines.length() > 0)
        val first = lines.getJSONObject(0)
        assertEquals("first line start", 1000L, first.optLong("start"))
        assertTrue("first line text", first.optString("text").isNotBlank())

        val plain = JSONObject(Facade.lyricsJSON("demo", "t1"))
        val plainLines = plain.optJSONArray("lines") ?: JSONArray()
        assertTrue("expected two LRC lines, got $plain", plainLines.length() >= 2)
        val head = plainLines.getJSONObject(0)
        assertEquals("LRC line start", 1000L, head.optLong("start"))
        // The LRC itself carries no [start,end] pairs; an unfilled end would
        // make the highlighter jump on every poll.
        assertTrue("end must be filled in, got $head", head.optLong("end") > head.optLong("start"))
    }

    /**
     * A source without the lyrics export is not an error: it answers with
     * supported=false plus a reason so the pane can say why it is empty
     * instead of the player falling over.
     */
    @Test
    fun lyricsUnsupportedSourceIsNotAnError() {
        val out = JSONObject(Facade.lyricsJSON("cnradio", "1"))
        assertTrue("cnradio must not claim lyrics, got $out", !out.optBoolean("supported"))
        assertTrue("expected a reason, got $out", out.optString("reason").isNotBlank())
        assertEquals("no lines expected", 0, out.optJSONArray("lines")?.length() ?: 0)
    }

    /**
     * Queue rows jump with playAt(), which moves the index without rebuilding
     * the queue. A live station queue is a rolling window, so the same call
     * must be rejected there (covered in the Go unit tests).
     */
    @Test
    fun playAtJumpsWithinQueue() {
        Facade.playQueueJSON("demo", demoTracks(), 0L)

        Facade.playAt(1L)
        val queue = JSONObject(Facade.queueJSON())
        assertEquals("jumped index", 1, queue.optInt("index"))
        assertEquals("queue must be kept", 2, queue.optJSONArray("tracks")?.length() ?: 0)

        var threw = false
        try {
            Facade.playAt(9L)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("out of range must throw", threw)

        Facade.togglePlay()
    }

    /** A station id is not a browse page id; the guard must reject it. */
    @Test
    fun emptyStationIdIsRejected() {
        var threw = false
        try {
            Facade.playStationJSON("demo", "")
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("empty station id must throw", threw)
    }

    private fun pluginIds(): List<String> {
        val arr = JSONArray(Facade.listPluginsJSON())
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
    }

    private fun demoTracks(): String {
        fun track(id: String, name: String) = JSONObject()
            .put("id", id)
            .put("source", "demo")
            .put("name", name)
            .put("artists", JSONArray().put(JSONObject().put("id", "a1").put("name", "The Examples")))
            .put("album", JSONObject().put("id", "al1").put("name", "First Album"))
            .put("duration", 12000)

        return JSONArray()
            .put(track("t1", "Song 1"))
            .put(track("t2", "Song 2"))
            .toString()
    }
}
