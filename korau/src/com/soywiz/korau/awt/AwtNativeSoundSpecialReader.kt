package com.soywiz.korau.awt

import com.soywiz.korau.format.readAudioData
import com.soywiz.korau.format.toNativeSound
import com.soywiz.korau.sound.NativeSound
import com.soywiz.korio.vfs.LocalVfs
import com.soywiz.korio.vfs.Vfs
import com.soywiz.korio.vfs.VfsSpecialReader

class AwtNativeSoundSpecialReader : VfsSpecialReader<NativeSound>(NativeSound::class.java) {
	suspend override fun readSpecial(vfs: Vfs, path: String): NativeSound = when (vfs) {
		is LocalVfs -> vfs[path].readAudioData().toNativeSound()
		else -> vfs[path].readAudioData().toNativeSound()
	}
}