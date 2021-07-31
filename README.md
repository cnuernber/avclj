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

You have to export `LD_LIBRARY_PATH` to the library directory in order to load the shared
library.  But then, *hopefully*, you will get the right output:

```console
(base) chrisn@chrisn-lt3:~/dev/cnuernber/avclj/library$ export LD_LIBRARY_PATH=$(pwd)
(base) chrisn@chrisn-lt3:~/dev/cnuernber/avclj/library$ ./compile.sh
(base) chrisn@chrisn-lt3:~/dev/cnuernber/avclj/library$ ./a.out
initialized? 0
initialized? 1
[libx264 @ 0x55f64aeb92c0] using cpu capabilities: MMX2 SSE2Fast SSSE3 SSE4.2 AVX FMA3 BMI2 AVX2
[libx264 @ 0x55f64aeb92c0] profile High, level 2.1, 4:2:0, 8-bit
got encoder: 521232628
[libx264 @ 0x55f64aeb92c0] frame I:2     Avg QP:13.06  size:  1014
[libx264 @ 0x55f64aeb92c0] frame P:103   Avg QP:25.78  size:    88
[libx264 @ 0x55f64aeb92c0] frame B:195   Avg QP:23.22  size:    87
[libx264 @ 0x55f64aeb92c0] consecutive B-frames:  1.3% 36.0%  0.0% 62.7%
[libx264 @ 0x55f64aeb92c0] mb I  I16..4: 50.2% 25.0% 24.8%
[libx264 @ 0x55f64aeb92c0] mb P  I16..4:  1.2%  1.0%  1.0%  P16..4: 12.8%  0.3%  0.0%  0.0%  0.0%    skip:83.8%
[libx264 @ 0x55f64aeb92c0] mb B  I16..4:  0.3%  0.0%  0.0%  B16..8: 32.9%  1.6%  0.0%  direct: 0.0%  skip:65.2%  L0:39.1% L1:60.9% BI: 0.0%
[libx264 @ 0x55f64aeb92c0] 8x8 transform intra:26.4% inter:47.5%
[libx264 @ 0x55f64aeb92c0] coded y,uvDC,uvAC intra: 6.7% 0.0% 0.0% inter: 0.0% 0.0% 0.0%
[libx264 @ 0x55f64aeb92c0] i16 v,h,dc,p: 51% 35% 13%  0%
[libx264 @ 0x55f64aeb92c0] i8 v,h,dc,ddl,ddr,vr,hd,vl,hu: 33%  7% 60%  0%  0%  0%  0%  0%  0%
[libx264 @ 0x55f64aeb92c0] i4 v,h,dc,ddl,ddr,vr,hd,vl,hu: 39% 15% 45%  0%  0%  0%  0%  1%  0%
[libx264 @ 0x55f64aeb92c0] i8c dc,h,v,p: 100%  0%  0%  0%
[libx264 @ 0x55f64aeb92c0] Weighted P-Frames: Y:0.0% UV:0.0%
[libx264 @ 0x55f64aeb92c0] ref P L0: 89.4%  0.9%  9.7%  0.0%
[libx264 @ 0x55f64aeb92c0] ref B L0: 81.5% 18.1%  0.4%
[libx264 @ 0x55f64aeb92c0] ref B L1: 99.2%  0.8%
[libx264 @ 0x55f64aeb92c0] kb/s:44.84
Encoded 300 frames.  Testing decode to RGB24
Decoding frames 100x100
Jul 31, 2021 8:52:33 AM clojure.tools.logging$eval1$fn__4 invoke
INFO: Reference thread starting
Decoded 300 frames
```


## Extra Information
 
* [Understanding Rate Control](https://slhck.info/video/2017/03/01/rate-control.html)
* [FFmpeg H264 Encoding](https://trac.ffmpeg.org/wiki/Encode/H.264)

## License - GPLv2

FFmpeg provides a function to get the license of the library; it returns GPLv2 on my
system. This library is thus transitively licensed under GPLv2.
