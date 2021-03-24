# FFmpeg (libavcodec) Bindings for Clojure

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

I used the dtype-next activate-graal script to activate graal native.  I then went into
native_test/avclj/main.clj and ran the code to generate the static classes necessary in
order to bind into the libavcodec pathways.  You will need:

* libavcodec-dev
* libavformat-dev
* libswscale-dev
* libavutil-dev
* libx264-dev


Then, after checking under generated_classes to be sure the class generation mechanism
worked, simply run `scripts/compile`.

* [main.clj](https://github.com/cnuernber/avclj/blob/01685a4f0286bd7c39a0decf8e5a69d2a897d835/native_test/avclj/main.clj)


### Graal Native Shared Library

This is using an experimental dtype-next API to export a set of functions from the uberjar to C.
1. Same setup as above -- you have to go into main.clj to generate the library bindings -- but in 
addition go under 'native_test/avclj/libavclj.clj and run the code in the comment block that 
shows how to export functions to a graalvm library.
2.  Then run `scripts/compile-shared` to get a shared library written to the 'library' directory.  
3.  Then in the 'library' directory, there is a script to compile a c++ executable against the shared library.  It encodes 300 frames or 
so.  

One key lesson learned here is that your library export file cannot have any 'def' or 
'defonce' members; these will not get initialized.  Persistent state is recorded in a library init file 
referenced from the main class of the uberjar so that the graal native system will initialized it.
Your export file really needs to have only non-typehinted global functions referencing 
state that your main function in your jarfile also references.

* [library export file](https://github.com/cnuernber/avclj/blob/master/native_test/avclj/libavclj.clj)
* [cpp encoder using referenced functions](https://github.com/cnuernber/avclj/blob/master/library/testencode.cpp)


## Extra Information
 
* [Understanding Rate Control](https://slhck.info/video/2017/03/01/rate-control.html)
* [FFmpeg H264 Encoding](https://trac.ffmpeg.org/wiki/Encode/H.264)

## License - GPLv2

FFmpeg provides a function to get the license of the library; it returns GPLv2 on my
system. This library is thus transitively licensed under GPLv2.
