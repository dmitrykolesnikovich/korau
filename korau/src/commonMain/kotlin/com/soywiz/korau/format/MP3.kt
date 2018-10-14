@file:Suppress("EXPERIMENTAL_FEATURE_WARNING")

package com.soywiz.korau.format

import com.soywiz.kmem.*
import com.soywiz.korio.error.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.stream.*
import com.soywiz.korio.util.*

object MP3 : MP3Base()

open class MP3Base : AudioFormat("mp3") {
	override suspend fun tryReadInfo(data: AsyncStream): Info? = try {
		val parser = Parser(data)
		val duration = parser.getDurationEstimate()
		//val duration = parser.getDurationExact()
		Info(duration, parser.info?.channelMode?.channels ?: 2)
	} catch (e: Throwable) {
		null
	}

	class Parser(val data: AsyncStream) {
		var info: Mp3Info? = null

		//Read first mp3 frame only...  bind for CBR constant bit rate MP3s
		suspend fun getDurationEstimate() = _getDuration(use_cbr_estimate = true)

		suspend fun getDurationExact() = _getDuration(use_cbr_estimate = false)

		//Read entire file, frame by frame... ie: Variable Bit Rate (VBR)
		private suspend fun _getDuration(use_cbr_estimate: Boolean): Long {
			data.position = 0
			val fd = data.duplicate()

			var duration = 0L
			val offset = this.skipID3v2Tag(fd.readStream(100))
			fd.position = offset

			var info: Mp3Info? = null

			while (!fd.eof()) {
				val block2 = fd.readBytesUpTo(10)
				if (block2.size < 10) break

				if (block2.getu(0) == 0xFF && ((block2.getu(1) and 0xe0) != 0)) {
					info = parseFrameHeader(block2)
					this.info = info
					if (info.frameSize == 0) return duration

					fd.position += info.frameSize - 10
					duration += (info.samples * 1_000_000L) / info.samplingRate
				} else if (block2.openSync().readString(3) == "TAG") {
					fd.position += 128 - 10 //skip over id3v1 tag size
				} else {
					fd.position -= 9
				}

				if ((info != null) && use_cbr_estimate) {
					return estimateDuration(info.bitrate, info.channelMode.channels, offset.toInt())
				}
			}
			return duration
		}

		private suspend fun estimateDuration(bitrate: Int, channels: Int, offset: Int): Long {
			val kbps = (bitrate * 1_000) / 8
			val dataSize = data.getLength() - offset
			return dataSize * (2 / channels) * 1_000_000L / kbps
		}

		private suspend fun skipID3v2Tag(block: AsyncStream): Long {
			val b = block.duplicate()

			if (b.readString(3) == "ID3") {
				val id3v2_major_version = b.readU8()
				val id3v2_minor_version = b.readU8()
				val id3v2_flags = b.readU8()
				val flag_unsynchronisation = id3v2_flags.extract(7)
				val flag_extended_header = id3v2_flags.extract(6)
				val flag_experimental_ind = id3v2_flags.extract(5)
				val flag_footer_present = id3v2_flags.extract(4)
				val z0 = b.readU8();
				val z1 = b.readU8();
				val z2 = b.readU8();
				val z3 = b.readU8()
				if (((z0 and 0x80) == 0) && ((z1 and 0x80) == 0) && ((z2 and 0x80) == 0) && ((z3 and 0x80) == 0)) {
					val header_size = 10
					val tag_size =
						((z0 and 0x7f) * 2097152) + ((z1 and 0x7f) * 16384) + ((z2 and 0x7f) * 128) + (z3 and 0x7f)
					val footer_size = if (flag_footer_present) 10 else 0
					return (header_size + tag_size + footer_size).toLong()//bytes to skip
				}
			}
			return 0L
		}

		companion object {
			enum class ChannelMode(val id: Int, val channels: Int) {
				STEREO(0b00, 2),
				JOINT_STEREO(0b01, 1),
				DUAL_CHANNEL(0b10, 2),
				SINGLE_CHANNEL(0b11, 1);

				companion object {
					val BY_ID = values().map { it.id to it }.toMap()
				}
			}

			val versions = arrayOf("2.5", "x", "2", "1")
			val layers = intArrayOf(-1, 3, 2, 1)

			val bitrates = mapOf(
				"V1L1" to intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
				"V1L2" to intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
				"V1L3" to intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
				"V2L1" to intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
				"V2L2" to intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
				"V2L3" to intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
			)

			val sampleRates = mapOf(
				"1" to intArrayOf(44100, 48000, 32000),
				"2" to intArrayOf(22050, 24000, 16000),
				"2.5" to intArrayOf(11025, 12000, 8000)
			)

			val samples = mapOf(
				1 to mapOf(1 to 384, 2 to 1152, 3 to 1152), // MPEGv1,     Layers 1,2,3
				2 to mapOf(1 to 384, 2 to 1152, 3 to 576)   // MPEGv2/2.5, Layers 1,2,3
			)

			data class Mp3Info(
				val version: String,
				val layer: Int,
				val bitrate: Int,
				val samplingRate: Int,
				val channelMode: ChannelMode,
				val frameSize: Int,
				val samples: Int
			)

			suspend fun parseFrameHeader(f4: ByteArray): Mp3Info {
				val b0 = f4.getu(0);
				val b1 = f4.getu(1);
				val b2 = f4.getu(2);
				val b3 = f4.getu(3)
				if (b0 != 0xFF) invalidOp

				val version = versions[b1.extract(3, 2)]
				val simple_version = if (version == "2.5") 2 else version.toInt()

				val layer = layers[b1.extract(1, 2)]

				val protection_bit = b1.extract(0, 1)
				val bitrate_key = "V%dL%d".format(simple_version, layer)
				val bitrate_idx = b2.extract(4, 4)

				val bitrate = bitrates[bitrate_key]?.get(bitrate_idx) ?: 0
				val sample_rate = sampleRates[version]?.get(b2.extract(2, 2)) ?: 0
				val padding_bit = b2.extract(1, 1)
				val private_bit = b2.extract(0, 1)
				val channelMode = ChannelMode.BY_ID[b3.extract(6, 2)]!!
				val mode_extension_bits = b3.extract(4, 2)
				val copyright_bit = b3.extract(3, 1)
				val original_bit = b3.extract(2, 1)
				val emphasis = b3.extract(0, 2)

				return Mp3Info(
					version = version,
					layer = layer,
					bitrate = bitrate,
					samplingRate = sample_rate,
					channelMode = channelMode,
					frameSize = this.framesize(layer, bitrate, sample_rate, padding_bit),
					samples = samples[simple_version]?.get(layer) ?: 0
				)
			}

			private fun framesize(layer: Int, bitrate: Int, sample_rate: Int, padding_bit: Int): Int {
				return if (layer == 1) {
					((12 * bitrate * 1000 / sample_rate) + padding_bit) * 4
				} else {
					//layer 2, 3
					((144 * bitrate * 1000) / sample_rate) + padding_bit
				}
			}
		}
	}
}
