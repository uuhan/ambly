# Ambly

A [ClojureScript](https://github.com/clojure/clojurescript) REPL into embedded JavaScriptCore on iOS, macOS, and tvOS.

Ambly is a REPL designed for use when developing hybrid ClojureScript / native apps.

Ambly comprises a ClojureScript REPL implementation, along with Objective-C code interfacing JavaScriptCore.

An iOS, macOS and tvOS demo apps are included, making it easy to give the REPL a spin.

```
pod "Ambly", "~> 1.4.0"
```

[![Clojars Project](http://clojars.org/ambly/latest-version.svg)](http://clojars.org/ambly)

## Running

### Prerequisites

You must have Xcode installed as well as support for [CocoaPods](http://cocoapods.org). 
You must have Java 8 or later installed along with the [Clojure CLI tools](https://clojure.org/guides/deps_and_cli).

### Demo iOS and tvOS Apps

In `ambly/ObjectiveC/Ambly Demo` run `pod install`.

Open `Ambly Demo.xcworkspace` in Xcode and run the app in the simulator or on a device.

### Demo macOS App

You can either build the `Ambly Demo CLI` project and install it, or download a prebuilt macOS binary from http://ambly.fikesfarm.com

### REPL

You can start the Ambly REPL by supplying `-re ambly` as an option to `cljs.main`.

Here is a sample REPL startup sequence, illustrating device auto-discovery:

```
$ clj -m cljs.main -re ambly 

[1] Ambly Demo on iPod touch
[2] Ambly Demo on iPad
[3] Ambly Demo on iPhone Simulator (My-Mac-Pro)
[4] Ambly Demo TV on Apple TV

[R] Refresh

Choice: 1

Connecting to Ambly Demo on iPod touch ...

To quit, type: :cljs/quit
cljs.user=> (+ 3 4)
7
```

> Note: See [Connectivity](https://github.com/mfikes/ambly/wiki/Connectivity) for details, should any networking difficulty arise.

### REPL Options

#### :choose-first-discovered

Determines whether the Ambly will attempt to automatically connect the first device discovered. Defaults to `false`.

Example:

```
clj -m cljs.main -ro '{:choose-first-discovered true}' -re ambly -r
```

## App Integration

See [Integrating Ambly into Your App](https://github.com/mfikes/ambly/wiki/Integrating-Ambly-into-Your-App) for details.

An example using Ambly to drive Ejecta is at [ClojureScript Ejecta](http://blog.fikesfarm.com/posts/2017-04-29-clojurescript-ejecta.html).

Source for an example iOS app that makes use of Ambly is [Shrimp](https://github.com/mfikes/shrimp).

## License

Copyright © 2015–2018 Mike Fikes and Contributors

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
