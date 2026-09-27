#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <mutex>
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

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

// Two independent KV lanes share the context: the conversation, and the
// background work the app runs alongside it (today the conversation-brief
// merge). They share no prompt prefix, so before lanes existed each one's
// prefill wiped the other's cache and both were permanently cold.
//
// With kv_unified = false llama.cpp splits n_ctx evenly, giving each lane
// n_ctx / n_seq_max — so this costs no extra KV memory, it partitions what was
// already allocated. Anything reasoning about a single conversation's room must
// therefore use LANE_CONTEXT_SIZE, not the total.
// One model slot per role. They used to share a single set of globals, so
// asking for the tool caller evicted the chat model — a new llama_context, and
// with it both KV lanes, every time a turn used a tool. Slots let each role keep
// its own model, context and lanes resident; the Kotlin side decides whether
// there is RAM for that (see LocalLlmManager) and falls back to evicting when
// there is not.
constexpr int   N_SLOTS                 = 2;
constexpr int   SLOT_CHAT               = 0;
constexpr int   SLOT_TOOL_CALLER        = 1;

constexpr int   N_LANES                 = 2;
constexpr int   LANE_CHAT               = 0;
constexpr int   LANE_AUX                = 1;

constexpr int   DEFAULT_CONTEXT_SIZE    = 8192;
constexpr int   LANE_CONTEXT_SIZE       = DEFAULT_CONTEXT_SIZE / N_LANES;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
// Layers pushed onto a GPU backend when one is present; 99 is llama.cpp's idiom
// for "all of them". Only reachable once a backend .so actually loads.
constexpr int   GPU_OFFLOAD_LAYERS      = 99;
// Upper bound on the system-prompt prefill. Chosen so the decode stays inside
// ART's ~10s GC-suspend window on a phone-class CPU (~3 batches). See the note
// in processSystemPrompt.
constexpr int   MAX_SYSTEM_PREFILL_TOKENS = 1536;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;


/**
 * Everything that belongs to one conversation-in-flight.
 *
 * One instance per KV lane. `id` doubles as the llama.cpp sequence id, so a
 * decode, an eviction and a context shift on one lane cannot touch the other.
 */
struct Lane {
    int                            id = 0;
    /** Exact token sequence resident in this lane at positions [0, size). Kept
     *  in lockstep with every decode so the next turn can measure how much of
     *  its prompt is already computed. See processSystemPrompt. */
    llama_tokens                   cached_tokens;
    std::vector<common_chat_msg>   chat_msgs;
    llama_pos                      system_prompt_position = 0;
    llama_pos                      current_position       = 0;
    llama_pos                      stop_generation_position = 0;
    std::string                    cached_token_chars;
    std::ostringstream             assistant_ss;
    common_sampler               * sampler = nullptr;
};

/**
 * One loaded model and everything that belongs to it.
 *
 * Slots are independent: loading, unloading or decoding in one cannot disturb
 * another's model, context or KV lanes.
 */
struct Slot {
    int                        id = 0;
    llama_model              * model = nullptr;
    llama_context            * context = nullptr;
    llama_batch                batch{};
    bool                       batch_ready = false;
    common_chat_templates_ptr  chat_templates;
    Lane                       lanes[N_LANES];
};

static Slot g_slots[N_SLOTS];

/** Stamps each slot with its own index once, so logs can name it. */
static void ensure_slot_ids() {
    for (int i = 0; i < N_SLOTS; i++) { g_slots[i].id = i; }
}

/** Falls back to the conversation slot rather than trusting an out-of-range id. */
static Slot &slot_at(const int index) {
    ensure_slot_ids();
    if (index < 0 || index >= N_SLOTS) {
        LOGw("%s: slot %d out of range; using the conversation slot", __func__, index);
        return g_slots[SLOT_CHAT];
    }
    return g_slots[index];
}

/** Falls back to the conversation lane rather than trusting an out-of-range id. */
static Lane &lane_at(Slot &slot, const int index) {
    if (index < 0 || index >= N_LANES) {
        LOGw("%s: lane %d out of range; using the conversation lane", __func__, index);
        return slot.lanes[LANE_CHAT];
    }
    return slot.lanes[index];
}

