package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.MediaHelper
import com.example.model.ChatConversation
import com.example.model.FeedPost
import com.example.model.PostCategory
import com.example.ui.theme.LocChatBrandGradient
import com.example.ui.theme.NearbyCyanTeal
import com.example.ui.theme.VerifiedEmerald
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FEED_EMOJIS = listOf("👍", "❤️", "🔥", "👏")

@Composable
fun FeedTab(
    posts: List<FeedPost>,
    activePeersCount: Int,
    cachedPacketsCount: Int,
    myNodeId: String,
    selectedCategory: PostCategory,
    broadcastBannerMessage: String?,
    onOpenCreatePost: () -> Unit,
    onSelectCategory: (PostCategory) -> Unit,
    onToggleLike: (String) -> Unit,
    onReactToPost: (String, String) -> Unit,
    onAddComment: (String, String) -> Unit,
    onReBroadcastPost: (String) -> Unit,
    onDirectMessageAuthor: (peerId: String, nickname: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalAppStrings.current
    val filteredPosts = remember(posts, selectedCategory) {
        if (selectedCategory == PostCategory.ALL) posts
        else posts.filter { it.category == selectedCategory }
    }
    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, LocChatBrandGradient),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(LocChatBrandGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.feedHeroTitleAvailable(activePeersCount),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = s.feedHeroSubtitle(cachedPacketsCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Inline Quick Publish Bar right on the Home Page
        Surface(
            onClick = onOpenCreatePost,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("feed_quick_compose_bar"),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            border = locChatRimBorder()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BluetoothConnected,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = s.feedQuickComposePrompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = s.feedPublishConfirm,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        AnimatedVisibility(visible = broadcastBannerMessage != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.small,
                color = VerifiedEmerald.copy(alpha = 0.14f)
            ) {
                Text(
                    text = broadcastBannerMessage.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = VerifiedEmerald,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PostCategory.entries.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { onSelectCategory(cat) },
                    label = { Text(cat.label(s.language)) },
                    modifier = Modifier.testTag("feed_filter_${cat.id}")
                )
            }
        }

        if (filteredPosts.isEmpty()) {
            EmptyStateBox(
                icon = Icons.Default.DynamicFeed,
                title = s.feedEmptyTitle,
                subtitle = s.feedEmptySubtitle
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredPosts, key = { it.id }) { post ->
                    FeedPostCard(
                        post = post,
                        isMine = post.authorId == myNodeId,
                        onToggleLike = { onToggleLike(post.id) },
                        onReact = { emoji -> onReactToPost(post.id, emoji) },
                        onAddComment = { comment -> onAddComment(post.id, comment) },
                        onReBroadcast = { onReBroadcastPost(post.id) },
                        onDirectMessage = { onDirectMessageAuthor(post.authorId, post.authorName) },
                        onImageClick = { post.imagePath?.let { fullscreenImagePath = it } }
                    )
                }
            }
        }
    }

    fullscreenImagePath?.let { imgPath ->
        AlertDialog(
            onDismissRequest = { fullscreenImagePath = null },
            confirmButton = {
                TextButton(onClick = { fullscreenImagePath = null }) { Text(s.closeLabel) }
            },
            text = {
                AsyncImage(
                    model = File(imgPath),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(MaterialTheme.shapes.medium)
                )
            }
        )
    }
}

