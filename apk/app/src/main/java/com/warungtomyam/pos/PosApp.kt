package com.warungtomyam.pos

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt application entry point. Dependency graph is wired here as modules are added. */
@HiltAndroidApp
class PosApp : Application()
