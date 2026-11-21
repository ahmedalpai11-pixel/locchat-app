package com.meshchat.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.meshchat.android.mesh.MeshForegroundService
import com.meshchat.android.mesh.MeshRepository
import com.meshchat.android.model.ChatConversation
import com.meshchat.android.ui.ChatDetailScreen
import com.meshchat.android.ui.LocChatHomeScreen
import com.meshchat.android.ui.theme.LocChatTheme

class MainActivity : ComponentActivity() {

    private lateinit var repository: MeshRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MeshRepository.getInstance(applicationContext)

        setContent {
            LocChatTheme {
                LocChatRootScreen(activity = this, repository = repository)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.mediaHelper.stopAudio()
    }
}

@Composable
fun LocChatRootScreen(activity: ComponentActivity, repository: MeshRepository) {
    val context = activity.applicationContext

    val requiredPermissions = remember {
        mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    var hasPermissions by remember {
        mutableStateOf(requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    var isBluetoothOn by remember { mutableStateOf(checkBluetoothEnabled(context)) }
    var isLocationEnabled by remember { mutableStateOf(checkLocationEnabledForBle(context)) }

    // مراقبة حية لتشغيل/إغلاق البلوتوث أو الموقع من إعدادات الهاتف
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    isBluetoothOn = checkBluetoothEnabled(context)
                } else if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                    isLocationEnabled = checkLocationEnabledForBle(context)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
        isLocationEnabled = checkLocationEnabledForBle(context)
        isBluetoothOn = checkBluetoothEnabled(context)
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isBluetoothOn = checkBluetoothEnabled(context)
    }

    // شاشة الترحيب وطلب الصلاحيات وتشغيل البلوتوث
    if (!hasPermissions || !isBluetoothOn || !isLocationEnabled) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_locchat_logo),
                contentDescription = "LocChat",
                modifier = Modifier.size(90.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("مرحباً بك في LocChat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))

            when {
                !hasPermissions -> {
                    Text(
                        "يحتاج LocChat إلى إذن البلوتوث والموقع والإشعارات لكي يكتشف الأجهزة القريبة ويرسل الرسائل والصور بدون إنترنت.",
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                        Text("منح الإذن واستخدام البلوتوث")
                    }
                }
                !isBluetoothOn -> {
                    Text(
                        "البلوتوث مغلق حالياً. يرجى تشغيل البلوتوث لبدء الاتصال بالشبكة المحلية.",
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
                        Text("تشغيل البلوتوث")
                    }
                }
                !isLocationEnabled -> {
                    Text(
                        "في إصدار أندرويد الحالي يجب تشغيل خدمة الموقع (GPS) من الإعدادات ليسمح النظام بالبحث عن أجهزة البلوتوث القريبة.",
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                        Text("تفعيل خدمة الموقع")
                    }
                }
            }
        }
        return
    }

    // تشغيل خدمة الشبكة في الخلفية فور جاهزية كل الأذونات والبلوتوث
    LaunchedEffect(Unit) {
        MeshForegroundService.start(context)
    }

    val identity by repository.identity.collectAsState()
    val feedPosts by repository.feedPosts.collectAsState()
    val conversations by repository.conversations.collectAsState()
    val messagesByChat by repository.messagesByChat.collectAsState()
    val nearbyPeers by repository.nearbyPeers.collectAsState()

    var activeConversation by remember { mutableStateOf<ChatConversation?>(null) }
    var contactsRefreshTick by remember { mutableIntStateOf(0) }

    if (activeConversation == null) {
        key(contactsRefreshTick) {
            LocChatHomeScreen(
                identity = identity,
                contactsManager = repository.contactsManager,
                mediaHelper = repository.mediaHelper,
                posts = feedPosts,
                conversations = conversations,
                nearbyPeers = nearbyPeers,
                onOpenChat = { activeConversation = it },
                onStartChatWithPeer = { peerId, nickname ->
                    activeConversation = repository.openOrCreateConversation(peerId, nickname, isChannel = false)
                },
                onCreateChannel = { name ->
                    val normalized = if (name.startsWith("#")) name else "#$name"
                    activeConversation = repository.openOrCreateConversation(normalized, normalized, isChannel = true)
                },
                onCreatePost = { text, imgPath, sizeKb, category, ttlHours ->
                    repository.publishFeedPost(text, imgPath, sizeKb, category, ttlHours)
                },
                onToggleLike = { postId -> repository.togglePostLike(postId) },
                onAddComment = { postId, commentText -> repository.addPostComment(postId, commentText) },
                onUpdateNickname = { newName -> repository.updateNickname(newName) },
                onFriendVerified = { contactsRefreshTick++ },
                onPanicWipe = {
                    repository.performPanicWipe()
                    activeConversation = null
                    contactsRefreshTick++
                }
            )
        }
    } else {
        val chat = activeConversation!!
        val chatMsgs = messagesByChat[chat.id] ?: emptyList()

        ChatDetailScreen(
            conversation = chat,
            messages = chatMsgs,
            mediaHelper = repository.mediaHelper,
            onBack = { activeConversation = null },
            onSendText = { text, replyPreview ->
                repository.sendTextMessage(chat.id, chat.isChannel, text, replyPreview)
            },
            onSendImage = { uri ->
                repository.sendImageMessage(chat.id, chat.isChannel, uri)
            },
            onSendVoice = { file, duration ->
                repository.sendVoiceMessage(chat.id, chat.isChannel, file, duration)
            },
            onReactToMessage = { msgId, emoji ->
                repository.sendMessageReaction(chat.id, chat.isChannel, msgId, emoji)
            }
        )
    }
}

private fun checkBluetoothEnabled(context: Context): Boolean {
    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    return bm.adapter?.isEnabled == true
}

private fun checkLocationEnabledForBle(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return LocationManagerCompat.isLocationEnabled(lm)
}