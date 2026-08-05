package id.my.femasaf.secondlink

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.os.Build
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

class MediaControllerManager(private val context: Context) {
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(context, MediaNotificationListener::class.java)

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun listSessions(): String {
        val sessions = activeSessions()
        if (!hasAccess()) return ACCESS_REQUIRED
        if (sessions.isEmpty()) return "Tidak ada sesi media aktif. Putar media sekali lalu coba lagi."
        return buildString {
            appendLine("🎵 *Sesi Media Aktif*")
            sessions.forEachIndexed { index, controller ->
                val marker = if (isSelected(controller)) "✅" else "▫️"
                appendLine("$marker ${index + 1}. ${appLabel(controller.packageName)}")
                appendLine("   `${controller.packageName}` • ${stateName(controller.playbackState?.state)}")
            }
            append("Gunakan `/media select <nomor>` untuk memilih.")
        }.trim()
    }

    fun selectSession(selector: String): String {
        val sessions = activeSessions()
        if (!hasAccess()) return ACCESS_REQUIRED
        val selected = selector.toIntOrNull()?.let { sessions.getOrNull(it - 1) }
            ?: sessions.firstOrNull { it.packageName.equals(selector, ignoreCase = true) }
            ?: return "❌ Sesi tidak ditemukan. Gunakan `/media list`."
        Config.setPreferredMediaPackage(context, selected.packageName)
        return "✅ Media aktif dipilih: ${appLabel(selected.packageName)}"
    }

    fun status(): String {
        if (!hasAccess()) return ACCESS_REQUIRED
        val controller = selectedController() ?: return "Tidak ada sesi media aktif."
        val metadata = controller.metadata
        val state = controller.playbackState
        val title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString().orEmpty().ifBlank { "Tanpa judul" }
        val artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString().orEmpty().ifBlank {
            metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.toString().orEmpty().ifBlank { "Tidak diketahui" }
        }
        val album = metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString().orEmpty().ifBlank { "—" }
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = estimatedPosition(state, duration)
        val playbackInfo = controller.playbackInfo
        val isRemote = playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
        val currentVolume = if (isRemote) playbackInfo.currentVolume else audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = if (isRemote) playbackInfo.maxVolume.coerceAtLeast(1)
            else audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volumePct = currentVolume * 100 / maxVolume
        return """
            🎵 *MEDIA SEKARANG*
            *Aplikasi:* ${appLabel(controller.packageName)}
            *Judul:* ${escapeMarkdown(title)}
            *Artis:* ${escapeMarkdown(artist)}
            *Album:* ${escapeMarkdown(album)}
            *Status:* ${stateName(state?.state)}
            *Posisi:* ${formatDuration(position)} / ${formatDuration(duration)}
            *Volume:* $volumePct% ${if (isRemote) "(remote)" else if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) "🔇" else "🔊"}
        """.trimIndent()
    }

    fun transport(action: String, argument: String?): String {
        if (!hasAccess()) return ACCESS_REQUIRED
        val controller = selectedController() ?: return "❌ Tidak ada sesi media aktif."
        val controls = controller.transportControls
        val state = controller.playbackState
        val supported = state?.actions ?: 0L
        return when (action.lowercase(Locale.ROOT)) {
            "play" -> perform(supported, PlaybackState.ACTION_PLAY, "Play") { controls.play() }
            "pause" -> perform(supported, PlaybackState.ACTION_PAUSE, "Pause") { controls.pause() }
            "toggle" -> {
                if (state?.state == PlaybackState.STATE_PLAYING) {
                    perform(supported, PlaybackState.ACTION_PAUSE, "Pause") { controls.pause() }
                } else {
                    perform(supported, PlaybackState.ACTION_PLAY, "Play") { controls.play() }
                }
            }
            "next" -> perform(supported, PlaybackState.ACTION_SKIP_TO_NEXT, "Next") { controls.skipToNext() }
            "previous", "prev" -> perform(supported, PlaybackState.ACTION_SKIP_TO_PREVIOUS, "Previous") { controls.skipToPrevious() }
            "stop" -> perform(supported, PlaybackState.ACTION_STOP, "Stop") { controls.stop() }
            "rewind" -> perform(supported, PlaybackState.ACTION_REWIND, "Rewind") { controls.rewind() }
            "fastforward", "ff" -> perform(supported, PlaybackState.ACTION_FAST_FORWARD, "Fast forward") { controls.fastForward() }
            "seek" -> seek(controller, argument)
            "queue" -> queue(controller)
            "track" -> selectTrack(controller, argument)
            "search" -> playFromSearch(controls, supported, argument)
            "speed" -> playbackSpeed(controls, supported, argument)
            "actions" -> customActions(controller)
            "custom" -> triggerCustomAction(controller, argument)
            "repeat", "shuffle" -> triggerNamedCustomAction(controller, action, argument)
            "volume" -> volume(controller, argument)
            else -> mediaHelp()
        }
    }

