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

## License - GPLv2

FFmpeg provides a function to get the license of the library; it returns GPLv2 on my
system. This library is thus transitively licensed under GPLv2.
