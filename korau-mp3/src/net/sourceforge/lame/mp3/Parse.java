/*
 *      Command line parsing related functions
 *
 *      Copyright (c) 1999 Mark Taylor
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

/* $Id: Parse.java,v 1.33 2012/03/23 10:02:29 kenchis Exp $ */

package net.sourceforge.lame.mp3;

import java.nio.ByteOrder;

public class Parse {
    public boolean swapbytes = false;
    //public int silent;
    public boolean in_signed = true;
    public ByteOrder in_endian = ByteOrder.LITTLE_ENDIAN;
    public int in_bitwidth = 16;
    private GetAudio.SoundFileFormat inputFormat = GetAudio.SoundFileFormat.sf_unknown;
    private MP3Data mp3InputData = new MP3Data();

    public GetAudio.SoundFileFormat getInputFormat() {
        return inputFormat;
    }

    public void setInputFormat(GetAudio.SoundFileFormat inputFormat) {
        this.inputFormat = inputFormat;
    }

    public MP3Data getMp3InputData() {
        return mp3InputData;
    }
}
