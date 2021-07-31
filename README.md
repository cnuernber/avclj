# FFmpeg (libavcodec) Bindings for Clojure

[![Clojars Project](https://img.shields.io/clojars/v/com.cnuernber/avclj.svg)](https://clojars.org/com.cnuernber/avclj)

* [API Documentation](https://cnuernber.github.io/avclj/)


This system uses the [ffi architecture](https://cnuernber.github.io/dtype-next/tech.v3.datatype.ffi.html) of dtype-next in order to build 
bindings to JNA, JDK-16 and Graal Native.


## Usage

You must have libavcodec installed to use this library; it was already on my machine.

```console
libavcodec58 - FFmpeg library with de/encoders for audio/video codecs - runtime files
```

```clojure
   {cnuernber/avclj {:git/url "https://github.com/cnuernber/avclj"
                     :sha "HEAD"}}
```

* [example encoder](test/avclj_test.clj)
* [API documentation](https://cnuernber.github.io/avclj/)

## Development

* `clj -X:codox` - regenerate documentation
* `clj -M:test` - run the unit tests


## Graal Native

I used the dtype-next activate-graal script to activate graal native:

```console
(base) chrisn@chrisn-lt3:~/dev/cnuernber/dtype-next$ source scripts/activate-graal
(base) chrisn@chrisn-lt3:~/dev/cnuernber/dtype-next$ java --version
openjdk 11.0.12 2021-07-20
OpenJDK Runtime Environment GraalVM CE 21.2.0 (build 11.0.12+6-jvmci-21.2-b08)
OpenJDK 64-Bit Server VM GraalVM CE 21.2.0 (build 11.0.12+6-jvmci-21.2-b08, mixed mode, sharing)
```

I then went into
`native_test/avclj/gen_bindings.clj` and ran the code to generate the static classes necessary in
order to bind into the libavcodec pathways.  You will need these system library packages:

* libavcodec-dev
* libavformat-dev
* libswscale-dev
* libavutil-dev
* libx264-dev


Then, after checking under generated_classes to be sure the class generation mechanism
worked, simply run `scripts/compile`.

* [gen_bindings.clj](native_test/avclj/main.clj)
* [main.clj](native_test/avclj/main.clj)


### Graal Native Shared Library

This is using an experimental dtype-next API to export a set of functions from the uberjar to C.
1. Same setup as above -- `gen_bindings` generates the bindings for the shared library.
2.  Then run `scripts/compile-shared` to get a shared library written to the 'library' directory.  
3.  Then in the 'library' directory, there is a script to compile a c++ executable against the shared library.  It encodes 300 frames or 
so - then decodes those frames.

One key lesson learned here is that your library export must only reference state that is also
referenced from the main class of the uberjar and the exported `defn`'s cannot have typehints.

* [library export file](native_test/avclj/libavclj.clj)
* [cpp encoder using referenced functions](library/testencode.cpp)


## Extra Information
 
* [Understanding Rate Control](https://slhck.info/video/2017/03/01/rate-control.html)
* [FFmpeg H264 Encoding](https://trac.ffmpeg.org/wiki/Encode/H.264)

## License - GPLv2

FFmpeg provides a function to get the license of the library; it returns GPLv2 on my
system. This library is thus transitively licensed under GPLv2.
