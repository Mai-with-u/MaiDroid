package org.maiwithu.maidroid.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ui.theme.Orange400
import org.maiwithu.maidroid.ui.theme.SurfaceDark
import androidx.compose.ui.tooling.preview.Preview
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.ui.theme.Orange400
import org.maiwithu.maidroid.ui.theme.TextOnOrange
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSecondary

/**
 * Permission card — all sizes scaled 0.67× from the 540px Figma canvas.
 */
@Composable
fun PermissionCard(
    @DrawableRes iconRes: Int,
    title: String,
    description: String,
    required: Boolean,
    onAuthorize: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(22.dp))  // 32 × 0.67
            .background(SurfaceDark)
            .padding(horizontal = 17.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon container: 48dp (72 × 0.67)
            Box(
                modifier = Modifier
                    .size(48.dp)           // 72 × 0.67
                    .clip(RoundedCornerShape(11.dp))  // 16 × 0.67
                    .background(Orange400),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = title,
                    modifier = Modifier.size(28.dp),  // 42 × 0.67
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(15.dp))  // 22 × 0.67

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (required) "必须" else "可选",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextOnOrange,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Orange400)
                            .padding(horizontal = 6.dp, vertical = 0.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))  // 18 × 0.67

            Button(
                onClick = onAuthorize,
                modifier = Modifier.size(width = 64.dp, height = 28.dp),  // 96/42 × 0.67
                shape = RoundedCornerShape(8.dp),  // 12 × 0.67
                colors = ButtonDefaults.buttonColors(
                    containerColor = Orange400,
                    contentColor = TextOnOrange
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text(
                    text = "去授权",
                    fontSize = 12.sp,  // 20 × 0.67
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212, widthDp = 360)
@Composable
private fun PermissionCardPreview() {
    MaiDroidTheme {
        Box(modifier = Modifier.background(Color(0xFF121212)).padding(24.dp)) {
            PermissionCard(
                iconRes = R.drawable.ic_storage_hdd,
                title = "存储权限",
                description = "MaiSaka需要存储权限才能安装，运行，和保存配置文件",
                required = true,
                onAuthorize = {}
            )
        }
    }
}