// Secondary defense layer behind the Kotlin coroutine dispatcher
// (Dispatchers.IO.limitedParallelism(1) in InferenceEngineImpl), which is
// expected to already serialize all calls into this file. This mutex guards
// against a stray call arriving from an unexpected thread (e.g. a second
// engine instance, or a JNI call issued off the intended dispatcher) racing
// on the global model/context/batch/sampler state above and corrupting it.
static std::mutex g_state_mutex;

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

static std::string get_backend(); // defined below, beside the other reporting

/**
 * True when a non-CPU backend registered during init().
 *
 * Backends ship as separate .so files (GGML_BACKEND_DL) and
 * ggml_backend_load_best() skips any it cannot load, so this is the only
 * trustworthy signal that offload is available: a build made with GGML_OPENCL=ON
 * still lands on devices with no usable driver, and those must stay on the CPU.
 */
static bool has_gpu_backend() {
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        if (std::string(ggml_backend_reg_name(ggml_backend_reg_get(i))) != "CPU") {
            return true;
        }
    }
    return false;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path, jint jslot) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    Slot &slot = slot_at(jslot);
    llama_model_params model_params = llama_model_default_params();
    // Offload everything when a GPU backend registered, nothing otherwise. This
    // sat at a hard 0 because Vulkan offload triggered DeviceLostError on Adreno;
    // with Vulkan compiled out it stays 0 on a CPU-only build and only lifts once
    // an OpenCL backend .so actually loads (see OPENCL_SDK in app/build.gradle.kts).
    model_params.n_gpu_layers = has_gpu_backend() ? GPU_OFFLOAD_LAYERS : 0;
    LOGi("%s: slot %d backends=[%s], offloading %d layers",
         __func__, slot.id, get_backend().c_str(), model_params.n_gpu_layers);
    model_params.use_mmap = true;

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    slot.model = model;
    return 0;
}