    fun mediaHelp(): String = """
        🎵 *Kontrol Media*
        `/media status #kode`
        `/media list #kode`
        `/media select <nomor> #kode`
        `/media play|pause|toggle #kode`
        `/media next|previous|stop #kode`
        `/media seek +30|-10|120 #kode`
        `/media rewind|ff #kode`
        `/media queue|track <nomor> #kode`
        `/media search <judul/artis> #kode`
        `/media speed 0.5-3.0 #kode`
        `/media volume up|down|0-100|mute|unmute|max #kode`
        `/media actions #kode`
        `/media custom <nomor> #kode`
        `/media repeat|shuffle <mode> #kode` (jika disediakan pemutar)
    """.trimIndent()

    private fun activeSessions(): List<MediaController> {
        if (!hasAccess()) return emptyList()
        return runCatching { sessionManager.getActiveSessions(listenerComponent) }.getOrDefault(emptyList())
    }

    private fun selectedController(): MediaController? {
        val sessions = activeSessions()
        val preferred = Config.getPreferredMediaPackage(context)
        return sessions.firstOrNull { it.packageName == preferred }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }

    private fun isSelected(controller: MediaController): Boolean =
        selectedController()?.sessionToken == controller.sessionToken

    private fun seek(controller: MediaController, value: String?): String {
        val raw = value?.trim() ?: return "❌ Masukkan detik: `+30`, `-10`, atau `120`."
        val seconds = raw.toLongOrNull() ?: return "❌ Nilai seek tidak valid."
        val metadataDuration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: Long.MAX_VALUE
        val current = estimatedPosition(controller.playbackState, metadataDuration)
        val target = if (raw.startsWith('+') || raw.startsWith('-')) current + seconds * 1000L else seconds * 1000L
        val bounded = target.coerceIn(0L, metadataDuration.coerceAtLeast(0L))
        val actions = controller.playbackState?.actions ?: 0L
        return perform(actions, PlaybackState.ACTION_SEEK_TO, "Seek ke ${formatDuration(bounded)}") {
            controller.transportControls.seekTo(bounded)
        }
    }

    private fun queue(controller: MediaController): String {
        val items = controller.queue.orEmpty()
        if (items.isEmpty()) return "Antrean media tidak tersedia dari pemutar ini."
        val activeId = controller.playbackState?.activeQueueItemId
        return buildString {
            appendLine("🎼 *Antrean Media*")
            items.take(30).forEachIndexed { index, item ->
                val marker = if (item.queueId == activeId) "▶️" else "▫️"
                val title = item.description.title?.toString().orEmpty().ifBlank { "Tanpa judul" }
                val subtitle = item.description.subtitle?.toString().orEmpty()
                appendLine("$marker ${index + 1}. ${escapeMarkdown(title)}${if (subtitle.isBlank()) "" else " — ${escapeMarkdown(subtitle)}"}")
            }
            if (items.size > 30) append("…dan ${items.size - 30} item lain.")
        }.trim()
    }

    private fun selectTrack(controller: MediaController, value: String?): String {
        val index = value?.toIntOrNull()?.minus(1) ?: return "❌ Gunakan `/media track <nomor>`."
        val item = controller.queue?.getOrNull(index) ?: return "❌ Nomor antrean tidak ditemukan."
        val actions = controller.playbackState?.actions ?: 0L
        return perform(actions, PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM, "Pilih track ${index + 1}") {
            controller.transportControls.skipToQueueItem(item.queueId)
        }
    }

    private fun playFromSearch(
        controls: MediaController.TransportControls,
        actions: Long,
        query: String?
    ): String {
        val cleanQuery = query?.trim()?.take(200).orEmpty()
        if (cleanQuery.isEmpty()) return "❌ Masukkan judul atau artis."
        return perform(actions, PlaybackState.ACTION_PLAY_FROM_SEARCH, "Cari dan putar") {
            controls.playFromSearch(cleanQuery, null)
        }
    }

    private fun playbackSpeed(
        controls: MediaController.TransportControls,
        actions: Long,
        value: String?
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "❌ Kontrol kecepatan memerlukan Android 10+."
        val speed = value?.toFloatOrNull() ?: return "❌ Kecepatan harus 0.5 sampai 3.0."
        if (speed !in 0.5f..3f) return "❌ Kecepatan harus 0.5 sampai 3.0."
        return perform(actions, PlaybackState.ACTION_SET_PLAYBACK_SPEED, "Kecepatan ${speed}x") {
            controls.setPlaybackSpeed(speed)
        }
    }

