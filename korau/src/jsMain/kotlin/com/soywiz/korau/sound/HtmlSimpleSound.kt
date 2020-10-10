package com.soywiz.korau.sound

import com.soywiz.klock.*
import com.soywiz.korio.async.*
import com.soywiz.korio.file.std.*
import com.soywiz.korio.lang.*
import kotlinx.coroutines.*
import org.khronos.webgl.*
import org.w3c.dom.*
import org.w3c.dom.events.*
import kotlinx.browser.*
import kotlin.coroutines.*

class MediaElementAudioSourceNodeWithAudioElement(
	val node: MediaElementAudioSourceNode,
	val audio: HTMLAudioElement
)

object HtmlSimpleSound {
	val ctx: BaseAudioContext? = try {
		when {
			jsTypeOf(window.asDynamic().AudioContext) != "undefined" -> AudioContext()
			jsTypeOf(window.asDynamic().webkitAudioContext) != "undefined" -> webkitAudioContext()
			else -> null
		}.also {
            (window.asDynamic()).globalAudioContext = it
        }
	} catch (e: Throwable) {
		console.error(e)
		null
	}

	val available get() = ctx != null
	var unlocked = false
	private val unlockDeferred = CompletableDeferred<Unit>(Job())
	val unlock = unlockDeferred as Deferred<Unit>

	class SimpleSoundChannel(
		val buffer: AudioBuffer,
		val ctx: BaseAudioContext,
        val params: PlaybackParameters,
        val coroutineContext: CoroutineContext
	) {
        var gainNode: GainNode? = null
        var pannerNode: PannerNode? = null
        var sourceNode: AudioBufferSourceNode? = null

        fun createNode(startTime: TimeSpan) {
            ctx.destination.apply {
                pannerNode = panner {
                    gainNode = gain {
                        this.gain.value = 1.0
                        sourceNode = source(buffer) {
                            //start(0.0)
                        }
                    }
                }
            }
            sourceNode?.start(0.0, startTime.seconds)
        }

        var startedAt = DateTime.now()
        var times = params.times

        fun createJobAt(startTime: TimeSpan): Job {
            startedAt = DateTime.now()
            var startTime = startTime
            ctx?.resume()
            return CoroutineScope(coroutineContext).launchImmediately {
                try {
                    while (times.hasMore) {
                        //println("TIMES: $times, startTime=$startTime, buffer.duration.seconds=${buffer.duration.seconds}")
                        startedAt = DateTime.now()
                        createNode(startTime)
                        startTime = 0.seconds
                        val deferred = CompletableDeferred<Unit>()
                        //println("sourceNode: $sourceNode, ctx?.state=${ctx?.state}, buffer.duration=${buffer.duration}")
                        if (sourceNode == null || ctx?.state != "running") {
                            window.setTimeout({ deferred.complete(Unit) }, (buffer.duration * 1000).toInt())
                        } else {
                            sourceNode?.onended = {
                                deferred.complete(Unit)
                            }
                        }
                        times = times.oneLess
                        //println("awaiting sound")
                        deferred.await()
                        //println("sound awaited")
                        if (!times.hasMore) break
                    }
                } finally {
                    //println("sound completed")

                    running = false
                    sourceNode?.stop()
                    gainNode = null
                    pannerNode = null
                    sourceNode = null
                }
            }
        }

        var job = createJobAt(params.startTime)

		var currentTime: TimeSpan
            get() = DateTime.now() - startedAt
            set(value) = run {
                job.cancel()
                job = createJobAt(value)
            }
        var volume: Double = 1.0
            set(value) = run {
                field = value
                gainNode?.gain?.value = value
            }
        var pitch: Double = 1.0
            set(value) = run {
                field = value
            }
        var panning: Double = 0.0
            set(value) = run {
                pannerNode?.setPosition(panning, 0.0, 0.0)
                field = value
            }

		private var running = true
		//val playing get() = running && currentTime < buffer.duration
        val playing get() = running.also {
            //println("playing: $running")
        }

		fun stop() {
            job.cancel()
		}
	}

