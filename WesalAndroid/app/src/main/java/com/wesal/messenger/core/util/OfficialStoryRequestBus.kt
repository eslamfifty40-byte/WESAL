package com.wesal.messenger.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Round 18: one-shot hand-off for «نشر حالة رسمية» (publish official
 * story). The entry now lives ONLY inside the admin panel
 * (AdminVerificationScreen — a separate navigation destination), while
 * the story composer lives in the Home screen's Status tab. The panel
 * bumps this counter and navigates home; HomeScreen consumes each
 * pending request exactly once (switch to the Status tab with the
 * official composer armed) and clears it, so a later recreation of
 * Home never replays a stale request.
 *
 * This bus carries NO authority: the publish path still enforces the
 * same gates as before — [OfficialStoryLogic.canPublishOfficial] on
 * the author's numericId in StoriesViewModel and the repository
 * stamping in StoryRepositoryImpl.
 */
object OfficialStoryRequestBus {

    private val _pendingRequest = MutableStateFlow(0)
    val pendingRequest: StateFlow<Int> = _pendingRequest.asStateFlow()

    /** Called by the admin-panel entry when the admin taps publish. */
    fun requestPublish() {
        _pendingRequest.value += 1
    }

    /** HomeScreen calls this once it has applied [value]. */
    fun consume(value: Int) {
        if (_pendingRequest.value == value) _pendingRequest.value = 0
    }
}
