package com.wesal.messenger.domain.model

/**
 * Round 13 (Addendum L2): the profile screen's load state — a FAILED
 * load is NOT "account doesn't exist".
 *
 * Device-proven defect (2026-11-27, a new user's phone): tapping a
 * registered contact's photo (10001/10002 — accounts that exist)
 * showed «الحساب غير موجود / غير متاح حالياً». Two conflations caused
 * it: (1) `observeUser` mapped listener ERRORS to the same null as a
 * missing document, so quota/network failures were indistinguishable
 * from non-existence; (2) the screen's "unavailable" branch fired on
 * `hiddenFromSearch`, a SEARCH-visibility flag that says nothing about
 * whether the account exists or whether a contact may view it.
 *
 * This state keeps the three outcomes separate: [Failed] renders a
 * retryable Arabic error, [NotFound] is the only path to a not-found
 * verdict, and [Loaded] renders the profile.
 */
sealed interface UserLoadState {
    data object Loading : UserLoadState
    data class Loaded(val user: WesalUser) : UserLoadState
    data object NotFound : UserLoadState
    data class Failed(val error: Throwable) : UserLoadState
}