    private fun customActions(controller: MediaController): String {
        val actions = controller.playbackState?.customActions.orEmpty()
        if (actions.isEmpty()) return "Pemutar ini tidak menyediakan aksi tambahan."
        return buildString {
            appendLine("⚙️ *Aksi Tambahan Pemutar*")
            actions.forEachIndexed { index, action ->
                appendLine("${index + 1}. ${escapeMarkdown(action.name.toString())}")
            }
            append("Gunakan `/media custom <nomor>`. ")
        }.trim()
    }

    private fun triggerCustomAction(controller: MediaController, value: String?): String {
        val index = value?.toIntOrNull()?.minus(1)
            ?: return "❌ Gunakan `/media custom <nomor>`."
        val action = controller.playbackState?.customActions?.getOrNull(index)
            ?: return "❌ Nomor aksi tambahan tidak ditemukan."
        controller.transportControls.sendCustomAction(action.action, null)
        return "✅ ${action.name} dikirim ke pemutar."
    }

    private fun triggerNamedCustomAction(
        controller: MediaController,
        actionName: String,
        mode: String?
    ): String {
        val searchTerms = listOfNotNull(actionName, mode?.lowercase(Locale.ROOT))
        val action = controller.playbackState?.customActions?.firstOrNull { candidate ->
            val searchable = "${candidate.action} ${candidate.name}".lowercase(Locale.ROOT)
            searchTerms.all(searchable::contains)
        } ?: controller.playbackState?.customActions?.firstOrNull { candidate ->
            "${candidate.action} ${candidate.name}".lowercase(Locale.ROOT).contains(actionName)
        } ?: return "❌ Pemutar ini tidak menyediakan kontrol $actionName. Gunakan `/media actions`."
        controller.transportControls.sendCustomAction(action.action, null)
        return "✅ ${action.name} dikirim ke pemutar."
    }

    private fun volume(controller: MediaController, value: String?): String {
        val info = controller.playbackInfo
        val remote = info?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
        val max = if (remote) info.maxVolume.coerceAtLeast(1)
            else audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val controlType = info?.volumeControl ?: VolumeProvider.VOLUME_CONTROL_ABSOLUTE
        if (remote && controlType == VolumeProvider.VOLUME_CONTROL_FIXED) {
            return "❌ Volume perangkat remote dikunci oleh pemutar."
        }
        when (value?.lowercase(Locale.ROOT)) {
            "up" -> if (remote) controller.adjustVolume(AudioManager.ADJUST_RAISE, 0)
                else audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            "down" -> if (remote) controller.adjustVolume(AudioManager.ADJUST_LOWER, 0)
                else audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            "mute" -> if (remote) controller.setVolumeTo(0, 0)
                else audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> if (remote) controller.setVolumeTo((max / 2).coerceAtLeast(1), 0)
                else audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            "max" -> if (remote) controller.setVolumeTo(max, 0)
                else audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            else -> {
                val percent = value?.toIntOrNull()
                    ?: return "❌ Volume: up, down, mute, unmute, max, atau 0-100."
                if (percent !in 0..100) return "❌ Volume harus 0-100."
                val target = (percent * max / 100f).toInt()
                if (remote) controller.setVolumeTo(target, 0)
                else audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
        }
        val currentRaw = if (remote) controller.playbackInfo?.currentVolume ?: 0
            else audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val current = currentRaw * 100 / max
        return "✅ Volume media: $current%"
    }

    private inline fun perform(actions: Long, required: Long, label: String, block: () -> Unit): String {
        if (actions and required == 0L) return "❌ Pemutar ini tidak mendukung aksi $label."
        block()
        return "✅ $label dikirim ke pemutar media."
    }

    private fun estimatedPosition(state: PlaybackState?, duration: Long): Long {
        if (state == null) return 0L
        val elapsed = if (state.state == PlaybackState.STATE_PLAYING) {
            android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        } else 0L
        return (state.position + elapsed * state.playbackSpeed).toLong().coerceIn(0L, duration.coerceAtLeast(0L))
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "Playing ▶️"
        PlaybackState.STATE_PAUSED -> "Paused ⏸"
        PlaybackState.STATE_BUFFERING -> "Buffering"
        PlaybackState.STATE_STOPPED -> "Stopped ⏹"
        PlaybackState.STATE_ERROR -> "Error"
        PlaybackState.STATE_CONNECTING -> "Connecting"
        else -> "Idle"
    }

    private fun formatDuration(milliseconds: Long): String {
        if (milliseconds <= 0L || milliseconds == Long.MAX_VALUE) return "00:00"
        val totalSeconds = milliseconds / 1000L
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    private fun escapeMarkdown(value: String): String = value.replace("*", "\\*").replace("`", "'")

    companion object {
        const val ACCESS_REQUIRED = "⚠️ Akses kontrol media belum aktif. Buka aplikasi Android lalu aktifkan Notification access untuk SecondLink."
    }
}
