# iOS third-party notices

## Argmax Open-Source SDK / WhisperKit

- Source: https://github.com/argmaxinc/argmax-oss-swift
- Product: WhisperKit
- License: MIT
- Purpose: On-device Whisper speech-to-text inference using Apple's Core ML stack.
- Model: The app requests the multilingual tiny model at first use from the upstream WhisperKit model repository. Model weights are not vendored in this repository. Verify and preserve the exact model-repository license and notices for any model snapshot or predownloaded model bundle distributed with the app.

The Argmax Open-Source SDK repository includes its MIT license and third-party notices. The package is resolved by Swift Package Manager at version 1.1.1 or later within the selected major version.

## Apple system frameworks

SwiftUI, AVFoundation, AVKit, PhotosUI, UniformTypeIdentifiers, Vision (planned), Core ML (through WhisperKit), URLSession, Core Animation, and VideoToolbox are supplied by Apple platform SDKs. No separate third-party license is required for their use as system frameworks.

## Evaluated but not included

- Kadr (https://github.com/SteliyanH/kadr) — Apache-2.0; evaluated as a possible future multitrack composition/timeline layer but not added as a dependency.
- ONNX Runtime (https://github.com/microsoft/onnxruntime) — MIT; candidate only for future YAMNet execution on iOS.
- YAMNet — Apache-2.0 per the Android project's existing third-party notices; not yet integrated into iOS.
