# Third-party software

- [FFmpegKit maintained](https://github.com/ffmpegkit-maintained/ffmpeg) provides the Android FFmpeg runtime. The version pinned in Gradle is 8.1.9 and the full non-GPL variant uses LGPL-3.0. Its license and component notices are included in app assets. The upstream repository provides the corresponding source and build information.
- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) 0.18.1 provides the Android launcher, yt-dlp extraction, and the standalone FFmpeg executable used by the full-file import path. See the upstream project for component licenses and source.
- [Jetpack Compose](https://developer.android.com/develop/ui/compose) and [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) are distributed under Apache-2.0.
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp/tree/v1.9.5) (MIT) provides offline transcription. The APK bundles the verified multilingual [Whisper tiny model](https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-tiny.bin).
- [YAMNet](https://github.com/tensorflow/models/tree/master/research/audioset/yamnet) and the bundled [ONNX conversion](https://huggingface.co/zeropointnine/yamnet-onnx) are distributed under Apache-2.0. [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT) runs the model.
- [ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection/android) provides on-device face detection.
