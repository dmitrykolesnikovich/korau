/* -*-mode:java; c-basic-offset:2; indent-tabs-mode:nil -*- */
/* JOrbis
 * Copyright (C) 2000 ymnk, JCraft,Inc.
 *  
 * Written by: 2000 ymnk<ymnk@jcraft.com>
 *   
 * Many thanks to 
 *   Monty <monty@xiph.org> and 
 *   The XIPHOPHORUS Company http://www.xiph.org/ .
 * JOrbis has been based on their awesome works, Vorbis codec.
 *   
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public License
 * as published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
   
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 * 
 * You should have received a copy of the GNU Library General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package com.soywiz.korau.format.com.jcraft.jogg

import com.soywiz.kmem.*

class Page {
	var header_base: ByteArray = byteArrayOf()
	var header: Int = 0
	var header_len: Int = 0
	var body_base: ByteArray = byteArrayOf()
	var body: Int = 0
	var body_len: Int = 0

	fun version(): Int = header_base[header + 4].toInt() and 0xff
	fun continued(): Int = header_base[header + 5].toInt() and 0x01
	fun bos(): Int = header_base[header + 5].toInt() and 0x02
	fun eos(): Int = header_base[header + 5].toInt() and 0x04

	fun granulepos(): Long {
		var foo = (header_base[header + 13].unsigned).toLong()
		foo = foo shl 8 or (header_base[header + 12].unsigned.toLong())
		foo = foo shl 8 or (header_base[header + 11].unsigned.toLong())
		foo = foo shl 8 or (header_base[header + 10].unsigned.toLong())
		foo = foo shl 8 or (header_base[header + 9].unsigned.toLong())
		foo = foo shl 8 or (header_base[header + 8].unsigned.toLong())
		foo = foo shl 8 or (header_base[header + 7].unsigned.toLong())
		foo = foo shl 8 or (header_base[header + 6].unsigned.toLong())
		return foo
	}

	fun serialno(): Int {
		return header_base[header + 14].unsigned or (header_base[header + 15].unsigned shl 8) or (header_base[header + 16].unsigned shl 16) or (header_base[header + 17].unsigned shl 24)
	}

	fun pageno(): Int {
		return header_base[header + 18].unsigned or (header_base[header + 19].unsigned shl 8) or (header_base[header + 20].unsigned shl 16) or (header_base[header + 21].unsigned shl 24)
	}

	fun checksum() {
		var crc_reg = 0

		for (i in 0 until header_len) {
			crc_reg = crc_reg shl 8 xor crc_lookup[crc_reg.ushr(24) and 0xff xor (header_base[header + i].unsigned)]
		}
		for (i in 0 until body_len) {
			crc_reg = crc_reg shl 8 xor crc_lookup[crc_reg.ushr(24) and 0xff xor (body_base[body + i].unsigned)]
		}
		header_base[header + 22] = crc_reg.toByte()
		header_base[header + 23] = crc_reg.ushr(8).toByte()
		header_base[header + 24] = crc_reg.ushr(16).toByte()
		header_base[header + 25] = crc_reg.ushr(24).toByte()
	}

	fun copy(p: Page = Page()): Page {
		var tmp = ByteArray(header_len)
		arraycopy(header_base, header, tmp, 0, header_len)
		p.header_len = header_len
		p.header_base = tmp
		p.header = 0
		tmp = ByteArray(body_len)
		arraycopy(body_base, body, tmp, 0, body_len)
		p.body_len = body_len
		p.body_base = tmp
		p.body = 0
		return p
	}

	companion object {
		private val crc_lookup = IntArray(256) {
			var r = it shl 24
			for (i in 0..7) {
				r = if (r and 0x80000000.toInt() != 0) {
					r shl 1 xor 0x04c11db7
				} else {
					r shl 1
				}
			}
			r and 0xffffffff.toInt()
		}
	}
}