	fun AudioNode.panner(callback: PannerNode.() -> Unit = {}): PannerNode? {
		val ctx = ctx ?: return null
		val node = kotlin.runCatching { ctx.createPanner() }.getOrNull() ?: return null
		callback(node)
		node.connect(this)
		return node
	}

	fun AudioNode.gain(callback: GainNode.() -> Unit = {}): GainNode? {
		val ctx = ctx ?: return null
		val node = ctx.createGain()
		callback(node)
		node.connect(this)
		return node
	}

	fun AudioNode.source(buffer: AudioBuffer, callback: AudioBufferSourceNode.() -> Unit = {}): AudioBufferSourceNode? {
		val ctx = ctx ?: return null
		val node = ctx.createBufferSource()
		node.buffer = buffer
		callback(node)
		node.connect(this)
		return node
	}

	fun playSound(buffer: AudioBuffer, params: PlaybackParameters, coroutineContext: CoroutineContext): SimpleSoundChannel? {
        //println("playSound[1]")
        return ctx?.let {
            //println("playSound[2]")
            SimpleSoundChannel(buffer, it, params, coroutineContext)
        }
    }

	fun stopSound(channel: AudioBufferSourceNode?) {
		channel?.disconnect(0)
		channel?.stop(0.0)
	}

	suspend fun waitUnlocked(): BaseAudioContext? {
		unlock.await()
		return ctx
	}

	fun callOnUnlocked(callback: (Unit) -> Unit): Cancellable {
		var cancelled = false
		unlock.invokeOnCompletion { if (!cancelled) callback(Unit) }
		return Cancellable { cancelled = true }
	}

	suspend fun loadSound(data: ArrayBuffer, url: String): AudioBuffer? {
		if (ctx == null) return null
		return suspendCoroutine<AudioBuffer> { c ->
			ctx.decodeAudioData(
				data,
				{ data -> c.resume(data) },
				{ c.resumeWithException(Exception("error decoding $url")) }
			)
		}
	}

	suspend fun loadSoundBuffer(url: String): MediaElementAudioSourceNodeWithAudioElement? {
		if (ctx == null) return null
		val audioPool = document.createElement("audio").unsafeCast<HTMLAudioElement>()
		audioPool.currentTime = 0.0
		audioPool.pause()
		audioPool.autoplay = false
		audioPool.src = url
		return MediaElementAudioSourceNodeWithAudioElement(ctx.createMediaElementSource(audioPool), audioPool)
	}

	suspend fun playSoundBuffer(buffer: MediaElementAudioSourceNodeWithAudioElement?) {
		if (ctx != null) {
			buffer?.audio?.play()
			buffer?.node?.connect(ctx.destination)
		}
	}

	suspend fun stopSoundBuffer(buffer: MediaElementAudioSourceNodeWithAudioElement?) {
		if (ctx != null) {
			buffer?.audio?.pause()
			buffer?.audio?.currentTime = 0.0
			buffer?.node?.disconnect(ctx.destination)
		}
	}

	suspend fun loadSound(data: ByteArray): AudioBuffer? = loadSound(data.unsafeCast<Int8Array>().buffer, "ByteArray")

	suspend fun loadSound(url: String): AudioBuffer? = loadSound(url.uniVfs.readBytes())

	init {
		val _scratchBuffer = ctx?.createBuffer(1, 1, 22050)
		lateinit var unlock: (e: Event) -> Unit
		unlock = {
			if (ctx != null) {
                // If already created the audio context, we try to resume it
                (window.asDynamic()).globalAudioContext.unsafeCast<BaseAudioContext?>()?.resume()

                val source = ctx.createBufferSource()

				source.buffer = _scratchBuffer
				source.connect(ctx.destination)
				source.start(0.0)
				if (jsTypeOf(ctx.asDynamic().resume) === "function") ctx.asDynamic().resume()
				source.onended = {
					source.disconnect(0)

					// Remove the touch start listener.
					document.removeEventListener("keydown", unlock, true)
					document.removeEventListener("touchstart", unlock, true)
					document.removeEventListener("touchend", unlock, true)
					document.removeEventListener("mousedown", unlock, true)

					unlocked = true
					unlockDeferred.complete(Unit)
				}
			}
		}

		document.addEventListener("keydown", unlock, true)
		document.addEventListener("touchstart", unlock, true)
		document.addEventListener("touchend", unlock, true)
		document.addEventListener("mousedown", unlock, true)
	}
}

