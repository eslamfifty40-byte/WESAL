package com.wesal.messenger.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import java.text.BreakIterator

data class WesalEmojiItem(
    val glyph: String,
    val keywords: String
)

enum class EmojiCategory(val icon: ImageVector) {
    RECENT(Icons.Default.History),
    SMILEYS(Icons.Default.SentimentSatisfiedAlt),
    HEARTS(Icons.Default.Favorite),
    GESTURES(Icons.Default.ThumbUp),
    OBJECTS(Icons.Default.Star),
    NATURE(Icons.Default.Park)
}

object WesalEmojiUtils {
    fun insertAtSelection(current: TextFieldValue, emoji: String): TextFieldValue {
        val text = current.text
        val start = current.selection.min.coerceIn(0, text.length)
        val end = current.selection.max.coerceIn(0, text.length)
        val newText = buildString {
            append(text.substring(0, start))
            append(emoji)
            append(text.substring(end))
        }
        val cursor = start + emoji.length
        return TextFieldValue(text = newText, selection = TextRange(cursor))
    }

    /**
     * Grapheme-cluster aware Backspace so multi-codepoint emojis / skin tones / ZWJ sequences
     * delete cleanly as one unit instead of leaving broken surrogate halves.
     */
    fun backspaceAtSelection(current: TextFieldValue): TextFieldValue {
        val text = current.text
        if (text.isEmpty()) return current
        val selStart = current.selection.min.coerceIn(0, text.length)
        val selEnd = current.selection.max.coerceIn(0, text.length)
        if (selStart != selEnd) {
            val newText = text.removeRange(selStart, selEnd)
            return TextFieldValue(text = newText, selection = TextRange(selStart))
        }
        if (selStart == 0) return current
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val prevBoundary = iterator.preceding(selStart).takeIf { it != BreakIterator.DONE }
            ?: (selStart - 1).coerceAtLeast(0)
        val newText = text.removeRange(prevBoundary, selStart)
        return TextFieldValue(text = newText, selection = TextRange(prevBoundary))
    }

    val smileys = listOf(
        WesalEmojiItem("😀", "smile happy ابتسامة فرح"),
        WesalEmojiItem("😁", "grin happy ضحك سعادة"),
        WesalEmojiItem("😂", "joy laugh lol ضحك دموع"),
        WesalEmojiItem("🤣", "rofl laugh هستيريا ضحك"),
        WesalEmojiItem("😊", "blush cute لطيف خجل"),
        WesalEmojiItem("😇", "angel innocent ملاك"),
        WesalEmojiItem("🥰", "love adore حب قلوب"),
        WesalEmojiItem("😍", "heart eyes عشق حب"),
        WesalEmojiItem("🤩", "star struck واو نجم"),
        WesalEmojiItem("😘", "kiss بوسة حب"),
        WesalEmojiItem("😋", "yummy tasty لذيذ"),
        WesalEmojiItem("😜", "wink tongue مزاح غمزة"),
        WesalEmojiItem("🤗", "hug عناق حضن"),
        WesalEmojiItem("🤔", "think hmm تفكير سؤال"),
        WesalEmojiItem("😴", "sleep tired نوم نعاس"),
        WesalEmojiItem("🥳", "party celebrate احتفال مبروك"),
        WesalEmojiItem("😎", "cool sunglasses كول نظارة"),
        WesalEmojiItem("😢", "sad cry حزن دمعة"),
        WesalEmojiItem("😭", "sob cry بكاء حزن"),
        WesalEmojiItem("😡", "angry mad غضب زعل"),
        WesalEmojiItem("😱", "shock wow صدمة مفاجأة"),
        WesalEmojiItem("🙏", "pray please thanks شكراً رجاء"),
        WesalEmojiItem("🤲", "dua pray دعاء"),
        WesalEmojiItem("🤫", "shhh quiet سر صمت")
    )

    val hearts = listOf(
        WesalEmojiItem("❤️", "red heart love قلب حب"),
        WesalEmojiItem("🩵", "light blue turquoise heart قلب أزرق"),
        WesalEmojiItem("💚", "green heart wesal قلب أخضر"),
        WesalEmojiItem("💙", "blue heart قلب أزرق"),
        WesalEmojiItem("💜", "purple heart قلب بنفسجي"),
        WesalEmojiItem("🤍", "white heart قلب أبيض"),
        WesalEmojiItem("🖤", "black heart قلب أسود"),
        WesalEmojiItem("💖", "sparkling heart قلب لامع"),
        WesalEmojiItem("💘", "cupid love سهم حب"),
        WesalEmojiItem("💝", "gift heart هدية حب"),
        WesalEmojiItem("💞", "revolving hearts قلوب"),
        WesalEmojiItem("💔", "broken heart مكسور"),
        WesalEmojiItem("✨", "sparkles نجمة بريق"),
        WesalEmojiItem("🔥", "fire lit نار حماس"),
        WesalEmojiItem("💯", "100 perfect ممتاز تمام"),
        WesalEmojiItem("🌹", "rose flower وردة")
    )

