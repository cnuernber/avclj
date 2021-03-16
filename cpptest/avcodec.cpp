#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <cstdio>
#include <cstddef>

using namespace std;


int main(int c, char** v)
{
  printf(
    "AVCodecContext size: %ld\n"
    "context.flags offset: %ld\n"
    "context.width offset: %ld\n"
    "context.pix-fmt offset: %ld\n"
    "AVPacket size: %ld\n"
    "AVFrame size: %ld\n"
    "AVCodec size: %ld\n"
    "AVFormatContext size: %ld\n"
    "AVStream size: %ld\n",
    sizeof(AVCodecContext),
    offsetof(AVCodecContext,flags),
    offsetof(AVCodecContext,width),
    offsetof(AVCodecContext,pix_fmt),
    sizeof(AVPacket),
    sizeof(AVFrame),
    sizeof(AVCodec),
    sizeof(AVFormatContext),
    sizeof(AVStream));
  return 0;
}
