package com.andy8401.mygo.car.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andy8401.mygo.car.data.Account
import com.andy8401.mygo.car.data.PluginSetting
import com.andy8401.mygo.car.ui.CarColors.Background
import com.andy8401.mygo.car.ui.CarColors.Cyan
import com.andy8401.mygo.car.ui.CarColors.Error
import com.andy8401.mygo.car.ui.CarColors.Surface
import com.andy8401.mygo.car.ui.CarColors.SurfaceHigh
import com.andy8401.mygo.car.ui.CarColors.Text
import com.andy8401.mygo.car.ui.CarColors.TextMuted

/**
 * Plugin manager: install (local zip or GitHub repo), configure, update, remove.
 *
 * The Go side already exposes all four (InstallPluginFromPath / FromRepo,
 * SettingsJSON / SetSettingJSON, CheckPluginUpdatesJSON, UninstallPlugin);
 * this screen is the missing front end for them on the head unit.
 */
@Composable
fun PluginManagerContent(ui: MainViewModel.UiState, vm: MainViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var repo by remember { mutableStateOf("") }

    // OpenDocument (not GetContent) keeps the content:// read grant alive while
    // we copy the bytes into private storage before Go sees the file.
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.installFromUri(context, uri)
    }

    LazyColumn(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("插件管理", style = MaterialTheme.typography.headlineLarge, color = Text)
                Spacer(Modifier.weight(1f))
                Text(
                    if (ui.updatesRunning) "检查中…" else "检查更新",
                    color = if (ui.updatesRunning) TextMuted else Cyan,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable(enabled = !ui.updatesRunning) { vm.checkUpdates() }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
        if (ui.busy.isNotBlank()) {
            item { Text(ui.busy, color = Cyan, style = MaterialTheme.typography.bodyLarge) }
        }
        item {
            Text(
                "已安装 ${ui.plugins.size} 个 · 点击“配置”修改插件自己的设置，" +
                    "仓库安装的插件可以检查并更新",
                color = TextMuted, style = MaterialTheme.typography.bodyMedium
            )
        }
        if (ui.plugins.isEmpty()) {
            item {
                Text(
                    "还没有安装任何插件。内置插件需要放在 assets 里随 App 发布，" +
                        "额外插件请用下面的安装区导入。",
                    color = TextMuted, style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        items(ui.plugins, key = { it.id }) { row ->
            PluginCard(
                row = row,
                active = row.id == ui.activeSourceId,
                account = ui.accounts[row.id],
                signingIn = ui.signingIn == row.id,
                onConfigure = { vm.openPluginSettings(row.id) },
                onUninstall = { vm.askUninstall(row.id) },
                onUpdate = { vm.installFromRepo(row.repo) },
                onSignIn = { vm.signIn(row.id) },
                onSignOut = { vm.signOut(row.id) }
            )
        }
        item {
            InstallSection(
                repo = repo,
                onRepoChange = { repo = it },
                busy = ui.busy.isNotBlank(),
                onPickFile = {
                    pickZip.launch(
                        arrayOf(
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                },
                onInstallRepo = { vm.installFromRepo(repo) }
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PluginCard(
    row: MainViewModel.PluginRow,
    active: Boolean,
    account: Account?,
    signingIn: Boolean,
    onConfigure: () -> Unit,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    // Two lines per card, buttons on the title line: a 407dp-tall car screen
    // otherwise fits only two plugins and the list needs constant scrolling.
    Column(
        Modifier.fillMaxWidth().background(SurfaceHigh, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, style = MaterialTheme.typography.titleLarge, color = Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (active) {
                        Text("当前音源", color = Cyan, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                Text(
                    listOfNotNull(
                        row.id,
                        row.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                        if (row.repo.isBlank()) "本地安装" else "仓库 ${row.repo}",
                        row.settingCount.takeIf { it > 0 }?.let { "$it 项设置" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            if (row.updateAvailable) {
                Button(onClick = onUpdate, modifier = Modifier.height(TouchTarget.dp)) {
                    Text("更新到 v${row.latest}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
            }
            if (row.canSignIn) {
                // Only a source that exports signIn() gets this button: asking
                // the others would fail with "这个音源不需要登录".
                if (account != null && account.signedIn) {
                    Text(
                        account.name.ifBlank { "已登录" },
                        color = Cyan,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.height(TouchTarget.dp)) {
                        Text("退出", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Button(
                        onClick = onSignIn,
                        enabled = !signingIn,
                        modifier = Modifier.height(TouchTarget.dp)
                    ) {
                        Text(if (signingIn) "登录中…" else "登录", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            Button(
                onClick = onConfigure,
                enabled = row.settingCount > 0,
                modifier = Modifier.height(TouchTarget.dp)
            ) { Text(if (row.settingCount > 0) "配置" else "无设置", style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onUninstall, modifier = Modifier.height(TouchTarget.dp)) {
                Text("卸载", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (row.updateError.isNotBlank()) {
            Text(
                "更新检查失败：${row.updateError}",
                color = Error, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun InstallSection(
    repo: String,
    onRepoChange: (String) -> Unit,
    busy: Boolean,
    onPickFile: () -> Unit,
    onInstallRepo: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("安装插件", style = MaterialTheme.typography.titleLarge, color = Text)
        Text(
            "本地 zip 直接导入；填 GitHub owner/repo 可联网安装，也是更新的来源。" +
                "安装同名插件会覆盖旧版本，插件数据保留。",
            style = MaterialTheme.typography.bodyMedium, color = TextMuted
        )
        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth().height(TouchTarget.dp)) {
            Text("选择 zip 文件安装", style = MaterialTheme.typography.labelLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = repo,
                onValueChange = onRepoChange,
                label = { Text("GitHub 仓库 owner/repo") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onInstallRepo,
                enabled = !busy && repo.isNotBlank(),
                modifier = Modifier.height(TouchTarget.dp)
            ) { Text("安装 / 更新", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Uninstall confirmation. Plugin data is kept, so this says so. */
@Composable
fun UninstallDialog(ui: MainViewModel.UiState, vm: MainViewModel) {
    val id = ui.confirmUninstall
    if (id.isBlank()) return
    val name = ui.plugins.firstOrNull { it.id == id }?.name ?: id
    androidx.activity.compose.BackHandler { vm.cancelUninstall() }
    AlertDialog(
        onDismissRequest = { vm.cancelUninstall() },
        title = { Text("卸载 $name", color = Text) },
        text = {
            Text(
                "插件目录会被删除，当前列表里的入口随即消失。" +
                    "它在 plugin-data 下的登录状态和设置会保留，重新安装后恢复。",
                color = TextMuted
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.confirmUninstall() }) { Text("卸载", color = Error) }
        },
        dismissButton = {
            TextButton(onClick = { vm.cancelUninstall() }) { Text("取消", color = TextMuted) }
        },
        containerColor = Surface
    )
}

/** Full-screen settings form for one plugin. A head unit is short, so the form
 *  scrolls instead of being clipped behind the transport bar. */
@Composable
fun PluginSettingsSheet(ui: MainViewModel.UiState, vm: MainViewModel) {
    val pluginId = ui.settingsFor
    if (pluginId.isBlank()) return
    val name = ui.plugins.firstOrNull { it.id == pluginId }?.name ?: pluginId
    // Without this, BACK leaves the app and drops the user on the launcher
    // while the form is still open behind it.
    androidx.activity.compose.BackHandler { vm.closePluginSettings() }

    // Opaque on purpose: a form with ghost text from the list behind it is
    // harder to read at a glance than the player sheet.
    Box(Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("$name · 设置", style = MaterialTheme.typography.headlineMedium, color = Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("关闭", color = Cyan, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { vm.closePluginSettings() }.padding(16.dp))
            }
            if (ui.pluginSettings.isEmpty()) {
                Text("这个插件没有声明可配置项。", color = TextMuted,
                    modifier = Modifier.padding(top = 24.dp))
                return@Column
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ui.pluginSettings.forEach { setting ->
                    SettingField(setting) { value -> vm.setPluginSetting(pluginId, setting.key, value) }
                }
                Spacer(Modifier.height(8.dp))
                Text("修改立刻写入插件存储，无需重启。", color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SettingField(setting: PluginSetting, onSave: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(setting.label, style = MaterialTheme.typography.titleMedium, color = Text)
        when (setting.type) {
            // Booleans persist as the strings "true"/"false" — the plugin
            // contract is string-only, there is no typed storage.
            "boolean" -> {
                val on = setting.value.equals("true", ignoreCase = true)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = on, onCheckedChange = { onSave(if (it) "true" else "false") })
                    Text(if (on) "开启" else "关闭", color = TextMuted,
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            "select" -> Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                setting.choices.forEach { choice ->
                    val selected = choice.value == setting.value
                    Text(
                        choice.label.ifBlank { choice.value },
                        color = if (selected) Background else Text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .background(if (selected) Cyan else SurfaceHigh, RoundedCornerShape(20.dp))
                            .clickable { onSave(choice.value) }
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    )
                }
            }
            else -> {
                // An unknown type stays editable as text so an older app can
                // still drive a newer plugin's manifest.
                var draft by remember(setting.key, setting.value) { mutableStateOf(setting.value) }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        visualTransformation = if (setting.type == "password") {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onSave(draft) },
                        enabled = draft != setting.value,
                        modifier = Modifier.height(TouchTarget.dp)
                    ) { Text("保存", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        if (setting.help.isNotBlank()) {
            Text(setting.help, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        }
        if (setting.link.isNotBlank()) {
            Text("获取地址：${setting.link}", style = MaterialTheme.typography.bodyMedium, color = Cyan,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
