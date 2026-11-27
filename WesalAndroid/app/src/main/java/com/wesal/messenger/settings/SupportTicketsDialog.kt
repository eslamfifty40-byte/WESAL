package com.wesal.messenger.settings

import com.wesal.messenger.core.util.ErrorCopy
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.domain.model.SupportTicket
import com.wesal.messenger.domain.model.SupportTicketMessage
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.wesalImageModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.wesal.messenger.presentation.components.wesalFieldColors

@HiltViewModel
class SupportTicketsViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    val myTickets: StateFlow<List<SupportTicket>> = userRepository.observeMySupportTickets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _feedback = MutableStateFlow<Pair<Boolean, String>?>(null)
    val feedback: StateFlow<Pair<Boolean, String>?> = _feedback.asStateFlow()

    fun observeMessages(ticketId: String): Flow<List<SupportTicketMessage>> =
        userRepository.observeSupportTicketMessages(ticketId)

    fun clearFeedback() {
        _feedback.value = null
    }

    fun submitTicket(
        subject: String,
        message: String,
        attachmentUri: Uri?,
        isEn: Boolean,
        onSubmitted: (String) -> Unit
    ) {
        if (_isBusy.value) return
        val cleanSubject = subject.trim()
        val cleanMsg = message.trim()
        if (cleanSubject.isBlank() || cleanMsg.isBlank()) {
            _feedback.value = false to (if (isEn) "Enter subject and description" else "أدخل عنوان المشكلة والتفاصيل")
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            userRepository.submitSupportTicket(cleanSubject, cleanMsg, attachmentUri)
                .onSuccess { ref ->
                    _isBusy.value = false
                    _feedback.value = true to (
                        if (isEn) "Ticket submitted (Ref: $ref)" else "تم إرسال الطلب للإدارة بنجاح (رقم: $ref)"
                        )
                    onSubmitted(ref)
                }
                .onFailure { err ->
                    _isBusy.value = false
                    _feedback.value = false to (
                        ErrorCopy.friendly(err, if (isEn) "Failed to submit ticket" else "تعذر إرسال طلب الدعم")
                        )
                }
        }
    }

    fun sendReply(ticketId: String, text: String, isEn: Boolean, onSent: () -> Unit) {
        if (_isBusy.value) return
        val clean = text.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            _isBusy.value = true
            userRepository.replySupportTicket(ticketId, clean, updateStatus = null)
                .onSuccess {
                    _isBusy.value = false
                    onSent()
                }
                .onFailure { err ->
                    _isBusy.value = false
                    _feedback.value = false to (
                        ErrorCopy.friendly(err, if (isEn) "Failed to send reply" else "تعذر إرسال الرد")
                        )
                }
        }
    }
}

