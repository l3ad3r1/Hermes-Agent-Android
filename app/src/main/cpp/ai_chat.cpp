// Adapted from examples/llama.android/lib/src/main/cpp/ai_chat.cpp
// Copyright (c) Meta Platforms, Inc. and affiliates. MIT License.
//
// JNI entry points are named for com.hermes.agent.data.llm.LlamaInferenceEngine.

#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;
constexpr int   DEFAULT_CONTEXT_SIZE    = 8192;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;

extern "C"
JNIEXPORT void JNICALL
Java_com_hermes_agent_data_llm_LlamaInferenceEngine_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    llama_log_set(aichat_android_log_callback, nullptr);
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);
    llama_backend_init();
    LOGi("Backend initiated.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_hermes_agent_data_llm_LlamaInferenceEngine_loadModel(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();
    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: %s", __func__, model_path);
    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) { return 1; }
    g_model = model;
    return 0;
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) { return nullptr; }
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
        (int) sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_ctx = llama_model_n_ctx_train(model);
    ctx_params.n_ctx        = std::min(n_ctx, trained_ctx);
    ctx_params.n_batch      = BATCH_SIZE;
    ctx_params.n_ubatch     = BATCH_SIZE;
    ctx_params.n_threads    = n_threads;
    ctx_params.n_threads_batch = n_threads;
    return llama_init_from_model(g_model, ctx_params);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_hermes_agent_data_llm_LlamaInferenceEngine_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    common_params_sampling sparams;
    sparams.temp = DEFAULT_SAMPLER_TEMP;
    g_sampler = common_sampler_init(g_model, sparams);
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_hermes_agent_data_llm_LlamaInferenceEngine_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

// ── Conversation state ──────────────────────────────────────────────────────

constexpr const char *ROLE_SYSTEM    = "system";
constexpr const char *ROLE_USER      = "user";
constexpr const char *ROLE_ASSISTANT = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

static void reset_conversation(const bool clear_kv = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;
    if (clear_kv) llama_memory_clear(llama_get_memory(g_context), false);
}

static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg msg;
    msg.role = role;
    msg.content = content;
    auto formatted = common_chat_format_single(
        g_chat_templates.get(), chat_msgs, msg, role == ROLE_USER, false);
    chat_msgs.push_back(msg);
    return formatted;
}

static int decode_in_batches(const llama_tokens &tokens, const llama_pos start_pos,
                              const bool want_last_logit = false) {
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(g_batch);
        if (start_pos + i + cur >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            shift_context();
        }
        for (int j = 0; j < cur; j++) {
            const bool is_last = want_last_logit && (i + j == (int) tokens.size() - 1);
            common_batch_add(g_batch, tokens[i + j], start_pos + i + j, {0}, is_last);
        }
        if (llama_decode(g_context, g_batch) != 0) { return 1; }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_hermes_agent_data_llm_LlamaInferenceEngine_processSystemPrompt(
        JNIEnv *env, jobject /*unused*/, jstring jsystem_prompt) {
    reset_conversation();

    const auto *sys = env->GetStringUTFChars(jsystem_prompt, nullptr);
    std::string formatted(sys);
    const bool has_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_template) formatted = chat_add_and_format(ROLE_SYSTEM, sys);
    env->ReleaseStringUTFChars(jsystem_prompt, sys);

    const auto tokens = common_tokenize(g_context, formatted, has_template, has_template);
    if ((int) tokens.size() > DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) { return 1; }
    if (decode_in_batches(tokens, current_position)) { return 2; }

    system_prompt_position = current_position = (int) tokens.size();
    return 0;
}

// ── Generation state ────────────────────────────────────────────────────────

static llama_pos  stop_gen_pos;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_gen_state() {
    stop_gen_pos = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_hermes_agent_data_llm_LlamaInferenceEngine_processUserPrompt(
        JNIEnv *env, jobject /*unused*/, jstring juser_prompt, jint n_predict) {
    reset_gen_state();

    const auto *usr = env->GetStringUTFChars(juser_prompt, nullptr);
    std::string formatted(usr);
    const bool has_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_template) formatted = chat_add_and_format(ROLE_USER, usr);
    env->ReleaseStringUTFChars(juser_prompt, usr);

    auto tokens = common_tokenize(g_context, formatted, has_template, has_template);
    const int max_batch = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) tokens.size() > max_batch) tokens.resize(max_batch);

    if (decode_in_batches(tokens, current_position, true)) { return 2; }

    current_position += (int) tokens.size();
    stop_gen_pos = current_position + n_predict;
    return 0;
}

static bool is_valid_utf8(const char *s) {
    if (!s) return true;
    const auto *b = (const unsigned char *) s;
    int num;
    while (*b != 0x00) {
        if      ((*b & 0x80) == 0x00) num = 1;
        else if ((*b & 0xE0) == 0xC0) num = 2;
        else if ((*b & 0xF0) == 0xE0) num = 3;
        else if ((*b & 0xF8) == 0xF0) num = 4;
        else return false;
        b += 1;
        for (int i = 1; i < num; ++i) {
            if ((*b & 0xC0) != 0x80) return false;
            b += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_hermes_agent_data_llm_LlamaInferenceEngine_generateNextToken(
        JNIEnv *env, jobject /*unused*/) {
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) shift_context();
    if (current_position >= stop_gen_pos) return nullptr;

    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) return nullptr;
    current_position++;

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    cached_token_chars += common_token_to_piece(g_context, new_token_id);
    if (is_valid_utf8(cached_token_chars.c_str())) {
        jstring result = env->NewStringUTF(cached_token_chars.c_str());
        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
        return result;
    }
    return env->NewStringUTF("");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_hermes_agent_data_llm_LlamaInferenceEngine_unloadModel(JNIEnv * /*unused*/, jobject /*unused*/) {
    reset_conversation();
    reset_gen_state();
    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);
    g_model   = nullptr;
    g_context = nullptr;
    g_sampler = nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_hermes_agent_data_llm_LlamaInferenceEngine_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
