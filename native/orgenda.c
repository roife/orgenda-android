#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <tree_sitter/api.h>

extern const TSLanguage *tree_sitter_org(void);
typedef struct { TSParser *parser; TSTree *tree; char *bytes; uint32_t size; } Session;

static void fail(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}

JNIEXPORT jlong JNICALL Java_com_roifewu_orgenda_core_NativeBridge_open(JNIEnv *env, jobject self) {
    Session *s = calloc(1, sizeof(Session));
    if (!s) { fail(env, "Out of memory"); return 0; }
    s->parser = ts_parser_new();
    if (!s->parser || !ts_parser_set_language(s->parser, tree_sitter_org())) {
        if (s->parser) ts_parser_delete(s->parser);
        free(s); fail(env, "Tree-sitter ABI mismatch"); return 0;
    }
    return (jlong)(intptr_t)s;
}

static TSPoint point(const char *bytes, uint32_t end) {
    TSPoint p = {0, 0};
    for (uint32_t i = 0; i < end; ++i) {
        if (bytes[i] == '\n') { ++p.row; p.column = 0; } else { ++p.column; }
    }
    return p;
}

typedef struct { jint *data; size_t count, capacity; int failed; } Nodes;
static void collect(TSNode node, jint parent, Nodes *out, unsigned depth) {
    if (out->failed) return;
    if (depth > 1024 || out->count > 4000000) { out->failed = 1; return; }
    if (out->count + 5 > out->capacity) {
        size_t capacity = out->capacity ? out->capacity * 2 : 1024;
        jint *next = realloc(out->data, capacity * sizeof(jint));
        if (!next) { out->failed = 1; return; }
        out->data = next; out->capacity = capacity;
    }
    jint index = (jint)(out->count / 5);
    out->data[out->count++] = (jint)ts_node_symbol(node);
    out->data[out->count++] = (jint)ts_node_start_byte(node);
    out->data[out->count++] = (jint)ts_node_end_byte(node);
    out->data[out->count++] = parent;
    out->data[out->count++] = ts_node_has_error(node) || ts_node_is_missing(node);
    uint32_t count = ts_node_named_child_count(node);
    for (uint32_t i = 0; i < count; ++i) collect(ts_node_named_child(node, i), index, out, depth + 1);
}

JNIEXPORT jintArray JNICALL Java_com_roifewu_orgenda_core_NativeBridge_parse(JNIEnv *env, jobject self, jlong handle, jbyteArray input) {
    Session *s = (Session *)(intptr_t)handle;
    if (!s) { fail(env, "Parser is closed"); return NULL; }
    jsize size = (*env)->GetArrayLength(env, input);
    char *bytes = malloc((size_t)size + 1);
    if (!bytes) { fail(env, "Out of memory"); return NULL; }
    (*env)->GetByteArrayRegion(env, input, 0, size, (jbyte *)bytes);
    bytes[size] = 0;
    TSTree *edited = s->tree ? ts_tree_copy(s->tree) : NULL;
    if (edited) {
        uint32_t start = 0, old_end = s->size, new_end = (uint32_t)size;
        while (start < old_end && start < new_end && s->bytes[start] == bytes[start]) ++start;
        while (start > 0 && start < old_end && ((unsigned char)s->bytes[start] & 0xc0) == 0x80) --start;
        while (old_end > start && new_end > start && s->bytes[old_end - 1] == bytes[new_end - 1]) { --old_end; --new_end; }
        while (old_end < s->size && ((unsigned char)s->bytes[old_end] & 0xc0) == 0x80) { ++old_end; ++new_end; }
        TSInputEdit edit = {start, old_end, new_end, point(s->bytes, start), point(s->bytes, old_end), point(bytes, new_end)};
        ts_tree_edit(edited, &edit);
    }
    TSTree *tree = ts_parser_parse_string(s->parser, edited, bytes, (uint32_t)size);
    if (edited) ts_tree_delete(edited);
    if (!tree) { free(bytes); fail(env, "Could not parse document"); return NULL; }
    Nodes nodes = {0}; collect(ts_tree_root_node(tree), -1, &nodes, 0);
    if (nodes.failed) {
        free(nodes.data); free(bytes); ts_tree_delete(tree); fail(env, "Document syntax exceeds safety limit"); return NULL;
    }
    jintArray result = (*env)->NewIntArray(env, (jsize)nodes.count);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, (jsize)nodes.count, nodes.data);
    free(nodes.data);
    if (s->tree) ts_tree_delete(s->tree);
    free(s->bytes); s->bytes = bytes; s->size = (uint32_t)size; s->tree = tree;
    return result;
}

JNIEXPORT jobjectArray JNICALL Java_com_roifewu_orgenda_core_NativeBridge_symbols(JNIEnv *env, jobject self) {
    const TSLanguage *lang = tree_sitter_org();
    uint32_t count = ts_language_symbol_count(lang);
    jclass string = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count, string, NULL);
    for (uint32_t i = 0; i < count; ++i) {
        jstring name = (*env)->NewStringUTF(env, ts_language_symbol_name(lang, i));
        (*env)->SetObjectArrayElement(env, result, i, name); (*env)->DeleteLocalRef(env, name);
    }
    return result;
}

JNIEXPORT void JNICALL Java_com_roifewu_orgenda_core_NativeBridge_close(JNIEnv *env, jobject self, jlong handle) {
    Session *s = (Session *)(intptr_t)handle;
    if (!s) return;
    if (s->tree) ts_tree_delete(s->tree);
    ts_parser_delete(s->parser); free(s->bytes); free(s);
}
