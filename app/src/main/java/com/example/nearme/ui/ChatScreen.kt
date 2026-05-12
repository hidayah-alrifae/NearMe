package com.example.nearme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.nearme.model.Message

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    contactName: String
) {
    // the text the user is currently typing
    var inputText by remember { mutableStateOf("") }

    // get the messages list from the ViewModel
    val messages by viewModel.messages.collectAsState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                // App went to background or user navigated away —
                // clear active conversation so notifications resume
                viewModel.clearActiveConversation()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // User came back — suppress notifications again
                viewModel.restoreActiveConversation()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // top bar — shows who you're chatting with
        Text(
            text = contactName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )

        // messages list — takes all available space between top bar and input
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            reverseLayout = true  // newest messages at the bottom like WhatsApp
        ) {
            items(messages.reversed()) { message ->
                MessageBubble(message)
            }
        }

        // bottom bar — text input and send button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // text field where user types
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Type a message...") },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // send button
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText.trim())
                        inputText = ""  // clear the text field after sending
                    }
                }
            ) {
                Text("Send")
            }
        }
    }
}

// a single message bubble
@Composable
fun MessageBubble(message: Message) {
    // my messages on the right (blue), their messages on the left (gray)
    val alignment = if (message.isFromMe) Alignment.End else Alignment.Start
    val color = if (message.isFromMe) Color(0xFF2196F3) else Color(0xFFE0E0E0)
    val textColor = if (message.isFromMe) Color.White else Color.Black


    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .background(color, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text = message.content, color = textColor)
        }
    }
}
