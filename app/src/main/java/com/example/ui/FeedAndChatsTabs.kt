package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.example.model.NearbyPeer
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
    modifier: Modifier = Modifier,
    myNickname: String = "",
    nearbyPeers: List<NearbyPeer> = emptyList()
) {
    val s = LocalAppStrings.current
    val filteredPosts = remember(posts, selectedCategory) {
        if (selectedCategory == PostCategory.ALL) posts
        else posts.filter { it.category == selectedCategory }
    }
    val visiblePeers = remember(nearbyPeers) { nearbyPeers.filter { !it.isBlocked } }
    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        // Instagram-style "stories": people around you, tap to start a private chat
        item(key = "stories") {
            Column {
                StoriesRow(
                    myNickname = myNickname,
                    peers = visiblePeers,
                    onAddPost = onOpenCreatePost,
                    onOpenPeer = onDirectMessageAuthor
                )
                FeedDivider()
            }
        }

        // X-style inline composer
        item(key = "compose") {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenCreatePost() }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .testTag("feed_quick_compose_bar"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LocChatAvatar(label = myNickname, size = 40.dp)
                    Text(
                        text = s.feedQuickComposePrompt,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = s.feedAttachImage,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                FeedDivider()
            }
        }

        if (broadcastBannerMessage != null) {
            item(key = "banner") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = VerifiedEmerald.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = broadcastBannerMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = VerifiedEmerald,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // X-style underlined tabs for categories
        item(key = "tabs") {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    PostCategory.entries.forEach { cat ->
                        val selected = selectedCategory == cat
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { onSelectCategory(cat) }
                                .padding(horizontal = 18.dp)
                                .testTag("feed_filter_${cat.id}")
                        ) {
                            Text(
                                text = cat.label(s.language),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 14.dp, bottom = 10.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .width(34.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                                    )
                            )
                        }
                    }
                }
                FeedDivider()
            }
        }

        if (filteredPosts.isEmpty()) {
            item(key = "empty") {
                EmptyStateBox(
                    icon = Icons.Default.DynamicFeed,
                    title = s.feedEmptyTitle,
                    subtitle = s.feedEmptySubtitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 56.dp)
                )
            }
        } else {
            items(filteredPosts, key = { it.id }) { post ->
                Column {
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
                    FeedDivider()
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
private fun StoriesRow(
    myNickname: String,
    peers: List<NearbyPeer>,
    onAddPost: () -> Unit,
    onOpenPeer: (peerId: String, nickname: String) -> Unit
) {
    val s = LocalAppStrings.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "story_me") {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(70.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onAddPost() }
                    .testTag("story_me")
            ) {
                Box(modifier = Modifier.size(66.dp), contentAlignment = Alignment.Center) {
                    LocChatAvatar(label = myNickname, size = 62.dp)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = s.fabNewPost,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        items(peers, key = { it.peerId }) { peer ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(70.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onOpenPeer(peer.peerId, peer.nickname) }
                    .testTag("story_peer_${peer.peerId}")
            ) {
                LocChatAvatar(label = peer.nickname, size = 66.dp, ring = true)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = peer.nickname,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PostAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String?,
    tint: Color,
    description: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
        if (!text.isNullOrEmpty()) {
            Spacer(modifier = Modifier.width(5.dp))
            Text(text = text, style = MaterialTheme.typography.labelMedium, color = tint)
        }
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
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("feed_post_${post.id}")
    ) {
        LocChatAvatar(label = post.authorName, size = 42.dp)
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = post.authorName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = formatTime(post.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.weight(1f))
                val catName = post.category.label(s.language)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (post.hopCount == 0) catName else "$catName • ${s.hopsLabel(post.hopCount)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = s.remainingHours(remainingHours),
                style = MaterialTheme.typography.labelSmall,
                color = muted
            )

            if (post.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
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
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .clickable { onImageClick() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FEED_EMOJIS.forEach { emoji ->
                    val count = post.reactions[emoji] ?: 0
                    val isSelected = post.myReaction == emoji
                    Surface(
                        onClick = { onReact(emoji) },
                        shape = CircleShape,
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        border = BorderStroke(
                            0.5.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.testTag("react_post_${post.id}_$emoji")
                    ) {
                        Text(
                            text = if (count > 0) "$emoji $count" else emoji,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PostAction(
                    icon = Icons.Outlined.ModeComment,
                    text = post.comments.size.takeIf { it > 0 }?.toString(),
                    tint = if (showComments) MaterialTheme.colorScheme.primary else muted,
                    description = s.commentsCount(post.comments.size),
                    modifier = Modifier.testTag("comments_post_${post.id}"),
                    onClick = { showComments = !showComments }
                )
                PostAction(
                    icon = Icons.Default.Repeat,
                    text = null,
                    tint = if (reBroadcastDone) VerifiedEmerald else muted,
                    description = if (reBroadcastDone) s.rebroadcastDoneLabel else s.rebroadcastLabel,
                    modifier = Modifier.testTag("rebroadcast_post_${post.id}"),
                    onClick = {
                        reBroadcastDone = true
                        onReBroadcast()
                    }
                )
                PostAction(
                    icon = if (post.isLikedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    text = post.likesCount.takeIf { it > 0 }?.toString(),
                    tint = if (post.isLikedByMe) MaterialTheme.colorScheme.error else muted,
                    description = s.likeLabel,
                    modifier = Modifier.testTag("like_post_${post.id}"),
                    onClick = onToggleLike
                )
                if (!isMine) {
                    PostAction(
                        icon = Icons.AutoMirrored.Filled.Send,
                        text = null,
                        tint = muted,
                        description = s.messageLabel,
                        modifier = Modifier.testTag("dm_author_${post.id}"),
                        onClick = onDirectMessage
                    )
                }
            }

            AnimatedVisibility(visible = showComments) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    post.comments.forEach { comment ->
                        Row(modifier = Modifier.padding(vertical = 6.dp)) {
                            LocChatAvatar(label = comment.authorName, size = 28.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = comment.authorName,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = formatTime(comment.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = muted
                                    )
                                }
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
                            shape = CircleShape,
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
            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
        ) {
            items(conversations, key = { it.id }) { conv ->
                val hasUnread = conv.unreadCount > 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenChat(conv) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("conversation_card_${conv.id}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                        LocChatAvatar(
                            label = conv.title,
                            size = 58.dp,
                            isChannel = conv.isChannel,
                            ring = hasUnread
                        )
                        if (conv.isOnline && !conv.isChannel) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(2.5.dp)
                                    .clip(CircleShape)
                                    .background(VerifiedEmerald)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = conv.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (conv.isProtectedChannel) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "قناة محمية بكلمة مرور",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            if (conv.isVerified) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = "موثق",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = conv.lastMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (hasUnread) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatTime(conv.lastMessageTime),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasUnread) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (hasUnread) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            ) {
                                Text("${conv.unreadCount}")
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
    subtitle: String,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
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
