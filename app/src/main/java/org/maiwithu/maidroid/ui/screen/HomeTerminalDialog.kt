package org.maiwithu.maidroid.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
internal fun TerminalOutputDialog(
    logs: List<String>,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val terminalText = remember(logs) {
        if (logs.isEmpty()) {
            "暂无命令输出。平台安装或 MaiBot 启动后会显示在这里。"
        } else {
            logs.joinToString("\n") { it.ifBlank { " " } }
        }
    }

    LaunchedEffect(logs.size, terminalText) {
        if (logs.isNotEmpty()) {
            delay(50)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "终端输出",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.38f))
                    .verticalScroll(scrollState)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = terminalText,
                        color = PlatformTextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PlatformOrange,
                    contentColor = Color.White
                )
            ) {
                Text(text = "关闭", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = PlatformCardSurface,
        titleContentColor = Color.White,
        textContentColor = PlatformTextSecondary
    )
}
