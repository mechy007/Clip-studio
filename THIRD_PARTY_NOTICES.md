# Third-party software

- [FFmpegKit maintained](https://github.com/ffmpegkit-maintained/ffmpeg) provides the Android FFmpeg runtime. The version pinned in Gradle is 8.1.9 and the full non-GPL variant uses LGPL-3.0. Its license and component notices are included in app assets. The upstream repository provides the corresponding source and build information.
- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) 0.18.1 provides the Android launcher, yt-dlp extraction, and the standalone FFmpeg executable used by the full-file import path. See the upstream project for component licenses and source.
- [Jetpack Compose](https://developer.android.com/develop/ui/compose) and [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) are distributed under Apache-2.0.
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp/tree/v1.9.5) (MIT) provides offline transcription. The APK bundles the verified multilingual [Whisper tiny model](https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-tiny.bin).
- [YAMNet](https://github.com/tensorflow/models/tree/master/research/audioset/yamnet) and the bundled [ONNX conversion](https://huggingface.co/zeropointnine/yamnet-onnx) are distributed under Apache-2.0. [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT) runs the model.
- [ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection/android) provides on-device face detection.

- [AI Video Captions](https://github.com/nicolaigaina/ai-video-captions) (MIT, AutoShorts) supplied the Bounce and Karaoke Wipe animation logic, adapted to Clip Studio’s Kotlin ASS renderer. The upstream MIT license is included in app assets.

YouTube creator skills by Jake Schincariol are bundled under MIT from
Jakeschincariol/youtube-agent-skill at a2feb2104981a375ffd4f87ee04f4f5344ac43c6.
The original license is included in app/src/main/assets/youtube-agent/LICENSE.

## iOS application

- [Argmax Open-Source SDK / WhisperKit](https://github.com/argmaxinc/argmax-oss-swift) (MIT) is included as a Swift Package Manager dependency for on-device Core ML speech transcription. The multilingual tiny model is fetched on first use and is not vendored in this repository. Preserve the upstream MIT license and verify the exact model repository license before distributing predownloaded model weights.
- AVFoundation, AVKit, SwiftUI, PhotosUI, URLSession, Core Animation, and VideoToolbox are Apple system frameworks used by the native iOS app.
- [Kadr](https://github.com/SteliyanH/kadr) (Apache-2.0) and [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT) were evaluated as future iOS options; neither is currently included as an iOS dependency.