static llama_context *init_context(llama_model *model,
                                   const int n_ctx = DEFAULT_CONTEXT_SIZE,
                                   const int n_seq_max = N_LANES) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    // One sequence per lane. kv_unified is off deliberately: llama.cpp warns it
    // hurts when sequences do not share a large prefix, and ours share none —
    // one is a chat transcript, the other a summarisation prompt. Off also makes
    // the split explicit at n_ctx / n_seq_max instead of letting both lanes
    // believe they own the whole context.
    ctx_params.n_seq_max = n_seq_max;
    ctx_params.kv_unified = false;
    ctx_params.n_batch = BATCH_SIZE;
    // Physical batch, kept equal to n_batch so each 512-token prefill chunk is a
    // single pass instead of eight. It was pinned to 64 to work around an Adreno
    // vk::DeviceLostError (TDR); that no longer applies while GGML_VULKAN is OFF
    // and every decode runs on the CPU backend. Restore the 64 cap alongside any
    // change that puts decoding back on the GPU -- the Adreno fault it guarded
    // against is a property of the hardware, not of Vulkan specifically.
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(llama_model *model, float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/, jint jslot) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    Slot &slot = slot_at(jslot);
    auto *context = init_context(slot.model);
    if (!context) { return 1; }
    slot.context = context;
    // A batch entry has to be able to name the lane it belongs to.
    slot.batch = llama_batch_init(BATCH_SIZE, 0, N_LANES);
    slot.chat_templates = common_chat_templates_init(slot.model, "");
    slot.batch_ready = true;
    for (int i = 0; i < N_LANES; i++) {
        Lane &lane = slot.lanes[i];
        lane = Lane{};                       // new context, nothing resident
        lane.id = i;
        lane.sampler = new_sampler(slot.model, DEFAULT_SAMPLER_TEMP);
    }
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr, jint jslot) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    Slot &slot = slot_at(jslot);
    // Single sequence: the benchmark wants the whole pp-token window to itself,
    // not the per-lane split the chat context uses.
    auto *context = init_context(slot.model, pp, /* n_seq_max */ 1);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(slot.batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(slot.batch, 0, i, {0}, false);
        }

        slot.batch.logits[slot.batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, slot.batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(slot.batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(slot.batch, 0, i, {j}, true);
            }

            if (llama_decode(context, slot.batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(slot.model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(slot.model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(slot.model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static void reset_long_term_states(Slot &slot, Lane &lane, const bool clear_kv_cache = true) {
    lane.chat_msgs.clear();
    lane.system_prompt_position = 0;
    lane.current_position = 0;

    if (clear_kv_cache) {
        // Only this lane's cells: llama_memory_clear() would wipe every lane.
        llama_memory_seq_rm(llama_get_memory(slot.context), lane.id, -1, -1);
        lane.cached_tokens.clear();
    }
}

/**
 * TODO-hyin: implement sliding-window version as a better alternative
 *
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static void shift_context(Slot &slot, Lane &lane) {
    const int n_discard = (lane.current_position - lane.system_prompt_position) / 2;
    LOGi("%s: lane %d discarding %d tokens", __func__, lane.id, n_discard);
    llama_memory_seq_rm(llama_get_memory(slot.context), lane.id,
                        lane.system_prompt_position, lane.system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(slot.context), lane.id,
                         lane.system_prompt_position + n_discard, lane.current_position, -n_discard);
    lane.current_position -= n_discard;
    // The n_predict budget moves with the tokens. Left where it was, a stop
    // position past the lane size could never be reached, so generation ran on.
    if (lane.stop_generation_position > 0) lane.stop_generation_position -= n_discard;
    // Mirror the same discard so the cache and its mirror stay in lockstep. A
    // shift that cannot be mirrored exactly drops reuse rather than risk serving
    // a prefix that no longer describes what is resident.
    if ((int) lane.cached_tokens.size() >= lane.system_prompt_position + n_discard) {
        lane.cached_tokens.erase(
                lane.cached_tokens.begin() + lane.system_prompt_position,
                lane.cached_tokens.begin() + lane.system_prompt_position + n_discard);
    } else {
        lane.cached_tokens.clear();
    }
    LOGi("%s: Context shifting done! Current position: %d", __func__, lane.current_position);
}

static std::string chat_add_and_format(Slot &slot, Lane &lane, const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            slot.chat_templates.get(), lane.chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ true);
    lane.chat_msgs.push_back(new_msg);
    // The text is the user's conversation, memory and persona: debug builds only.
    LOGd("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    LOGi("%s: added %s message (%d chars)", __func__, role.c_str(), (int) formatted.size());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static void reset_short_term_states(Lane &lane) {
    lane.stop_generation_position = 0;
    lane.cached_token_chars.clear();
    lane.assistant_ss.str("");
}

/**
 * Records tokens just decoded into the KV cache at [start_pos, start_pos + n).
 *
 * The mirror is only sound if it describes the cache exactly, so a gap or an
 * overlap invalidates it instead of silently desynchronising. Dropping it costs
 * one full prefill on the next turn, which self-heals: processSystemPrompt then
 * reuses nothing, clears the cache and records from position 0 again.
 */
static void cache_record(Lane &lane, const llama_tokens &tokens, const llama_pos start_pos) {
    if (start_pos != (llama_pos) lane.cached_tokens.size()) {
        LOGw("%s: lane %d KV mirror out of sync (decode at %d, mirror holds %d); dropping reuse",
             __func__, lane.id, start_pos, (int) lane.cached_tokens.size());
        lane.cached_tokens.clear();
        return;
    }
    lane.cached_tokens.insert(lane.cached_tokens.end(), tokens.begin(), tokens.end());
}

/** Length of the longest shared prefix of two token sequences. */
static size_t common_prefix_length(const llama_tokens &a, const llama_tokens &b) {
    const size_t n = std::min(a.size(), b.size());
    size_t i = 0;
    while (i < n && a[i] == b[i]) { i++; }
    return i;
}

static int decode_tokens_in_batches(
        Slot &slot,
        Lane &lane,
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), lane.current_position);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Shift context if current batch cannot fit into the context
        if (lane.current_position + cur_batch_size >= LANE_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into lane %d! Shifting...", __func__, lane.id);
            shift_context(slot, lane);
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = lane.current_position + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {lane.id}, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            // A failed decode leaves this lane's cells in an undefined state;
            // neither they nor the mirror can be trusted as a prefix afterwards.
            // Scoped to the lane so the other one survives.
            llama_memory_seq_rm(llama_get_memory(context), lane.id, -1, -1);
            lane.cached_tokens.clear();
            return 1;
        }
        cache_record(lane,
                     llama_tokens(tokens.begin() + i, tokens.begin() + i + cur_batch_size),
                     lane.current_position);
        lane.current_position += cur_batch_size;
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt,
        jint jlane,
        jint jslot
) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    Slot &slot = slot_at(jslot);
    Lane &lane = lane_at(slot, jlane);
    // Reset the chat-template bookkeeping and the per-turn state, but leave the
    // KV cache resident: the prefill below reuses whatever prefix still matches.
    reset_long_term_states(slot, lane, /* clear_kv_cache */ false);
    reset_short_term_states(lane);

    // Obtain system prompt from JEnv
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(slot.chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(slot, lane, ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    // Tokenize system prompt
    // parse_special stays on: the template markers and the tool-caller declarations
    // are real control tokens. Untrusted text is defused in Kotlin (neutralizeControlTokens).
    auto system_tokens = common_tokenize(slot.context, formatted_system_prompt,
                                         false, true);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(slot.context, id).c_str(), id);
    }

    // Handle context overflow
    const int max_batch_size = LANE_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for lane! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Hard prefill cap. Decoding runs in one uninterruptible native pass, and
    // on a phone-class CPU a few thousand tokens takes long enough that ART's
    // GC SuspendAll watchdog aborts the process mid-turn. The Kotlin side already
    // trims the prompt; this is the last line of defence for any path that
    // slips a large one through. Keep both ends and drop the middle: the head
    // carries the persona, the tail carries the newest history, the "how to
    // reply" close and the template's end-of-system marker.
    if ((int) system_tokens.size() > MAX_SYSTEM_PREFILL_TOKENS) {
        LOGw("%s: Capping system prefill from %d to %d tokens",
             __func__, (int) system_tokens.size(), MAX_SYSTEM_PREFILL_TOKENS);
        const int head = MAX_SYSTEM_PREFILL_TOKENS / 3;
        const int tail = MAX_SYSTEM_PREFILL_TOKENS - head;
        system_tokens.erase(system_tokens.begin() + head, system_tokens.end() - tail);
    }

    // Reuse the longest prefix of the resident cache that still matches this
    // block. The Kotlin side rebuilds the whole thing every turn -- instructions,
    // then "Conversation so far", then the how-to-reply close -- so turn N+1 is
    // turn N's block with one more history entry spliced in before that close:
    // everything up to the splice is already computed, and only the tail plus
    // the close have to be decoded. A different conversation shares no prefix and
    // falls back to a full prefill on its own.
    const int n_reused = (int) common_prefix_length(lane.cached_tokens, system_tokens);
    llama_memory_seq_rm(llama_get_memory(slot.context), lane.id, n_reused, -1);
    lane.cached_tokens.resize(n_reused);
    lane.current_position = n_reused;

    const llama_tokens pending(system_tokens.begin() + n_reused, system_tokens.end());
    LOGi("%s: slot %d lane %d system prefill: %d tokens reused, %d to decode",
         __func__, slot.id, lane.id, n_reused, (int) pending.size());

    // Decode whatever diverged, in batches
    if (!pending.empty() &&
        decode_tokens_in_batches(slot, lane, slot.context, slot.batch, pending)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    lane.system_prompt_position = lane.current_position;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict,
        jint jlane,
        jint jslot
) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    Slot &slot = slot_at(jslot);
    Lane &lane = lane_at(slot, jlane);
    // Reset short-term states
    reset_short_term_states(lane);

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(slot.chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(slot, lane, ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Decode formatted user prompts
    // See processSystemPrompt: untrusted text arrives already defused.
    auto user_tokens = common_tokenize(slot.context, formatted_user_prompt, false, true);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(slot.context, id).c_str(), id);
    }

    // Ensure user prompt doesn't exceed the context size by truncating if necessary.
    const int original_user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = LANE_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (original_user_prompt_size > max_batch_size) {
        const int skipped_tokens = original_user_prompt_size - max_batch_size;
        user_tokens.erase(user_tokens.begin(), user_tokens.begin() + skipped_tokens);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    // Decode user tokens in batches
    if (decode_tokens_in_batches(slot, lane, slot.context, slot.batch, user_tokens, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    lane.stop_generation_position = lane.current_position + n_predict;
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

/**
 * Whether [s], which is not valid UTF-8, is only waiting for the rest of its
 * last character. Anything else -- a stray continuation byte, a bad lead byte --
 * can never become valid, however many tokens follow.
 */
static bool utf8_awaits_more(const std::string &s) {
    size_t i = 0;
    while (i < s.size()) {
        const auto c = (unsigned char) s[i];
        int num;
        if (c < 0x80) num = 1;
        else if ((c & 0xE0) == 0xC0) num = 2;
        else if ((c & 0xF0) == 0xE0) num = 3;
        else if ((c & 0xF8) == 0xF0) num = 4;
        else return false;
        for (int k = 1; k < num; ++k) {
            if (i + k >= s.size()) return true;
            if ((((unsigned char) s[i + k]) & 0xC0) != 0x80) return false;
        }
        i += num;
    }
    return false;
}

/** Replaces every byte that is not part of a well-formed character with U+FFFD. */
static std::string utf8_repair(const std::string &s) {
    std::string out;
    size_t i = 0;
    while (i < s.size()) {
        const auto c = (unsigned char) s[i];
        const int num = c < 0x80 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 0;
        bool ok = num > 0 && i + num <= s.size();
        for (int k = 1; ok && k < num; ++k) ok = (((unsigned char) s[i + k]) & 0xC0) == 0x80;
        if (ok) {
            out.append(s, i, num);
            i += num;
        } else {
            out += "\xEF\xBF\xBD";
            i += 1;
        }
    }
    return out;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/,
        jint jlane,
        jint jslot
) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    Slot &slot = slot_at(jslot);
    Lane &lane = lane_at(slot, jlane);
    // Infinite text generation via context shifting
    if (lane.current_position >= LANE_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("%s: Lane %d full! Shifting...", __func__, lane.id);
        shift_context(slot, lane);
    }

    // Stop if reaching the marked position
    if (lane.current_position >= lane.stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, lane.stop_generation_position);
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(lane.sampler, slot.context, -1);
    common_sampler_accept(lane.sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(slot.batch);
    common_batch_add(slot.batch, new_token_id, lane.current_position, {lane.id}, true);
    if (llama_decode(slot.context, slot.batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    // Update position
    cache_record(lane, {new_token_id}, lane.current_position);
    lane.current_position++;

    // Stop if next token is EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(slot.model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        chat_add_and_format(slot, lane, ROLE_ASSISTANT, lane.assistant_ss.str());
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(slot.context, new_token_id);
    lane.cached_token_chars += new_token_chars;

    // A byte that can never complete a character would otherwise hold back every
    // later token, cutting the reply off there. Replace it and carry on.
    if (!is_valid_utf8(lane.cached_token_chars.c_str()) && !utf8_awaits_more(lane.cached_token_chars)) {
        lane.cached_token_chars = utf8_repair(lane.cached_token_chars);
    }

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(lane.cached_token_chars.c_str())) {
        result = env->NewStringUTF(lane.cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, lane.cached_token_chars.c_str(), new_token_chars.c_str());

        lane.assistant_ss << lane.cached_token_chars;
        lane.cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/, jint jslot) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    Slot &slot = slot_at(jslot);
    // Nothing loaded here. Unloading a slot twice, or one that never loaded, has
    // to be harmless: reset_long_term_states would dereference a null context.
    if (!slot.model && !slot.context) { return; }

    // Reset long-term & short-term states
    for (int i = 0; i < N_LANES; i++) {
        if (slot.context) reset_long_term_states(slot, slot.lanes[i]);
        reset_short_term_states(slot.lanes[i]);
    }

    // Free up resources
    for (auto &lane : slot.lanes) {
        if (lane.sampler) {
            common_sampler_free(lane.sampler);
            lane.sampler = nullptr;
        }
    }
    slot.chat_templates.reset();
    if (slot.batch_ready) {
        llama_batch_free(slot.batch);
        slot.batch_ready = false;
    }
    llama_free(slot.context);
    llama_model_free(slot.model);
    // Leave the slot reusable rather than holding freed pointers.
    slot.context = nullptr;
    slot.model = nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