@Composable
fun WesalSupportTicketsDialog(
    onDismiss: () -> Unit,
    onOpenDirectAdminChat: (() -> Unit)? = null,
    viewModel: SupportTicketsViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val tickets by viewModel.myTickets.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = New Ticket, 1 = My Tickets
    var subject by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var attachmentUri by remember { mutableStateOf<Uri?>(null) }
    var openedTicket by remember { mutableStateOf<SupportTicket?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) attachmentUri = uri
    }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        containerColor = palette.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.SupportAgent, contentDescription = null, tint = palette.emerald)
                Text(
                    text = if (openedTicket != null) {
                        openedTicket!!.referenceNumber.ifBlank { strings.supportHelpTitle }
                    } else {
                        strings.supportHelpTitle
                    },
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                feedback?.let { (ok, msg) ->
                    Surface(
                        color = (if (ok) palette.emerald else palette.error).copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = msg,
                                color = if (ok) palette.emerald else palette.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.clearFeedback() }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                val activeTicket = openedTicket
                if (activeTicket != null) {
                    val refreshed = tickets.firstOrNull { it.ticketId == activeTicket.ticketId } ?: activeTicket
                    val messages by remember(refreshed.ticketId) {
                        viewModel.observeMessages(refreshed.ticketId)
                    }.collectAsStateWithLifecycle(initialValue = emptyList())
                    var replyText by remember { mutableStateOf("") }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { openedTicket = null }) {
                            Text(if (strings.isEnglish) "← Back to tickets" else "← العودة للتذاكر", color = palette.turquoise)
                        }
                        SupportStatusChip(status = refreshed.status)
                    }
                    Text(refreshed.subject, color = palette.textPrimary, fontWeight = FontWeight.Bold)
                    Text(refreshed.message, color = palette.textSecondary, style = MaterialTheme.typography.bodySmall)
                    if (refreshed.attachmentUrl.isNotBlank()) {
                        AsyncImage(
                            model = wesalImageModel(refreshed.attachmentUrl),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    HorizontalDivider(color = palette.border)
                    if (messages.isEmpty()) {
                        Text(
                            text = if (strings.isEnglish) "No replies yet — WESAL Admin will respond soon." else "لا توجد ردود بعد — سيتواصل معك فريق الدعم قريبًا.",
                            color = palette.textSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(messages, key = { it.messageId }) { msg ->
                                Surface(
                                    color = if (msg.isFromAdmin) palette.emerald.copy(alpha = 0.12f) else palette.surfaceElevated,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            text = if (msg.isFromAdmin) {
                                                if (strings.isEnglish) "WESAL Support" else "فريق دعم WESAL"
                                            } else {
                                                msg.senderName.ifBlank { if (strings.isEnglish) "You" else "أنت" }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (msg.isFromAdmin) palette.emerald else palette.turquoise,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(msg.text, color = palette.textPrimary, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            DateTimeUtils.formatChatListTime(msg.createdAt),
                                            color = palette.textMuted,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (refreshed.status != "CLOSED") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                colors = wesalFieldColors(),
                                value = replyText,
                                onValueChange = { replyText = it },
                                placeholder = { Text(if (strings.isEnglish) "Write a reply..." else "اكتب ردًا...") },
                                modifier = Modifier.weight(1f),
                                maxLines = 2
                            )
                            IconButton(
                                enabled = !isBusy && replyText.isNotBlank(),
                                onClick = {
                                    viewModel.sendReply(refreshed.ticketId, replyText, strings.isEnglish) {
                                        replyText = ""
                                    }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = palette.emerald)
                            }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            label = { Text(strings.submitTicketTitle, maxLines = 1) }
                        )
                        FilterChip(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            label = { Text("${strings.myTicketsTitle} (${tickets.size})", maxLines = 1) }
                        )
                    }

                    if (selectedTab == 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                colors = wesalFieldColors(),
                                value = subject,
                                onValueChange = { subject = it },
                                label = { Text(strings.ticketSubjectLabel) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                colors = wesalFieldColors(),
                                value = description,
                                onValueChange = { description = it },
                                label = { Text(strings.ticketMessageLabel) },
                                minLines = 3,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedButton(
                                enabled = !isBusy,
                                onClick = {
                                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = null, tint = palette.turquoise)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (attachmentUri == null) {
                                        if (strings.isEnglish) "Attach Screenshot (Optional)" else "إرفاق صورة توضيحية (اختياري)"
                                    } else {
                                        if (strings.isEnglish) "Change Attachment ✓" else "تغيير الصورة المرفقة ✓"
                                    },
                                    color = palette.turquoise
                                )
                            }
                            if (attachmentUri != null) {
                                AsyncImage(
                                    model = wesalImageModel(attachmentUri.toString()),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            if (isBusy) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = palette.emerald)
                            }
                            if (onOpenDirectAdminChat != null) {
                                TextButton(
                                    onClick = {
                                        onDismiss()
                                        onOpenDirectAdminChat()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(strings.contactWesalAdmin, color = palette.emerald)
                                }
                            }
                        }
                    } else {
                        if (tickets.isEmpty()) {
                            Text(
                                text = if (strings.isEnglish) "No support tickets submitted yet." else "لم ترسل أي تذاكر دعم فني بعد.",
                                color = palette.textSecondary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(tickets, key = { it.ticketId }) { t ->
                                    Surface(
                                        color = palette.surfaceElevated,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, palette.border),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { openedTicket = t }
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(t.referenceNumber, color = palette.turquoise, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                                SupportStatusChip(status = t.status)
                                            }
                                            Text(t.subject, color = palette.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                text = t.lastReplyText.ifBlank { t.message },
                                                color = palette.textSecondary,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                DateTimeUtils.formatChatListTime(t.updatedAt),
                                                color = palette.textMuted,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (openedTicket == null && selectedTab == 0) {
                Button(
                    enabled = !isBusy && subject.isNotBlank() && description.isNotBlank(),
                    onClick = {
                        viewModel.submitTicket(subject, description, attachmentUri, strings.isEnglish) {
                            subject = ""
                            description = ""
                            attachmentUri = null
                            selectedTab = 1
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald)
                ) {
                    Text(if (strings.isEnglish) "Send Ticket" else "إرسال التذكرة")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !isBusy, onClick = onDismiss) {
                Text(strings.close)
            }
        }
    )
}

@Composable
fun SupportStatusChip(status: String) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val (color, label) = when (status.uppercase()) {
        "IN_PROGRESS" -> palette.turquoise to if (strings.isEnglish) "In Progress" else "قيد المعالجة"
        "RESOLVED" -> palette.emerald to if (strings.isEnglish) "Resolved" else "تم الحل"
        "CLOSED" -> palette.textMuted to if (strings.isEnglish) "Closed" else "مغلقة"
        else -> palette.warning to if (strings.isEnglish) "Open" else "مفتوحة"
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (status.equals("RESOLVED", true)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            }
            Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}
