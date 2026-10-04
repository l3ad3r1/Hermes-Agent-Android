package com.hermes.agent

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt's code-generation root, and nothing more: it is **not** the manifest's Application and is
 * never instantiated.
 *
 * `@HiltAndroidApp` is what makes Hilt generate the singleton component
 * (`DaggerHermesApp_HiltComponents_SingletonC`). The Application Android creates is Tinker's
 * patch-loading shell, `tinker.loader.HermesTinkerApplication`, which must stay unpatchable; the
 * component is built by `tinker.HermesComponentFactory` from the patchable
 * `tinker.HermesApplicationLike`, and the start-up that used to live here is [HermesAppStartup].
 * Keep this class name: the generated component's name derives from it.
 */
@HiltAndroidApp
class HermesApp : Application()
