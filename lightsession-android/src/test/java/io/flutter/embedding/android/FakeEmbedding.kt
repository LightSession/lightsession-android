package io.flutter.embedding.android

/**
 * Stand-ins named like the Flutter embedding's own classes, for the test that tells a Flutter
 * screen from a React Native one by class name. The SDK does not depend on Flutter, so the names are
 * all it has to go on, and a class in this package is the only way to give a test one.
 */
open class FlutterActivity

open class FlutterFragmentActivity

open class FlutterView
