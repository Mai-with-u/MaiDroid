package org.maiwithu.maidroid.ui.oobe

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.oobe.OobeStep
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.ui.theme.OobeBackground
import org.maiwithu.maidroid.ui.theme.OobeSurface
import org.maiwithu.maidroid.ui.theme.OobeTextDimmed
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * OOBE 顶部的三步指示器：当前步骤显示实心圆点，其余显示序号。尺寸按 540px 设计稿缩放 0.67 倍。
 */
@Composable
internal fun OobeStepIndicator(
    currentStep: OobeStep,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OobeStep.entries.forEachIndexed { index, step ->
            if (index > 0) StepConnector()
            StepNode(number = index + 1, isActive = step == currentStep)
        }
    }
}

@Composable
private fun StepNode(number: Int, isActive: Boolean) {
    val borderWidth by animateDpAsState(
        targetValue = if (isActive) 2.dp else 1.5.dp,
        label = "OobeStepBorderWidth"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) Color.White else OobeSurface,
        label = "OobeStepBorderColor"
    )

    Box(
        modifier = Modifier
            .size(24.dp)  // 36 × 0.67
            .clip(CircleShape)
            .border(borderWidth, borderColor, CircleShape)
            .background(Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        } else {
            Text(
                text = number.toString(),
                fontSize = TextSizes.BodyLarge,
                fontWeight = FontWeight.Bold,
                color = OobeTextDimmed,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun RowScope.StepConnector() {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(3.dp)  // 4 × 0.67
            .background(OobeSurface)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212, widthDp = 360)
@Composable
private fun OobeStepIndicatorPreview() {
    MaiDroidTheme {
        Box(modifier = Modifier.background(OobeBackground).padding(24.dp)) {
            OobeStepIndicator(currentStep = OobeStep.Permissions)
        }
    }
}
