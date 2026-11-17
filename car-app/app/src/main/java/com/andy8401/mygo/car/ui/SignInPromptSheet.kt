package com.andy8401.mygo.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp
import com.andy8401.mygo.car.ui.CarColors.Cyan
import com.andy8401.mygo.car.ui.CarColors.Text
import com.andy8401.mygo.car.ui.CarColors.TextMuted

/**
 * What a plugin wants shown while it signs in.
 *
 * 网易云 and QQ音乐 sign in by QR code instead of a WebView page: the plugin
 * pushes the code through host.signInPrompt, Go encodes it, and it arrives here
 * as rows of "0"/"1" — so the UI needs no QR library, only squares.
 */
@Composable
fun SignInPromptSheet(ui: MainViewModel.UiState, vm: MainViewModel) {
    val prompt = ui.signInPrompt ?: return
    androidx.activity.compose.BackHandler { vm.dismissSignInPrompt() }

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .background(CarColors.SurfaceHigh, RoundedCornerShape(20.dp))
                .padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "扫码登录 · ${sourceName(ui, prompt.pluginId)}",
                style = MaterialTheme.typography.titleLarge,
                color = Text
            )
            when {
                prompt.hasQR -> QRCode(prompt.matrix)
                else -> Text(
                    prompt.text.ifBlank { "请在手机上完成登录，完成后这里会自动关闭。" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextMuted
                )
            }
            if (prompt.hasQR && prompt.text.isNotBlank()) {
                Text(prompt.text, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
            // Dismissing only hides the card: the plugin keeps waiting, so the
            // sign-in still lands in the account row when it finishes.
            TextButton(onClick = { vm.dismissSignInPrompt() }) {
                Text("收起（登录仍在进行）", color = Cyan, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun sourceName(ui: MainViewModel.UiState, pluginId: String): String =
    ui.sources.firstOrNull { it.id == pluginId }?.name ?: pluginId

/** Draws the matrix on white; consecutive black modules merge into one rect. */
@Composable
private fun QRCode(rows: List<String>) {
    Box(
        Modifier
            .size(232.dp)
            .background(Color.White, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val n = rows.size
            if (n == 0) return@Canvas
            val cell = size.minDimension / n
            rows.forEachIndexed { y, row -> drawRow(row, y, cell) }
        }
    }
}

private fun DrawScope.drawRow(row: String, y: Int, cell: Float) {
    var x = 0
    while (x < row.length) {
        if (row[x] != '1') {
            x++
            continue
        }
        var end = x
        while (end < row.length && row[end] == '1') end++
        drawRect(
            color = Color.Black,
            topLeft = Offset(x * cell, y * cell),
            // The half-pixel bleed closes the seams that rounding leaves
            // between modules, which otherwise make the code unscannable.
            size = Size((end - x) * cell + 0.6f, cell + 0.6f)
        )
        x = end
    }
}
