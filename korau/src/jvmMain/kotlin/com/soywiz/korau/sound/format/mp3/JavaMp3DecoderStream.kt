package com.soywiz.korau.sound.format.mp3

import com.soywiz.kds.*
import com.soywiz.kmem.*
import com.soywiz.korau.sound.*
import com.soywiz.korau.sound.impl.awt.*
import fr.delthas.javamp3.*
import java.io.*

fun JavaMp3DecoderToAudioData(data: ByteArray): AudioData {
    val shorts = ShortArrayDeque()
    val data = JavaMp3Decoder.init(data.inputStream()) ?: error("Not an mp3 file")
    val samples = ShortArray(data.samplesBuffer.size / 2)
    while (JavaMp3Decoder.decodeFrame(data)) {
        for (n in samples.indices) samples[n] = data.samplesBuffer.readU16LE(n * 2).toShort()
        shorts.write(samples)
    }

    val samples2 = AudioSamplesInterleaved(data.nchannels, shorts.availableRead)
    shorts.read(samples2.data)

    println("${data.nchannels}, ${shorts.availableRead}")
    return AudioData(data.frequency, samples2.separated())
}

/*
suspend fun createJavaMp3DecoderStream(): JavaMp3DecoderStream {
    val iss: InputStream
    val data = JavaMp3Decoder.init(iss)
    JavaMp3Decoder.decodeFrame(data)
    decoder.
}

class JavaMp3DecoderStream : AudioStream(0, 0) {
    override suspend fun read(out: AudioSamples, offset: Int, length: Int): Int {
        return super.read(out, offset, length)
    }
}
*/
