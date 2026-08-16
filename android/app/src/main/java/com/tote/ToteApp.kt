package com.tote

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Phase 1 adds the suite config broker read here (`util/SuiteConfigReader` against
 * `content://com.dragonfly.suiteconfig/config/tote`, falling back to local prefs) — the same
 * shape every sibling app uses.
 */
@HiltAndroidApp
class ToteApp : Application()