external interface AudioParam {
	val defaultValue: Double
	val minValue: Double
	val maxValue: Double
	var value: Double
}

external interface GainNode : AudioNode {
	val gain: AudioParam
}

external interface StereoPannerNode : AudioNode {
	val pan: AudioParam
}

external interface PannerNode : AudioNode {
    fun setPosition(x: Double, y: Double, z: Double)
    fun setOrientation(x: Double, y: Double, z: Double)
}

open external class BaseAudioContext {
	fun createScriptProcessor(
		bufferSize: Int,
		numberOfInputChannels: Int,
		numberOfOutputChannels: Int
	): ScriptProcessorNode

	fun decodeAudioData(ab: ArrayBuffer, successCallback: (AudioBuffer) -> Unit, errorCallback: () -> Unit): Unit

	fun createMediaElementSource(audio: HTMLAudioElement): MediaElementAudioSourceNode
	fun createBufferSource(): AudioBufferSourceNode
	fun createGain(): GainNode
    fun createPanner(): PannerNode
	fun createStereoPanner(): StereoPannerNode
	fun createBuffer(numOfchannels: Int, length: Int, rate: Int): AudioBuffer

	var currentTime: Double
	//var listener: AudioListener
	var sampleRate: Double
	var state: String // suspended, running, closed
	val destination: AudioDestinationNode

    fun resume()
    fun suspend()
}

external class AudioContext : BaseAudioContext
external class webkitAudioContext : BaseAudioContext

external interface MediaElementAudioSourceNode : AudioNode {

}

external interface AudioScheduledSourceNode : AudioNode {
	var onended: () -> Unit
	fun start(whn: Double = definedExternally, offset: Double = definedExternally, duration: Double = definedExternally)
	fun stop(whn: Double = definedExternally)
}

external interface AudioBufferSourceNode : AudioScheduledSourceNode {
	var buffer: AudioBuffer?
	var detune: Int
	var loop: Boolean
	var loopEnd: Double
	var loopStart: Double
	var playbackRate: Double
}

external interface AudioBuffer {
	val duration: Double
	val length: Int
	val numberOfChannels: Int
	val sampleRate: Int
	fun copyFromChannel(destination: Float32Array, channelNumber: Int, startInChannel: Double?): Unit
	fun copyToChannel(source: Float32Array, channelNumber: Int, startInChannel: Double?): Unit
	//fun getChannelData(channel: Int): Float32Array
    fun getChannelData(channel: Int): FloatArray
}

external interface AudioNode {
	val channelCount: Int
	//val channelCountMode: ChannelCountMode
	//val channelInterpretation: ChannelInterpretation
	val context: AudioContext
	val numberOfInputs: Int
	val numberOfOutputs: Int
	fun connect(destination: AudioNode, output: Int? = definedExternally, input: Int? = definedExternally): AudioNode
	//fun connect(destination: AudioParam, output: Int?): Unit
	fun disconnect(output: Int? = definedExternally): Unit

	fun disconnect(destination: AudioNode, output: Int? = definedExternally, input: Int? = definedExternally): Unit
	//fun disconnect(destination: AudioParam, output: Int?): Unit
}

external interface AudioDestinationNode : AudioNode {
	val maxChannelCount: Int
}

external class AudioProcessingEvent : Event {
	val inputBuffer: AudioBuffer
	val outputBuffer: AudioBuffer
	val playbackTime: Double
}

external interface ScriptProcessorNode : AudioNode {
	var onaudioprocess: (AudioProcessingEvent) -> Unit
}
