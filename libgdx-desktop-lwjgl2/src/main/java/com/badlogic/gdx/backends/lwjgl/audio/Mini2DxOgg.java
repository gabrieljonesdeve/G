/*******************************************************************************
 * Copyright 2011 See LIBGDX_AUTHORS file.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.badlogic.gdx.backends.lwjgl.audio;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Modified version of {@link Ogg} to support sound completion events
 */
public class Mini2DxOgg extends Ogg {
	
	static public class Music extends Mini2DxOpenALMusic {
		private OggInputStream input;
		private OggInputStream previousInput;

		public Music (Mini2DxOpenALAudio audio, FileHandle file) {
			super(audio, file);
			if (audio.noDevice) return;
			input = new OggInputStream(file.read());
			setup(input.getChannels(), input.getSampleRate());
		}

		public Music (Mini2DxOpenALAudio audio, byte [] bytes) {
			super(audio, bytes);
			if (audio.noDevice) return;
			input = new OggInputStream(new ByteArrayInputStream(bytes));
			setup(input.getChannels(), input.getSampleRate());
		}

		public int read (byte[] buffer) {
			if (input == null) {
				if(file != null) {
					input = new OggInputStream(file.read(), previousInput);
				} else {
					input = new OggInputStream(new ByteArrayInputStream(bytes), previousInput);
				}

				setup(input.getChannels(), input.getSampleRate());
				previousInput = null; // release this reference
			}
			return input.read(buffer);
		}

		public void reset () {
			StreamUtils.closeQuietly(input);
			previousInput = null;
			input = null;
		}

		@Override
		protected void loop () {
			StreamUtils.closeQuietly(input);
			previousInput = input;
			input = null;
		}
	}

	static public class Sound extends Mini2DxOpenALSound {
		public Sound(com.badlogic.gdx.backends.lwjgl.audio.Mini2DxOpenALAudio audio, FileHandle file) {
			this(audio, file.read(), file.path());
		}

		public Sound(Mini2DxOpenALAudio audio, InputStream stream, String fileName) {
			super(audio);
			if (audio.noDevice)
				return;
			OggInputStream input = null;
			try {
				input = new OggInputStream(stream);
				ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
				byte[] buffer = new byte[2048];
				while (!input.atEnd()) {
					int length = input.read(buffer);
					if (length == -1)
						break;
					output.write(buffer, 0, length);
				}
				setup(output.toByteArray(), input.getChannels(), input.getSampleRate());
			} finally {
				StreamUtils.closeQuietly(input);
			}
		}
	}
}
