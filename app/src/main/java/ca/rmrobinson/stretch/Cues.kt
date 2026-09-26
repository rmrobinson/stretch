package ca.rmrobinson.stretch

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator

/** Beeps via ToneGenerator (no asset files). Ducks other audio while a routine is active. */
class Cues(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tone: ToneGenerator? = null
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    fun start() {
        am.requestAudioFocus(focus)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    }

    fun tick() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80) }

    fun done() { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 400) }

    fun stop() {
        tone?.release(); tone = null
        am.abandonAudioFocusRequest(focus)
    }
}
