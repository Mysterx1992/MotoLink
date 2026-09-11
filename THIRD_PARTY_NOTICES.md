# Third-Party Notices

## OpenDash / KTM-Nav-GEN3 maneuver classifier

MotoLink V1.6 vc16 uses the maneuver-classification approach and trained TensorFlow Lite model from `Pavanayi1/KTM-Nav-GEN3` / OpenDash solely to classify Google Maps maneuver glyphs. The vc16 integration uses the classifier only to recover VOGE roundabout exit sectors; MotoLink's VOGE protocol implementation is independent.

Source pinned for vc16:

- repository: `Pavanayi1/KTM-Nav-GEN3`
- commit: `ceafaf1fa899cdbb646051f1f517372eb21759fa`
- model Git blob: `e0104c6dcd104f05ee3fe08eb9922c036d60c9da`
- model path: `app/src/main/res/raw/maneuver_model.tflite`

MIT License

Copyright (c) 2026 OpenDash contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## TensorFlow Lite

MotoLink vc16 depends on `org.tensorflow:tensorflow-lite:2.14.0`, distributed under the Apache License 2.0.