    val gestures = listOf(
        WesalEmojiItem("👍", "thumbs up ok تمام لايك"),
        WesalEmojiItem("👎", "thumbs down لا ديسلايك"),
        WesalEmojiItem("👏", "clap applause تصفيق برافو"),
        WesalEmojiItem("🙌", "hands celebrate فرح رفع"),
        WesalEmojiItem("🤝", "handshake deal اتفاق مصافحة"),
        WesalEmojiItem("✌️", "peace victory نصر سلام"),
        WesalEmojiItem("🤞", "fingers crossed تمني"),
        WesalEmojiItem("👌", "ok fine تمام اوك"),
        WesalEmojiItem("🫡", "salute تحية تمام"),
        WesalEmojiItem("🫶", "heart hands حب قلب"),
        WesalEmojiItem("👋", "wave hi hello مرحبا سلام"),
        WesalEmojiItem("💪", "strong muscle قوة بطل"),
        WesalEmojiItem("☝️", "point up واحد"),
        WesalEmojiItem("✍️", "write note كتابة")
    )

    val objects = listOf(
        WesalEmojiItem("🎉", "party confetti احتفال مبروك"),
        WesalEmojiItem("🎊", "celebrate مهرجان"),
        WesalEmojiItem("🎁", "gift present هدية"),
        WesalEmojiItem("🏆", "trophy win فوز كأس"),
        WesalEmojiItem("⭐", "star نجمة"),
        WesalEmojiItem("🌙", "crescent moon هلال قمر"),
        WesalEmojiItem("☀️", "sun صباح شمس"),
        WesalEmojiItem("📱", "phone mobile هاتف جوال"),
        WesalEmojiItem("💬", "chat message رسالة"),
        WesalEmojiItem("🔒", "lock secure تشفير أمان"),
        WesalEmojiItem("📍", "location pin موقع"),
        WesalEmojiItem("🎵", "music note موسيقى صوت"),
        WesalEmojiItem("📷", "camera photo كاميرا صورة"),
        WesalEmojiItem("🚀", "rocket fast انطلاق صاروخ"),
        WesalEmojiItem("⚡", "lightning fast برق سريع"),
        WesalEmojiItem("✅", "check done تم صح")
    )

    val nature = listOf(
        WesalEmojiItem("🌺", "flower زهرة ورد"),
        WesalEmojiItem("🌻", "sunflower دوار الشمس"),
        WesalEmojiItem("🌴", "palm tree نخلة"),
        WesalEmojiItem("🌊", "ocean wave بحر موج"),
        WesalEmojiItem("🌈", "rainbow قوس قزح"),
        WesalEmojiItem("☕", "coffee قهوة صباح"),
        WesalEmojiItem("🍵", "tea شاي"),
        WesalEmojiItem("🍕", "pizza بيتزا"),
        WesalEmojiItem("🍔", "burger برجر"),
        WesalEmojiItem("🍰", "cake حلوى كيك"),
        WesalEmojiItem("🍎", "apple تفاح"),
        WesalEmojiItem("🍓", "strawberry فراولة")
    )

    val allItems = smileys + hearts + gestures + objects + nature
}

@Composable
fun WesalEmojiKeyboard(
    recentEmojis: List<String>,
    onEmojiSelected: (String) -> Unit,
    onBackspace: () -> Unit,
    onSwitchToSystemKeyboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    var selectedCategory by remember {
        mutableStateOf(if (recentEmojis.isNotEmpty()) EmojiCategory.RECENT else EmojiCategory.SMILEYS)
    }
    var search by remember { mutableStateOf("") }

    val displayedGlyphs = remember(search, selectedCategory, recentEmojis) {
        val q = search.trim()
        if (q.isNotBlank()) {
            WesalEmojiUtils.allItems
                .filter { it.keywords.contains(q, ignoreCase = true) || it.glyph == q }
                .map { it.glyph }
                .distinct()
        } else {
            when (selectedCategory) {
                EmojiCategory.RECENT -> recentEmojis
                EmojiCategory.SMILEYS -> WesalEmojiUtils.smileys.map { it.glyph }
                EmojiCategory.HEARTS -> WesalEmojiUtils.hearts.map { it.glyph }
                EmojiCategory.GESTURES -> WesalEmojiUtils.gestures.map { it.glyph }
                EmojiCategory.OBJECTS -> WesalEmojiUtils.objects.map { it.glyph }
                EmojiCategory.NATURE -> WesalEmojiUtils.nature.map { it.glyph }
            }
        }
    }

    Surface(
        color = palette.surface,
        tonalElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(290.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search & Keyboard / Backspace action bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(onClick = onSwitchToSystemKeyboard, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = strings.switchToTextKeyboard,
                        tint = palette.turquoise
                    )
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text(strings.emojiSearchPlaceholder, style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    trailingIcon = {
                        if (search.isNotBlank()) {
                            IconButton(onClick = { search = "" }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = strings.clear, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                )
                IconButton(onClick = onBackspace, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = strings.delete,
                        tint = palette.textPrimary
                    )
                }
            }

            // Category Tabs
            if (search.isBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceElevated)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    EmojiCategory.entries.forEach { cat ->
                        val active = selectedCategory == cat
                        val label = when (cat) {
                            EmojiCategory.RECENT -> strings.emojiRecent
                            EmojiCategory.SMILEYS -> strings.emojiSmileys
                            EmojiCategory.HEARTS -> strings.emojiHearts
                            EmojiCategory.GESTURES -> strings.emojiGestures
                            EmojiCategory.OBJECTS -> strings.emojiObjects
                            EmojiCategory.NATURE -> strings.emojiFoodNature
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) palette.emerald.copy(alpha = 0.20f) else palette.surfaceElevated)
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = cat.icon,
                                    contentDescription = label,
                                    tint = if (active) palette.emerald else palette.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Grid of Emojis
            if (displayedGlyphs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = strings.emojiEmptyRecent,
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 44.dp),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(displayedGlyphs, key = { "${selectedCategory.name}_$it" }) { emoji ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onEmojiSelected(emoji) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}
