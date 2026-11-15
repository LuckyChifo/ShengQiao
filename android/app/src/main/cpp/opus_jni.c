#include <jni.h>
#include <stdint.h>
#include <opus.h>

#define FRAME_SIZE 480
#define MAX_CHANNELS 2
#define PCM_BYTES (FRAME_SIZE * MAX_CHANNELS * 2)

JNIEXPORT jlong JNICALL
Java_com_shengqiao_app_core_OpusDecoder_nativeCreate(JNIEnv *env, jclass type,
                                                      jint sample_rate, jint channels) {
    (void) env;
    (void) type;
    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(sample_rate, channels, &error);
    if (error != OPUS_OK || decoder == NULL) {
        return 0;
    }
    return (jlong) (intptr_t) decoder;
}

JNIEXPORT jint JNICALL
Java_com_shengqiao_app_core_OpusDecoder_nativeDecode(JNIEnv *env, jclass type, jlong handle,
                                                      jbyteArray packet, jint offset, jint length,
                                                      jbyteArray output, jint output_offset) {
    (void) type;
    OpusDecoder *decoder = (OpusDecoder *) (intptr_t) handle;
    if (decoder == NULL || output == NULL || output_offset < 0
            || output_offset > (*env)->GetArrayLength(env, output) - PCM_BYTES) {
        return OPUS_BAD_ARG;
    }
    jbyte *compressed = NULL;
    if (packet == NULL) {
        if (offset != 0 || length != 0) return OPUS_BAD_ARG;
    } else {
        if (offset < 0 || length <= 0 || offset > (*env)->GetArrayLength(env, packet) - length) {
            return OPUS_BAD_ARG;
        }
        compressed = (*env)->GetByteArrayElements(env, packet, NULL);
        if (compressed == NULL) return OPUS_ALLOC_FAIL;
    }
    opus_int16 pcm[FRAME_SIZE * MAX_CHANNELS];
    const unsigned char *input = compressed == NULL ? NULL : (const unsigned char *) compressed + offset;
    int samples = opus_decode(decoder, input, length, pcm, FRAME_SIZE, 0);
    if (compressed != NULL) (*env)->ReleaseByteArrayElements(env, packet, compressed, JNI_ABORT);
    if (samples < 0) {
        return samples;
    }

    jbyte decoded[PCM_BYTES];
    for (int i = 0; i < samples * MAX_CHANNELS; i++) {
        uint16_t value = (uint16_t) pcm[i];
        decoded[i * 2] = (jbyte) (value & 0xff);
        decoded[i * 2 + 1] = (jbyte) (value >> 8);
    }
    (*env)->SetByteArrayRegion(env, output, output_offset, samples * MAX_CHANNELS * 2, decoded);
    return samples;
}

JNIEXPORT void JNICALL
Java_com_shengqiao_app_core_OpusDecoder_nativeDestroy(JNIEnv *env, jclass type, jlong handle) {
    (void) env;
    (void) type;
    OpusDecoder *decoder = (OpusDecoder *) (intptr_t) handle;
    if (decoder != NULL) {
        opus_decoder_destroy(decoder);
    }
}

JNIEXPORT jstring JNICALL
Java_com_shengqiao_app_core_OpusDecoder_nativeVersion(JNIEnv *env, jclass type) {
    (void) type;
    return (*env)->NewStringUTF(env, opus_get_version_string());
}
