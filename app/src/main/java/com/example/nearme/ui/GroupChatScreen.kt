package com.example.nearme.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nearme.R
import com.example.nearme.model.Message
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.File

@SuppressLint("NewApi")
@Composable
fun GroupChatScreen(
    viewModel: GroupChatViewModel,
    groupId: String,
    groupName: String,
    onBack: () -> Unit,
    onAddMembers: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val messages by viewModel.messages.collectAsState()
    val group by viewModel.group.collectAsState()
    val listState = rememberLazyListState()
    var showLeaveDialog by remember { mutableStateOf(false) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.sendFile(it) } }

    DisposableEffect(Unit) { onDispose { viewModel.clearActive() } }

    val title = group?.groupName ?: groupName
    val memberCount = group?.members?.size ?: 1

    Column(modifier = Modifier.fillMaxSize().imePadding()) {

        // Header
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { onBack() }.padding(end = 8.dp))
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(title.take(2).uppercase(), fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.group_members_count, memberCount),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (group?.isHub == true) {
                    Text(stringResource(R.string.group_add_button), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onAddMembers() }.padding(start = 8.dp))
                }
                Text(stringResource(R.string.group_leave), fontSize = 13.sp,
                    color = Color(0xFFE24B4A),
                    modifier = Modifier.clickable { showLeaveDialog = true }.padding(start = 8.dp))
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages.reversed(), key = { it.id }) { msg -> GroupMessageBubble(msg) }
        }

        // ── Composer bar ─────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                        .clickable { filePickerLauncher.launch("*/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Text("📎", fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Text input pill
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(22.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_input_placeholder),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Send button — gradient pill
                val canSend = inputText.isNotBlank()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            brush = if (canSend) Brush.linearGradient(
                                listOf(Color(0xFF2E0A6E), Color(0xFF4C1D95), Color(0xFF0369A1))
                            ) else Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.surfaceVariant
                                )
                            ),
                            shape = CircleShape
                        )
                        .clickable(enabled = canSend) {
                            viewModel.sendMessage(inputText.trim())
                            inputText = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "➤",
                        fontSize = 16.sp,
                        color = if (canSend) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.group_leave_title)) },
            text = { Text(stringResource(R.string.group_leave_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.leaveGroup(); showLeaveDialog = false; onBack()
                }) { Text(stringResource(R.string.group_leave), color = Color(0xFFE24B4A)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun GroupMessageBubble(message: Message) {
    // System line (empty senderName) → centered chip.
    if (message.senderName.isBlank() && !message.isFromMe) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(message.content, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp))
        }
        return
    }

    val isMe = message.isFromMe
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp))
                .background(if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!isMe) {
                Text(message.senderName, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(2.dp))
            }
            val mime = message.mimeType
            if (mime != null && message.filePath != null) {
                val context = LocalContext.current
                when {
                    mime.startsWith("image/") -> {
                        val bitmap = remember(message.filePath) {
                            try { BitmapFactory.decodeFile(message.filePath) } catch (_: Exception) { null }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = message.content,
                                modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 280.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text("📷 ${message.content}",
                                color = if (isMe) Color.White else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    mime.startsWith("video/") -> {
                        Text("🎬 ${message.content}",
                            color = if (isMe) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                    else -> {
                        Text("📄 ${message.content}",
                            color = if (isMe) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (!isMe) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.chat_save_to_device),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { saveFileToDevice(context, message) }
                    )
                }
            } else {
                Text(message.content, fontSize = 14.sp,
                    color = if (isMe) Color.White else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

