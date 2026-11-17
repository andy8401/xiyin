package com.andy8401.mygo.car.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import com.andy8401.mygo.car.bridge.BridgeListener
import com.andy8401.mygo.car.bridge.GoBridge
import com.andy8401.mygo.car.bridge.InstallRequest
import com.andy8401.mygo.car.bridge.PluginInstaller
import com.andy8401.mygo.car.player.PlaybackHolder
import facade.Facade
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stage D/E smoke surface. Each button isolates one link in the chain so a
 * failure can be attributed to the Go facade, the plugin, or Media3 without
 * guessing. Stage E adds SAF / repo installation and origins / update checks.
 */
@Composable
fun SmokeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf("—") }
    var pluginList by remember { mutableStateOf("—") }
    var browseResult by remember { mutableStateOf("—") }
    var originsResult by remember { mutableStateOf("—") }
    var updatesResult by remember { mutableStateOf("—") }
    var status by remember { mutableStateOf("未初始化") }
    var lastEvent by remember { mutableStateOf("—") }
    var selectedPlugin by remember { mutableStateOf("") }
    var repoInput by remember { mutableStateOf("") }
    var uninstallInput by remember { mutableStateOf("") }

    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "Installing SAF zip..."
            val result = PluginInstaller.installFromSaf(context, uri)
            when (result) {
                is PluginInstaller.Result.Ok -> {
                    status = "Installed: ${result.id}"
                    pluginList = Facade.listPluginsJSON()
                }
                is PluginInstaller.Result.Failed -> status = "Install failed: ${result.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        BridgeListener.events.collect { lastEvent = it }
    }

    // Consume a mygocar://install?repo=... link once the UI is up.
    LaunchedEffect(InstallRequest.pendingRepo) {
        InstallRequest.pendingRepo?.let { repo ->
            InstallRequest.pendingRepo = null
            repoInput = repo
            GoBridge.init(context)
            status = "App Link install: $repo"
            val result = PluginInstaller.installFromRepo(repo)
            when (result) {
                is PluginInstaller.Result.Ok -> {
                    status = "Installed from repo: ${result.id}"
                    pluginList = Facade.listPluginsJSON()
                }
                is PluginInstaller.Result.Failed -> status = "Repo install failed: ${result.message}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "MyGo Car · Stage D/E 冒烟",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFF4DD0E1)
        )

        Mono("AAR version : $version")
        Mono("Status      : $status")
        Mono("Last event  : ${lastEvent.take(300)}")

        WideButton("① 初始化 Go 桥接 + 启动 MediaSession") {
            val count = GoBridge.init(context)
            GoBridge.startPlaybackService(context)
            version = Facade.version()
            status = "就绪：$count 个插件"
        }

        WideButton("② 列出插件") {
            pluginList = Facade.listPluginsJSON()
            val arr = JSONArray(pluginList)
            if (arr.length() > 0) {
                selectedPlugin = arr.getJSONObject(0).getString("id")
                status = "选中插件：$selectedPlugin"
            }
        }

        WideButton("③ Browse（选中插件首页）") {
            if (selectedPlugin.isBlank()) {
                status = "请先执行 ② 列出插件"
            } else {
                browseResult = Facade.browseJSON(selectedPlugin, "")
                status = "Browse 字节数：${browseResult.length}"
            }
        }

        WideButton("④ 播放 Browse 第一首（Go → Media3）") {
            val track = firstTrack(browseResult)
            if (track == null) {
                status = "首页没有可播曲目"
            } else {
                val tracks = JSONArray().put(track)
                Facade.playQueueJSON(selectedPlugin, tracks.toString(), 0)
                status = "已下发队列：${track.optString("name")}"
            }
        }

        WideButton("⑤ 直连本地 tone.mp3（仅验证 Media3）") {
            val file = File(context.filesDir, "tone.mp3")
            context.assets.open("plugins/demo/tone.mp3").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val player = PlaybackHolder.get(context)
            player.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(file)).build())
            player.prepare()
            player.play()
            status = "Media3 直连播放：${file.absolutePath}"
        }

        WideButton("⏯ 暂停 / 继续（经 Go）") {
            val playing = Facade.togglePlay()
            status = "togglePlay → $playing"
        }

        Text("— 阶段 E：插件安装 —", color = Color(0xFF80DEEA))

        WideButton("⑥ 从文件安装插件（SAF，选 zip）") {
            pickZip.launch("application/zip")
        }

        OutlinedTextField(
            value = repoInput,
            onValueChange = { repoInput = it },
            label = { Text("GitHub 仓库 owner/repo") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        WideButton("⑦ 从仓库安装/更新") {
            if (repoInput.isBlank()) {
                status = "请输入 owner/repo"
            } else {
                scope.launch {
                    status = "Fetching $repoInput ..."
                    when (val result = PluginInstaller.installFromRepo(repoInput.trim())) {
                        is PluginInstaller.Result.Ok -> {
                            status = "Installed: ${result.id}"
                            pluginList = Facade.listPluginsJSON()
                        }
                        is PluginInstaller.Result.Failed -> status = "Failed: ${result.message}"
                    }
                }
            }
        }

        WideButton("⑧ 列出插件来源") {
            scope.launch {
                originsResult = PluginInstaller.origins()
                status = "Origins 已刷新"
            }
        }

        WideButton("⑨ 检查仓库插件更新（联网）") {
            scope.launch {
                status = "Checking updates..."
                updatesResult = PluginInstaller.updates()
                status = "Updates 已刷新"
            }
        }

        OutlinedTextField(
            value = uninstallInput,
            onValueChange = { uninstallInput = it },
            label = { Text("要卸载的插件 ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        WideButton("⑩ 卸载插件") {
            if (uninstallInput.isBlank()) {
                status = "请输入插件 ID"
            } else {
                scope.launch {
                    when (val result = PluginInstaller.uninstall(uninstallInput.trim())) {
                        is PluginInstaller.Result.Ok -> {
                            status = "Uninstalled: ${result.id}"
                            pluginList = Facade.listPluginsJSON()
                        }
                        is PluginInstaller.Result.Failed -> status = "Uninstall failed: ${result.message}"
                    }
                }
            }
        }

        Text("Plugin JSON：", color = Color(0xFF80DEEA))
        Mono(pluginList.take(1200))
        Text("Origins JSON：", color = Color(0xFF80DEEA))
        Mono(originsResult.take(1200))
        Text("Updates JSON：", color = Color(0xFF80DEEA))
        Mono(updatesResult.take(1200))
        Text("Browse JSON：", color = Color(0xFF80DEEA))
        Mono(browseResult.take(1200))
        Mono(InstallRequest.message.value)
    }
}

@Composable
private fun WideButton(label: String, onClick: () -> Unit) {
    val context = LocalContext.current
    Button(
        onClick = {
            runCatching(onClick).onFailure {
                Toast.makeText(context, it.toString(), Toast.LENGTH_LONG).show()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Text(label, fontSize = 16.sp)
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = Color(0xFFB0BEC5)
    )
}

private fun firstTrack(browseJSON: String): JSONObject? {
    if (browseJSON.isBlank() || browseJSON == "—") return null
    val sections = JSONObject(browseJSON).optJSONArray("sections") ?: return null
    for (i in 0 until sections.length()) {
        val tracks = sections.optJSONObject(i)?.optJSONArray("tracks") ?: continue
        if (tracks.length() > 0) return tracks.getJSONObject(0)
    }
    return null
}
