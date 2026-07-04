package org.maiwithu.maidroid.ui.component

import androidx.compose.animation.Crossfade
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
import org.maiwithu.maidroid.ui.theme.BackgroundDark
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.ui.theme.SurfaceDark
import org.maiwithu.maidroid.ui.theme.TextDimmed

/**
 * 3-step progress indicator — sizes scaled 0.67× from 540px Figma canvas.
 */
@Composable
fun OobeStepIndicator(
    currentStep: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepNode(step = 1, isActive = currentStep == 0)
        StepConnector()
        StepNode(step = 2, isActive = currentStep == 1)
        StepConnector()
        StepNode(step = 3, isActive = currentStep == 2)
    }
}

@Composable
private fun StepNode(step: Int, isActive: Boolean) {
    val borderWidth by animateDpAsState(
        targetValue = if (isActive) 2.dp else 1.5.dp,
        label = "OobeStepBorderWidth"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) Color.White else SurfaceDark,
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
        Crossfade(
            targetState = isActive,
            label = "OobeStepNodeContent"
        ) { active ->
            if (active) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            } else {
                Text(
                    text = step.toString(),
                    fontSize = 16.sp,  // 24 × 0.67
                    fontWeight = FontWeight.Bold,
                    color = TextDimmed,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun RowScope.StepConnector() {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(3.dp)  // 4 × 0.67
            .background(SurfaceDark)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212, widthDp = 360)
@Composable
private fun OobeStepIndicatorPreview() {
    MaiDroidTheme {
        Box(modifier = Modifier.background(BackgroundDark).padding(24.dp)) {
            OobeStepIndicator(currentStep = 0)
        }
    }
}
