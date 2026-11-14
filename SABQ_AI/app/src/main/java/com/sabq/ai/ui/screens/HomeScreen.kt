package com.sabq.ai.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabq.ai.R
import com.sabq.ai.ui.theme.SabqColors

@Composable
fun HomeScreen(
    onStartScan: () -> Unit,
    onOpenAssessment: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenLabs: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header matching reference UI
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Menu, contentDescription = "القائمة", tint = SabqColors.TextPrimary)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(id = R.drawable.sabq_official_icon),
                    contentDescription = "شعار سَبَق",
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Text("سَبَق", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("SABQ AI", fontSize = 11.sp, color = SabqColors.Cyan)
            }
            IconButton(onClick = onOpenAssistant) {
                Icon(Icons.Default.Notifications, contentDescription = "الإشعارات الذكية", tint = SabqColors.Cyan)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("مرحبًا بك", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "ابدأ الآن الفحص الشامل وفق إمكانيات جهازك الفعلية",
            fontSize = 13.sp,
            color = SabqColors.TextMuted,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Anatomical System Grid matching reference layout
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SystemNode("القلب", Icons.Default.Favorite, onOpenLabs)
                SystemNode("التنفس", Icons.Default.Air, onOpenLabs)
                SystemNode("الوزن", Icons.Default.MonitorWeight, onStartScan)
                SystemNode("المناعة", Icons.Default.Shield, onOpenLabs)
            }
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(SabqColors.Cyan.copy(alpha = 0.25f), Color.Transparent)
                        )
                    )
                    .border(1.5.dp, SabqColors.Cyan.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.AccessibilityNew,
                    contentDescription = "خريطة الجسم",
                    tint = SabqColors.Cyan,
                    modifier = Modifier.size(96.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SystemNode("الدم", Icons.Default.WaterDrop, onOpenLabs)
                SystemNode("الغدة", Icons.Default.Biotech, onOpenLabs)
                SystemNode("البشرة", Icons.Default.FaceRetouchingNatural, onStartScan)
                SystemNode("الشعر", Icons.Default.ContentCut, onStartScan)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Primary CTA Button matching reference gradient
        Button(
            onClick = onStartScan,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SabqColors.Purple)
        ) {
            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = SabqColors.Cyan)
            Spacer(modifier = Modifier.width(10.dp))
            Text("ابدأ الفحص الشامل", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Action Tiles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickTile("تحليل الوجه", Icons.Default.Face, Modifier.weight(1f), onStartScan)
            QuickTile("تحليل الشعر", Icons.Default.Spa, Modifier.weight(1f), onStartScan)
            QuickTile("المؤشرات", Icons.Default.MonitorHeart, Modifier.weight(1f), onOpenLabs)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickTile("التقييم التقديري", Icons.Default.Assessment, Modifier.weight(1f), onOpenAssessment)
            QuickTile("تقاريري", Icons.Default.Description, Modifier.weight(1f), onOpenReports)
            QuickTile("ذاكرتي الصحية", Icons.Default.Psychology, Modifier.weight(1f), onOpenMemory)
        }
    }
}

@Composable
private fun SystemNode(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(SabqColors.SurfaceElevated)
                .border(1.dp, SabqColors.Purple.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = SabqColors.Cyan, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = SabqColors.TextPrimary)
    }
}

@Composable
private fun QuickTile(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = SabqColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, SabqColors.Border)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = title, tint = SabqColors.Cyan, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(title, fontSize = 12.sp, color = Color.White, textAlign = TextAlign.Center)
        }
    }
}
