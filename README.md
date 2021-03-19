# FFmpeg (libavcodec) Bindings for Clojure


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

* [this is how I did it](https://github.com/cnuernber/avclj/blob/01685a4f0286bd7c39a0decf8e5a69d2a897d835/native_test/avclj/main.clj)


## Extra Information
 
* [Understanding Rate Control](https://slhck.info/video/2017/03/01/rate-control.html)

## License - GPLv2

FFmpeg provides a function to get the license of the library; it returns GPLv2 on my
system. This library is thus transitively licensed under GPLv2.
