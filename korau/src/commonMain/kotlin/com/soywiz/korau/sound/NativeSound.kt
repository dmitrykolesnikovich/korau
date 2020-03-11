package com.soywiz.korau.sound

import com.soywiz.klock.*
import com.soywiz.korau.error.*
import com.soywiz.korau.format.*
import com.soywiz.korio.async.*
import com.soywiz.korio.file.*
import com.soywiz.korio.util.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

expect val nativeSoundProvider: NativeSoundProvider

open class NativeSoundProvider {
	open val target: String = "unknown"

	private var initialized = false

	open fun initOnce() {
		if (!initialized) {
			initialized = true
			init()
		}
	}

	open fun createAudioStream(freq: Int = 44100): PlatformAudioOutput = PlatformAudioOutput(freq)

	protected open fun init(): Unit = Unit

	open suspend fun createSound(data: ByteArray, streaming: Boolean = false, props: AudioDecodingProps = AudioDecodingProps.DEFAULT): NativeSound = object : NativeSound() {
		override suspend fun decode(): AudioData = AudioData.DUMMY
		override fun play(params: PlaybackParameters): NativeSoundChannel = object : NativeSoundChannel(this) {
			override fun stop() = Unit
		}
	}

    open val audioFormats = AudioFormats(WAV)

    open suspend fun createSound(vfs: Vfs, path: String, streaming: Boolean = false, props: AudioDecodingProps = AudioDecodingProps.DEFAULT): NativeSound {
        return if (streaming) {
            val stream = vfs.file(path).open()
            createStreamingSound(audioFormats.decodeStream(stream, props) ?: error("Can't open sound for streaming")) {
                stream.close()
            }
        } else {
            createSound(vfs.file(path).read(), streaming, props)
        }
    }

	suspend fun createSound(file: FinalVfsFile, streaming: Boolean = false, props: AudioDecodingProps = AudioDecodingProps.DEFAULT): NativeSound = createSound(file.vfs, file.path, streaming, props)
	suspend fun createSound(file: VfsFile, streaming: Boolean = false, props: AudioDecodingProps = AudioDecodingProps.DEFAULT): NativeSound = createSound(file.getUnderlyingUnscapedFile(), streaming, props)

	open suspend fun createSound(
		data: AudioData,
		formats: AudioFormats = defaultAudioFormats,
		streaming: Boolean = false
	): NativeSound {
		return createSound(WAV.encodeToByteArray(data), streaming)
	}

    suspend fun createStreamingSound(stream: AudioStream, closeStream: Boolean = false, onComplete: suspend () -> Unit = {}): NativeSound {
        //println("STREAM.RATE:" + stream.rate)
        //println("STREAM.CHANNELS:" + stream.channels)
        val coroutineContext = coroutineContext
        return object : NativeSound() {
            val nativeSound = this
            override val length: TimeSpan get() = stream.totalLength
            override suspend fun decode(): AudioData = stream.toData()
            override fun play(params: PlaybackParameters): NativeSoundChannel {
                val nas = createAudioStream(stream.rate)
                var playing = true
                val job = launchImmediately(coroutineContext) {
                    val stream = stream.clone()
                    stream.currentTime = params.startTime
                    playing = true
                    //println("STREAM.START")
                    var times = params.times
                    try {
                        val temp = AudioSamples(stream.channels, 1024)
                        val nchannels = 2
                        val minBuf = (stream.rate * nchannels * params.bufferTime.seconds).toInt()
                        nas.start()
                        while (times.hasMore) {
                            times = times.oneLess

                            while (!stream.finished) {
                                //println("STREAM")
                                val read = stream.read(temp, 0, temp.totalSamples)
                                nas.add(temp, 0, read)
                                while (nas.availableSamples in minBuf..minBuf * 2) {
                                    delay(2.milliseconds) // 100ms of buffering, and 1s as much
                                    //println("STREAM.WAIT: ${nas.availableSamples}")
                                }
                            }
                            stream.currentPositionInSamples = 0L
                        }
                    } catch (e: CancellationException) {
                        nas.stop()
                    } finally {
                        //println("STREAM.STOP")
                        if (closeStream) {
                            stream.close()
                        }
                        playing = false
                        onComplete()
                    }
                }
                fun close() {
                    job.cancel()
                }
                return object : NativeSoundChannel(nativeSound) {
                    override var volume: Double by nas::volume.redirected()
                    override var pitch: Double by nas::pitch.redirected()
                    override var panning: Double by nas::panning.redirected()
                    override var current: TimeSpan
                        get() = stream.currentTime
                        set(value) = run { stream.currentTime = value }
                    override val total: TimeSpan get() = stream.totalLength
                    override val playing: Boolean get() = playing
                    override fun stop() = close()
                }
            }
        }
    }

    suspend fun playAndWait(stream: AudioStream, params: PlaybackParameters = PlaybackParameters.DEFAULT) = createStreamingSound(stream).playAndWait(params)
}

class DummyNativeSoundProvider : NativeSoundProvider()

class DummyNativeSoundChannel(sound: NativeSound, val data: AudioData? = null) : NativeSoundChannel(sound) {
	private var timeStart = DateTime.now()
	override var current: TimeSpan
        get() = DateTime.now() - timeStart
        set(value) = Unit
	override val total: TimeSpan get() = data?.totalTime ?: 0.seconds

	override fun stop() {
		timeStart = DateTime.now() + total
	}
}

interface SoundProps {
    var volume: Double
    var pitch: Double
    var panning: Double
}

class NativeSoundChannelGroup(volume: Double = 1.0, pitch: Double = 1.0, panning: Double = 0.0) : NativeSoundChannelBase {
    private val channels = arrayListOf<NativeSoundChannelBase>()

