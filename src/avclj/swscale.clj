(ns avclj.swscale
  (:require [tech.v3.datatype.ffi :as dt-ffi]
            [avclj.avcodec :as avcodec]))


(dt-ffi/define-library!
  lib
  '{:swscale_version {:rettype :int32
                      :doc "Get library version"}
    :swscale_license {:rettype :string
                      :doc "Get library license"}
    :swscale_configuration {:rettype :string
                            :doc "Get libswscale build time config"}
    :sws_getContext
    {:rettype :pointer
     :argtypes [[srcW :int32]
                [srcH :int32]
                [srcFormat :int32]
                [dstW :int32]
                [dstH :int32]
                [dstFormat :int32]
                [flags :int32]
                [srcFilter :pointer?]
                [dstFilter :pointer?]
                [param :pointer?]]
     :doc
     "/**
 * Allocate and return an SwsContext. You need it to perform
 * scaling/conversion operations using sws_scale().
 *
 * @param srcW the width of the source image
 * @param srcH the height of the source image
 * @param srcFormat the source image format
 * @param dstW the width of the destination image
 * @param dstH the height of the destination image
 * @param dstFormat the destination image format
 * @param flags specify which algorithm and options to use for rescaling
 * @param param extra parameters to tune the used scaler
 *              For SWS_BICUBIC param[0] and [1] tune the shape of the basis
 *              function, param[0] tunes f(1) and param[1] f´(1)
 *              For SWS_GAUSS param[0] tunes the exponent and thus cutoff
 *              frequency
 *              For SWS_LANCZOS param[0] tunes the width of the window function
 * @return a pointer to an allocated context, or NULL in case of error
 * @note this function is to be removed after a saner alternative is
 *       written
 */"}
    :sws_scale
    {:rettype :int32
     :argtypes [[ctx :pointer]
                [srcSlice :pointer]
                [srcStride :pointer]
                [srcSliceY :int32]
                [srcSliceH :int32]
                [dst :pointer]
                [dstStride :pointer]]
     :check-error? true
     :doc "/**
 * Scale the image slice in srcSlice and put the resulting scaled
 * slice in the image in dst. A slice is a sequence of consecutive
 * rows in an image.
 *
 * Slices have to be provided in sequential order, either in
 * top-bottom or bottom-top order. If slices are provided in
 * non-sequential order the behavior of the function is undefined.
 *
 * @param c         the scaling context previously created with
 *                  sws_getContext()
 * @param srcSlice  the array containing the pointers to the planes of
 *                  the source slice
 * @param srcStride the array containing the strides for each plane of
 *                  the source image
 * @param srcSliceY the position in the source image of the slice to
 *                  process, that is the number (counted starting from
 *                  zero) in the image of the first row of the slice
 * @param srcSliceH the height of the source slice, that is the number
 *                  of rows in the slice
 * @param dst       the array containing the pointers to the planes of
 *                  the destination image
 * @param dstStride the array containing the strides for each plane of
 *                  the destination image
 * @return          the height of the output slice
 */"}
    :sws_freeContext {:rettype :void
                      :argtypes [[swsContext :pointer]]}}
  nil
  avcodec/check-error)


(defn set-library-instance!
  [lib-instance]
  (dt-ffi/library-singleton-set-instance! lib lib-instance))


(defn initialize!
  []
  (if (dt-ffi/library-singleton-library lib)
    :already-initialized
    (dt-ffi/library-singleton-set! lib "swscale")))


(def constants
  {"SWS_FAST_BILINEAR"     1
   "SWS_BILINEAR"          2
   "SWS_BICUBIC"           4
   "SWS_X"                 8
   "SWS_POINT"          0x10
   "SWS_AREA"           0x20
   "SWS_BICUBLIN"       0x40
   "SWS_GAUSS"          0x80
   "SWS_SINC"          0x100
   "SWS_LANCZOS"       0x200
   "SWS_SPLINE"        0x400

   "SWS_SRC_V_CHR_DROP_MASK"     0x30000
   "SWS_SRC_V_CHR_DROP_SHIFT"    16

   "SWS_PARAM_DEFAULT"           123456

   "SWS_PRINT_INFO"              0x1000

   ;;the following 3 flags are not completely implemented
   ;;internal chrominance subsampling info
   "SWS_FULL_CHR_H_INT"    0x2000
   ;;input subsampling info
   "SWS_FULL_CHR_H_INP"    0x4000
   "SWS_DIRECT_BGR"        0x8000
   "SWS_ACCURATE_RND"      0x40000
   "SWS_BITEXACT"          0x80000
   "SWS_ERROR_DIFFUSION"  0x800000

   "SWS_MAX_REDUCE_CUTOFF" 0.002

   "SWS_CS_ITU709"         1
   "SWS_CS_FCC"            4
   "SWS_CS_ITU601"         5
   "SWS_CS_ITU624"         5
   "SWS_CS_SMPTE170M"      5
   "SWS_CS_SMPTE240M"      7
   "SWS_CS_DEFAULT"        5
   "SWS_CS_BT2020"         9})


(defmacro define-constants
  []
  `(do
     ~@(->> constants
            (map (fn [[k v]]
                   (let [sym (with-meta (symbol k)
                               {:tag ''long})]
                     `(def ~sym ~v)))))))

(define-constants)
