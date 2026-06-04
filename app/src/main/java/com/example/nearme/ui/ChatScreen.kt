package com.example.nearme.ui

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nearme.model.Message
import com.example.nearme.util.UnreadStore
import java.io.File

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    contactName: String,
    contactShortId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val isConnected by viewModel.isConnected.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val listState = rememberLazyListState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Mark conversation as read whenever messages update and we're on this screen
    LaunchedEffect(messages.size) {
        UnreadStore.markRead(context, contactShortId)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.sendFile(uri) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE  -> viewModel.clearActiveConversation()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    viewModel.restoreActiveConversation()
                    UnreadStore.markRead(context, contactShortId)
                }
                else -> { }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            UnreadStore.markRead(context, contactShortId)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {

        // ── Header ───────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Text("←", fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                }

                // Gradient avatar + presence dot
                Box {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    listOf(Color(0xFF4C1D95), Color(0xFF0369A1))
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = contactName.take(2).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .align(Alignment.BottomEnd)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(2.dp)
                            .background(
                                if (isConnected) Color(0xFF1D9E75) else Color(0xFFE24B4A),
                                CircleShape
                            )
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contactName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isConnected) "Connected" else "Connecting…",
                        fontSize = 12.sp,
                        color = if (isConnected) Color(0xFF1D9E75)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── Out-of-range banner ──────────────────────
        if (!isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEF9F27).copy(alpha = 0.15f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Connecting to $contactName… messages will send once linked.",
                    fontSize = 12.sp,
                    color = Color(0xFF8A5A00)
                )
            }
        }

        // ── Messages ─────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages.reversed(), key = { it.id }) { msg ->
                MessageBubble(msg, context)
            }
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
                        .clickable(enabled = isConnected) {
                            filePickerLauncher.launch("*/*")
                        },
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
                            text = "Type a message…",
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
                val canSend = inputText.isNotBlank() && isConnected
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
}

// ── Message bubble ──────────────────────────────────
@Composable
private fun MessageBubble(message: Message, context: android.content.Context) {
    val isMine = message.isFromMe
    val alignment = if (isMine) Alignment.End else Alignment.Start

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    brush = if (isMine) Brush.linearGradient(
                        listOf(Color(0xFF4C1D95), Color(0xFF0369A1))
                    ) else Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    ),
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isMine) 16.dp else 4.dp,
                        bottomEnd = if (isMine) 4.dp else 16.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val textColor = if (isMine) Color.White
            else MaterialTheme.colorScheme.onSurface

            // Content (file or text)
            if (message.filePath != null && message.mimeType != null) {
                when {
                    message.mimeType.startsWith("image/") -> {
                        val file = File(message.filePath)
                        val bitmap = remember(message.filePath) {
                            if (file.exists()) BitmapFactory.decodeFile(message.filePath) else null
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = message.content,
                                modifier = Modifier
                                    .widthIn(max = 240.dp)
                                    .heightIn(max = 280.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text("📷 ${message.content}", color = textColor)
                        }
                    }
                    message.mimeType.startsWith("video/") ->
                        Text("🎬 ${message.content}", color = textColor)
                    else ->
                        Text("📄 ${message.content}", color = textColor)
                }

                if (!isMine) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Save to device",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { saveFileToDevice(context, message) }
                    )
                }
            } else {
                Text(
                    text = message.content,
                    color = textColor,
                    fontSize = 14.sp
                )
            }

            // Delivery status (mine only)
            if (isMine) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when (message.status) {
                        "sending"  -> "⏳"
                        "sent"     -> "✓"
                        "delivered"-> "✓✓"
                        else       -> "✓"
                    },
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = if (message.status == "delivered") 1f else 0.7f),
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

// ── File save helper (unchanged from previous version) ──
private fun saveFileToDevice(context: android.content.Context, message: Message) {
    val filePath = message.filePath ?: return
    val mimeType = message.mimeType ?: "application/octet-stream"
    val sourceFile = File(filePath)
    if (!sourceFile.exists()) {
        Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = when {
                mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, message.content)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            }
            val uri = context.contentResolver.insert(collection, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
            }
            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
        } else {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val target = File(downloads, message.content)
            sourceFile.copyTo(target, overwrite = true)
            Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}