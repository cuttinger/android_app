package com.xingang.ttsreader

import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Exposes the reader as a media app. Android Auto (and the Automotive OS
 * media center) list media apps readily, show the current item's title and
 * subtitle on the Now Playing screen, and route steering-wheel controls
 * through the media session.
 *
 * Browse tree: root -> passages -> sentences. Metadata: title = the sentence
 * being spoken: title = the word with its 中文解释, subtitle = the example
 * sentence, album = passage title.
 */
class ReaderMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private lateinit var library: Library
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastPassageId: String? = null

    override fun onCreate() {
        super.onCreate()
        ReaderEngine.init(this)
        library = Library(this)
        session = MediaSessionCompat(this, "TtsReader").apply {
            setCallback(callback)
            isActive = true
        }
        sessionToken = session.sessionToken
        token = session.sessionToken
        scope.launch { ReaderEngine.state.collect { publish(it) } }
    }

    override fun onDestroy() {
        token = null
        session.release()
        super.onDestroy()
    }

    // ---- browsing ----------------------------------------------------------

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val items = ArrayList<MediaBrowserCompat.MediaItem>()
        if (parentId == ROOT) {
            for (p in library.passages()) {
                items += MediaBrowserCompat.MediaItem(
                    MediaDescriptionCompat.Builder()
                        .setMediaId("$PASSAGE_PREFIX${p.id}")
                        .setTitle(p.title)
                        .setSubtitle(Library.previewOf(p.body, 60))
                        .build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE or MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
                )
            }
        } else if (parentId.startsWith(PASSAGE_PREFIX)) {
            val id = parentId.removePrefix(PASSAGE_PREFIX)
            library.find(id)?.let { p ->
                Sentences.split(p.body).forEachIndexed { i, s ->
                    items += MediaBrowserCompat.MediaItem(
                        MediaDescriptionCompat.Builder()
                            .setMediaId("$SENTENCE_PREFIX$id:$i")
                            .setTitle(s.carTitle)
                            .setSubtitle(s.carSubtitle)
                            .setIconUri(ArtProvider.badgeUri(i + 1))
                            .build(),
                        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
                    )
                }
            }
        }
        result.sendResult(items)
    }

    // ---- transport controls --------------------------------------------------

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            val s = ReaderEngine.state.value
            if (!s.hasContent) library.passages().firstOrNull()?.let { ReaderEngine.load(it) } else ReaderEngine.play()
        }

        override fun onPause() = ReaderEngine.pause()
        override fun onStop() = ReaderEngine.pause()
        override fun onSkipToNext() = ReaderEngine.next()
        override fun onSkipToPrevious() = ReaderEngine.previous()
        override fun onSkipToQueueItem(id: Long) = ReaderEngine.seek(id.toInt())

        override fun onSetShuffleMode(shuffleMode: Int) {
            ReaderEngine.setShuffle(shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE)
        }

        /**
         * One button cycles the reading order, because a car screen has no
         * room for four and no safe way to present a menu while moving.
         */
        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                ACTION_SPEAK_MODE -> {
                    val modes = SpeakMode.entries
                    val now = ReaderEngine.state.value.speakMode
                    ReaderEngine.setSpeakMode(modes[(modes.indexOf(now) + 1) % modes.size])
                }
                // Head units do not all draw the standard shuffle control, so
                // the same toggle is offered as a button of our own.
                ACTION_SHUFFLE -> ReaderEngine.toggleShuffle()
            }
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            when {
                mediaId == null -> onPlay()
                mediaId.startsWith(PASSAGE_PREFIX) ->
                    library.find(mediaId.removePrefix(PASSAGE_PREFIX))?.let { ReaderEngine.load(it) }
                mediaId.startsWith(SENTENCE_PREFIX) -> {
                    val (pid, idx) = mediaId.removePrefix(SENTENCE_PREFIX).split(':')
                    val p = library.find(pid) ?: return
                    if (ReaderEngine.state.value.passageId != pid) ReaderEngine.load(p, idx.toInt())
                    else { ReaderEngine.seek(idx.toInt()); ReaderEngine.play() }
                }
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) = onPlay()
    }

    // ---- state -> session ----------------------------------------------------

    private var artKey: String? = null
    private var art: android.graphics.Bitmap? = null

    private fun publish(s: ReaderEngine.State) {
        val current = s.current
        // Render the sentence as the "album art" so the artwork square shows text, not a placeholder.
        val key = "${s.passageId}:${s.index}"
        if (current != null && key != artKey) {
            artKey = key
            art = SentenceArt.nowPlaying(current, s.index, s.sentences.size)
        }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .apply {
                    // Bitmap for the phone notification / lock screen; URI for car media UIs.
                    art?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
                    s.passageId?.let { pid ->
                        val uri = ArtProvider.artUri(pid, s.index).toString()
                        putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, uri)
                        putString(MediaMetadataCompat.METADATA_KEY_ART_URI, uri)
                        putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, uri)
                    }
                }
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "$SENTENCE_PREFIX${s.passageId}:${s.index}")
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, current?.carTitle ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, current?.carSubtitle ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, s.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, current?.carTitle ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, current?.carSubtitle ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (s.index + 1).toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, s.sentences.size.toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                .build()
        )
        session.setShuffleMode(
            if (s.shuffle) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE
        )
        val state = when {
            s.error != null -> PlaybackStateCompat.STATE_ERROR
            s.playing -> PlaybackStateCompat.STATE_PLAYING
            s.hasContent -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActiveQueueItemId(s.index.toLong())
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
                )
                // The label carries the current mode, so the driver can see
                // what tapping it has done without leaving the player.
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        ACTION_SHUFFLE,
                        getString(if (s.shuffle) R.string.order_shuffle else R.string.order_sequential),
                        if (s.shuffle) R.drawable.ic_shuffle else R.drawable.ic_order_sequential,
                    ).build()
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        ACTION_SPEAK_MODE,
                        getString(R.string.car_speak_mode, getString(s.speakMode.labelRes)),
                        s.speakMode.iconRes,
                    ).build()
                )
                .apply { s.error?.let { setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it) } }
                .build()
        )
        // The queue (all sentences) is rebuilt only when the passage changes.
        if (s.passageId != lastPassageId) {
            lastPassageId = s.passageId
            session.setQueueTitle(s.title)
            session.setQueue(s.sentences.mapIndexed { i, sentence ->
                MediaSessionCompat.QueueItem(
                    MediaDescriptionCompat.Builder()
                        .setMediaId("$SENTENCE_PREFIX${s.passageId}:$i")
                        .setTitle(sentence.carTitle)
                        .setSubtitle(sentence.carSubtitle)
                        .setIconUri(ArtProvider.badgeUri(i + 1))
                        .build(),
                    i.toLong(),
                )
            })
            notifyChildrenChanged(ROOT)
        }
    }

    companion object {
        const val ROOT = "root"
        const val PASSAGE_PREFIX = "passage:"
        const val SENTENCE_PREFIX = "sentence:"

        /** Cycles the reading order from the car player. */
        const val ACTION_SPEAK_MODE = "com.xingang.ttsreader.SPEAK_MODE"

        /** Toggles sequential / random order from the car player. */
        const val ACTION_SHUFFLE = "com.xingang.ttsreader.SHUFFLE"

        /** Session token for the foreground notification's MediaStyle. */
        @Volatile var token: MediaSessionCompat.Token? = null
    }
}

/** Car rows lead with the word and its gloss; the example sentence follows beneath. */
private val Sentence.carTitle: String get() = note ?: text
private val Sentence.carSubtitle: String get() = if (note != null) text else ""