@Composable
private fun FeedPostCard(
    post: FeedPost,
    isMine: Boolean,
    onToggleLike: () -> Unit,
    onReact: (String) -> Unit,
    onAddComment: (String) -> Unit,
    onReBroadcast: () -> Unit,
    onDirectMessage: () -> Unit,
    onImageClick: () -> Unit
) {
    val s = LocalAppStrings.current
    var showComments by remember { mutableStateOf(false) }
    var commentDraft by remember { mutableStateOf("") }
    var reBroadcastDone by remember { mutableStateOf(false) }
    val remainingHours = remember(post.expiresAt) {
        ((post.expiresAt - System.currentTimeMillis()) / 3_600_000L).coerceAtLeast(1L)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("feed_post_${post.id}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = locChatRimBorder(),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(LocChatBrandGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = post.authorName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = post.authorName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${formatTime(post.createdAt)} • ${s.remainingHours(remainingHours)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(
                    onClick = {},
                    label = {
                        val catName = post.category.label(s.language)
                        Text(
                            text = if (post.hopCount == 0) catName else "$catName • ${s.hopsLabel(post.hopCount)}",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }

            if (post.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = post.text,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (!post.imagePath.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                AsyncImage(
                    model = File(post.imagePath),
                    contentDescription = "صورة المنشور",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onImageClick() }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FEED_EMOJIS.forEach { emoji ->
                    val count = post.reactions[emoji] ?: 0
                    val isSelected = post.myReaction == emoji
                    FilterChip(
                        selected = isSelected,
                        onClick = { onReact(emoji) },
                        label = {
                            Text(if (count > 0) "$emoji $count" else emoji)
                        },
                        modifier = Modifier.testTag("react_post_${post.id}_$emoji")
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onToggleLike,
                    modifier = Modifier.testTag("like_post_${post.id}")
                ) {
                    Icon(
                        imageVector = if (post.isLikedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = s.likeLabel,
                        tint = if (post.isLikedByMe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${post.likesCount}")
                }

                TextButton(
                    onClick = { showComments = !showComments },
                    modifier = Modifier.testTag("comments_post_${post.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ModeComment,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(s.commentsCount(post.comments.size))
                }

                TextButton(
                    onClick = {
                        reBroadcastDone = true
                        onReBroadcast()
                    },
                    modifier = Modifier.testTag("rebroadcast_post_${post.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (reBroadcastDone) s.rebroadcastDoneLabel else s.rebroadcastLabel)
                }

                if (!isMine) {
                    TextButton(
                        onClick = onDirectMessage,
                        modifier = Modifier.testTag("dm_author_${post.id}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(s.messageLabel)
                    }
                }
            }

            AnimatedVisibility(visible = showComments) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    post.comments.forEach { comment ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = comment.authorName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = formatTime(comment.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(text = comment.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = commentDraft,
                            onValueChange = { commentDraft = it },
                            placeholder = { Text(s.commentPlaceholder) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("comment_input_${post.id}")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (commentDraft.isNotBlank()) {
                                    onAddComment(commentDraft)
                                    commentDraft = ""
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("send_comment_${post.id}")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatsTab(
    conversations: List<ChatConversation>,
    onOpenChat: (ChatConversation) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalAppStrings.current
    if (conversations.isEmpty()) {
        EmptyStateBox(
            icon = Icons.AutoMirrored.Filled.Chat,
            title = s.chatsEmptyTitle,
            subtitle = s.chatsEmptySubtitle
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(conversations, key = { it.id }) { conv ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onOpenChat(conv) }
                        .testTag("conversation_card_${conv.id}"),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = locChatRimBorder(),
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (conv.isChannel) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.primaryContainer
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (conv.isChannel) Icons.Default.Tag else Icons.Default.Person,
                                contentDescription = if (conv.isChannel) "قناة محلية" else "محادثة فردية",
                                tint = if (conv.isChannel) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = conv.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (conv.isProtectedChannel) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "قناة محمية بكلمة مرور",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                if (conv.isVerified) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = "موثق",
                                        tint = VerifiedEmerald,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (conv.isOnline && !conv.isChannel) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(VerifiedEmerald)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = conv.lastMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = formatTime(conv.lastMessageTime),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (conv.unreadCount > 0) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                    Text("${conv.unreadCount}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreatePostDialog(
    mediaHelper: MediaHelper,
    onDismiss: () -> Unit,
    onPublish: (text: String, imgPath: String?, sizeKb: Int, category: PostCategory, ttlHours: Int) -> Unit
) {
    val s = LocalAppStrings.current
    var text by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(PostCategory.GENERAL) }
    var ttlHours by remember { mutableIntStateOf(24) }
    var compressedImage by remember { mutableStateOf<Pair<String, Int>?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            compressedImage = mediaHelper.compressImageFromUri(uri)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.feedDialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(s.feedInputLabel) },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_post_text_input")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PostCategory.entries.filter { it != PostCategory.ALL }.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat.label(s.language)) }
                        )
                    }
                }

                Text(s.feedTtlLabel, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6, 24, 48, 72).forEach { hours ->
                        FilterChip(
                            selected = ttlHours == hours,
                            onClick = { ttlHours = hours },
                            label = { Text("$hours${s.hoursSuffix}") }
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pick_post_image_button")
                ) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        compressedImage?.let { s.feedAttachedImageKb(it.second) }
                            ?: s.feedAttachImage
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onPublish(
                        text,
                        compressedImage?.first,
                        compressedImage?.second ?: 0,
                        selectedCategory,
                        ttlHours
                    )
                    onDismiss()
                },
                enabled = text.isNotBlank() || compressedImage != null,
                modifier = Modifier.testTag("publish_post_confirm_button")
            ) {
                Text(s.feedPublishConfirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.cancelLabel) }
        }
    )
}

@Composable
fun CreateChannelDialog(
    onDismiss: () -> Unit,
    onCreateChannel: (name: String, passphrase: String) -> Unit
) {
    val s = LocalAppStrings.current
    var name by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.createChannelTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(s.channelNameLabel) },
                    prefix = { Text("#") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_channel_input")
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(s.channelPasswordLabel) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_channel_passphrase_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onCreateChannel(name.trim(), passphrase.trim())
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("create_channel_confirm_button")
            ) {
                Text(s.enterChannelConfirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.cancelLabel) }
        }
    )
}

@Composable
fun EmptyStateBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, LocChatBrandGradient)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = NearbyCyanTeal
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