    override val playing: Boolean get() = channels.any { it.playing }

    override var volume: Double = 1.0
        set(value) = run { field = value}.also { all { it.volume = value } }
    override var pitch: Double = 1.0
        set(value) = run { field = value}.also { all { it.pitch = value } }
    override var panning: Double = 0.0
        set(value) = run { field = value}.also { all { it.panning = value } }
    init {
        this.volume = volume
        this.pitch = pitch
        this.panning = panning
    }

    fun add(channel: NativeSoundChannelBase) = run { channels.add(channel) }.also { setProps(channel) }
    fun remove(channel: NativeSoundChannelBase) = run { channels.remove(channel) }

    private fun setProps(channel: NativeSoundChannelBase) {
        channel.volume = this.volume
        channel.pitch = this.pitch
        channel.panning = this.panning
    }

    @PublishedApi
    internal fun prune() = run {  channels.removeAll { !it.playing }  }

    private inline fun all(callback: (NativeSoundChannelBase) -> Unit) = run { for (channel in channels) callback(channel) }.also { prune() }

    override fun reset(): Unit = all { it.reset() }
    override fun stop(): Unit = all { it.stop() }

}

interface NativeSoundChannelBase : SoundProps {
    val playing: Boolean
    fun reset(): Unit
    fun stop(): Unit

    suspend fun await() {
        while (playing) delay(1.milliseconds)
    }
}

fun <T : NativeSoundChannelBase> T.attachTo(group: NativeSoundChannelGroup): T = this.apply { group.add(this) }

abstract class NativeSoundChannel(val sound: NativeSound) : NativeSoundChannelBase {
	private val startTime = DateTime.now()
	override var volume = 1.0
	override var pitch = 1.0
	override var panning = 0.0 // -1.0 left, +1.0 right
    // @TODO: Rename to position
	open var current: TimeSpan
        get() = DateTime.now() - startTime
        set(value) = seekingNotSupported()
	open val total: TimeSpan get() = sound.length
	override val playing: Boolean get() = current < total
    override fun reset(): Unit = TODO()
	abstract override fun stop(): Unit
}

suspend fun NativeSoundChannel.await(progress: NativeSoundChannel.(current: TimeSpan, total: TimeSpan) -> Unit = { current, total -> }) {
	try {
		while (playing) {
			progress(current, total)
			delay(4.milliseconds)
		}
		progress(total, total)
	} catch (e: CancellationException) {
		stop()
	}
}

abstract class NativeSound : SoundProps {
    override var volume: Double = 1.0
    override var panning: Double = 0.0
    override var pitch: Double = 1.0
	open val length: TimeSpan = 0.seconds
	abstract suspend fun decode(): AudioData
	open fun play(params: PlaybackParameters = PlaybackParameters.DEFAULT): NativeSoundChannel = TODO()
    fun play(times: PlaybackTimes, startTime: TimeSpan = 0.seconds): NativeSoundChannel = play(PlaybackParameters(times, startTime))
    fun playForever(startTime: TimeSpan = 0.seconds): NativeSoundChannel = play(infinitePlaybackTimes, startTime)
}

data class PlaybackParameters(
    val times: PlaybackTimes = 1.playbackTimes,
    val startTime: TimeSpan = 0.seconds,
    val bufferTime: TimeSpan = 0.1.seconds
) {
    companion object {
        val DEFAULT = PlaybackParameters(1.playbackTimes, 0.seconds)
    }
}

val infinitePlaybackTimes get() = PlaybackTimes.INFINITE
inline val Int.playbackTimes get() = PlaybackTimes(this)

inline class PlaybackTimes(val count: Int) {
    companion object {
        val ZERO = PlaybackTimes(0)
        val ONE = PlaybackTimes(1)
        val INFINITE = PlaybackTimes(-1)
    }
    val hasMore get() = this != ZERO
    val oneLess get() = if (this == INFINITE) INFINITE else PlaybackTimes(count - 1)
}

suspend fun NativeSound.toData(): AudioData = decode()
suspend fun NativeSound.toStream(): AudioStream = decode().toStream()

suspend fun NativeSound.playAndWait(params: PlaybackParameters, progress: NativeSoundChannel.(current: TimeSpan, total: TimeSpan) -> Unit = { current, total -> }): Unit =
    play(params).await(progress)

suspend fun NativeSound.playAndWait(times: PlaybackTimes = 1.playbackTimes, startTime: TimeSpan = 0.seconds, progress: NativeSoundChannel.(current: TimeSpan, total: TimeSpan) -> Unit = { current, total -> }): Unit =
    play(times, startTime).await(progress)

suspend fun VfsFile.readNativeMusic(props: AudioDecodingProps = AudioDecodingProps.DEFAULT) = readNativeSound(streaming = true, props = props)
suspend fun VfsFile.readNativeSound(streaming: Boolean = false, props: AudioDecodingProps = AudioDecodingProps.DEFAULT) = nativeSoundProvider.createSound(this, streaming, props)

suspend fun ByteArray.readNativeSound(streaming: Boolean = false, props: AudioDecodingProps = AudioDecodingProps.DEFAULT) = nativeSoundProvider.createSound(this, streaming, props)
suspend fun ByteArray.readNativeMusic(props: AudioDecodingProps = AudioDecodingProps.DEFAULT) = readNativeSound(streaming = true, props = props)

@Deprecated("", ReplaceWith("readNativeSound(streaming)"))
suspend fun VfsFile.readNativeSoundOptimized(streaming: Boolean = false, props: AudioDecodingProps = AudioDecodingProps.DEFAULT) = readNativeSound(streaming, props)

