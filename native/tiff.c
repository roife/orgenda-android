#include <jni.h>
#include <tiffio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

typedef struct {
    const unsigned char *bytes;
    size_t length, position;
} ImageSource;
static tmsize_t image_read(thandle_t handle, void *buffer, tmsize_t size) {
    ImageSource *source = (ImageSource *)handle;
    if (size < 0)
        return -1;
    size_t count = (size_t)size;
    if (count > source->length - source->position)
        count = source->length - source->position;
    memcpy(buffer, source->bytes + source->position, count);
    source->position += count;
    return (tmsize_t)count;
}
static tmsize_t image_write(thandle_t handle, void *buffer, tmsize_t size) { return -1; }
static toff_t image_seek(thandle_t handle, toff_t offset, int origin) {
    ImageSource *source = (ImageSource *)handle;
    int64_t delta = (int64_t)offset;
    int64_t base = origin == SEEK_SET   ? 0
                   : origin == SEEK_CUR ? (int64_t)source->position
                   : origin == SEEK_END ? (int64_t)source->length
                                        : -1;
    if (base < 0 || delta < -base || delta > (int64_t)source->length - base)
        return (toff_t)-1;
    source->position = (size_t)(base + delta);
    return (toff_t)source->position;
}
static int image_close(thandle_t handle) { return 0; }
static toff_t image_size(thandle_t handle) { return (toff_t)((ImageSource *)handle)->length; }
static int image_message(TIFF *tiff, void *data, const char *module, const char *format,
                         va_list args) {
    return 1;
}
static jintArray image_failure(JNIEnv *env, const char *message) {
    jclass exception = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (exception)
        (*env)->ThrowNew(env, exception, message);
    return NULL;
}

JNIEXPORT jintArray JNICALL Java_com_roifewu_orgenda_ui_NativeTiff_decode(JNIEnv *env,
                                                                          jobject object,
                                                                          jbyteArray input,
                                                                          jint max_side) {
    jsize length = (*env)->GetArrayLength(env, input);
    if (length > 20 * 1024 * 1024 || max_side < 1 || max_side > 2048)
        return image_failure(env, "Invalid TIFF input");
    jbyte *bytes = (*env)->GetByteArrayElements(env, input, NULL);
    if (!bytes)
        return NULL;
    ImageSource source = {(const unsigned char *)bytes, (size_t)length, 0};
    TIFFOpenOptions *options = TIFFOpenOptionsAlloc();
    if (!options) {
        (*env)->ReleaseByteArrayElements(env, input, bytes, JNI_ABORT);
        return image_failure(env, "TIFF allocation failed");
    }
    TIFFOpenOptionsSetMaxSingleMemAlloc(options, 16 * 1024 * 1024);
    TIFFOpenOptionsSetMaxCumulatedMemAlloc(options, 16 * 1024 * 1024);
    TIFFOpenOptionsSetErrorHandlerExtR(options, image_message, NULL);
    TIFFOpenOptionsSetWarningHandlerExtR(options, image_message, NULL);
    TIFF *tiff = TIFFClientOpenExt("attachment", "r", &source, image_read, image_write, image_seek,
                                   image_close, image_size, NULL, NULL, options);
    TIFFOpenOptionsFree(options);
    uint32_t width = 0, height = 0;
    uint16_t orientation = ORIENTATION_TOPLEFT;
    uint32_t *raster = NULL;
    jint *pixels = NULL;
    jintArray result = NULL;
    const char *failure = "Unable to decode TIFF";
    if (!tiff)
        goto cleanup;
    TIFFGetField(tiff, TIFFTAG_IMAGEWIDTH, &width);
    TIFFGetField(tiff, TIFFTAG_IMAGELENGTH, &height);
    TIFFGetFieldDefaulted(tiff, TIFFTAG_ORIENTATION, &orientation);
    if (!width || !height || (uint64_t)width * height > 16 * 1024 * 1024) {
        failure = "TIFF exceeds the 16 megapixel preview limit";
        goto cleanup;
    }
    raster = malloc((size_t)width * height * sizeof(uint32_t));
    if (!raster) {
        failure = "TIFF allocation failed";
        goto cleanup;
    }
    // Decode stored pixels first, then apply all eight orientations, including
    // portrait rotations that TIFFReadRGBAImageOriented alone does not handle.
    TIFFSetField(tiff, TIFFTAG_ORIENTATION, ORIENTATION_TOPLEFT);
    if (!TIFFReadRGBAImageOriented(tiff, width, height, raster, ORIENTATION_TOPLEFT, 1))
        goto cleanup;
    uint32_t rotated_width = orientation >= 5 && orientation <= 8 ? height : width;
    uint32_t rotated_height = orientation >= 5 && orientation <= 8 ? width : height;
    uint32_t longest = rotated_width > rotated_height ? rotated_width : rotated_height;
    uint32_t sample = (longest + (uint32_t)max_side - 1) / (uint32_t)max_side;
    uint32_t out_width = (rotated_width + sample - 1) / sample;
    uint32_t out_height = (rotated_height + sample - 1) / sample;
    size_t count = (size_t)out_width * out_height + 2;
    pixels = malloc(count * sizeof(jint));
    if (!pixels) {
        failure = "TIFF allocation failed";
        goto cleanup;
    }
    pixels[0] = (jint)out_width;
    pixels[1] = (jint)out_height;
    for (uint32_t y = 0; y < out_height; y++)
        for (uint32_t x = 0; x < out_width; x++) {
            uint32_t rx = x * sample, ry = y * sample, sx = rx, sy = ry;
            switch (orientation) {
            case 2:
                sx = width - 1 - rx;
                break;
            case 3:
                sx = width - 1 - rx;
                sy = height - 1 - ry;
                break;
            case 4:
                sy = height - 1 - ry;
                break;
            case 5:
                sx = ry;
                sy = rx;
                break;
            case 6:
                sx = ry;
                sy = height - 1 - rx;
                break;
            case 7:
                sx = width - 1 - ry;
                sy = height - 1 - rx;
                break;
            case 8:
                sx = width - 1 - ry;
                sy = rx;
                break;
            }
            uint32_t rgba = raster[(size_t)sy * width + sx];
            uint32_t a = TIFFGetA(rgba), r = TIFFGetR(rgba), g = TIFFGetG(rgba), b = TIFFGetB(rgba);
            if (a > 0 && a < 255) {
                r = r * 255 / a;
                g = g * 255 / a;
                b = b * 255 / a;
            }
            if (r > 255)
                r = 255;
            if (g > 255)
                g = 255;
            if (b > 255)
                b = 255;
            pixels[2 + (size_t)y * out_width + x] = (jint)((a << 24) | (r << 16) | (g << 8) | b);
        }
    result = (*env)->NewIntArray(env, (jsize)count);
    if (result)
        (*env)->SetIntArrayRegion(env, result, 0, (jsize)count, pixels);
cleanup:
    free(pixels);
    free(raster);
    TIFFClose(tiff);
    (*env)->ReleaseByteArrayElements(env, input, bytes, JNI_ABORT);
    if (!result && !(*env)->ExceptionCheck(env))
        return image_failure(env, failure);
    return result;
}
