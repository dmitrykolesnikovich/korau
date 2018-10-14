package com.soywiz.korau

import com.soywiz.korau.format.*
import com.soywiz.korio.async.*
import com.soywiz.korio.file.std.*
import kotlin.test.*

class KorauTest {
	val formats = AudioFormats().registerStandard()

	@Test
	fun name(): Unit = suspendTest {
		val sound = MyResourcesVfs["wav1.wav"].readAudioData(formats)
		//sleep(0)
		//sound.play()
	}
}