package com.hermes.agent.domain.agent

/**
 * Where a turn originated. Interactive turns have a human watching a chat
 * surface who can answer confirmation dialogs; background turns (Kanban
 * worker, local API server, crons) have nobody to ask, so anything that
 * needs a human verdict must fail fast instead of waiting on a dialog
 * that will never appear.
 */
enum class ExecutionOrigin {
    INTERACTIVE,
    BACKGROUND,
}
