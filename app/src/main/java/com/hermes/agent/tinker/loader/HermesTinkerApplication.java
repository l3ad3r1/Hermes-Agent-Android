package com.hermes.agent.tinker.loader;

import com.tencent.tinker.loader.app.TinkerApplication;
import com.tencent.tinker.loader.shareutil.ShareConstants;

import dagger.hilt.internal.GeneratedComponentManager;

/**
 * The manifest's Application: Tinker's patch-loading shell, and nothing else.
 *
 * <p>Tinker installs a patch by giving the process a new class loader in
 * {@code attachBaseContext}. Every class this shell touches has already been
 * loaded from the installed APK by then and can never be patched (a "loader
 * class"), and it must not refer to any class that can be — tinker-patch-lib
 * refuses to build a patch that breaks either rule. That is why this is Java
 * (no Kotlin runtime references) and why it holds no app logic.
 *
 * <p>Hilt: the app's real start-up, and the whole Dagger graph, live in
 * {@code com.hermes.agent.tinker.HermesApplicationLike}, which Tinker creates
 * through the patched class loader. Hilt's generated activities, services,
 * receivers and entry points find the singleton component by checking that the
 * Application is a {@link GeneratedComponentManager}; this shell implements that
 * one interface (itself listed as a loader class, so both class loaders share
 * it) and forwards to the component manager the delegate installs. Making the
 * {@code @HiltAndroidApp} class the manifest Application instead would build
 * the component in the old class loader, leaving the entire graph unpatchable
 * and mixing old and new classes in the same casts.
 *
 * <p>Safe mode: Tinker counts starts that die before the delegate has attached
 * and drops the patch after {@code ShareConstants.TINKER_SAFE_MODE_MAX_COUNT}
 * of them; later crash loops are handled by the delegate's crash guard.
 */
public final class HermesTinkerApplication extends TinkerApplication implements GeneratedComponentManager<Object> {

    /** Loaded by name through the patched class loader, so it can itself be patched. */
    private static final String DELEGATE = "com.hermes.agent.tinker.HermesApplicationLike";
    private static final String LOADER = "com.tencent.tinker.loader.TinkerLoader";

    private volatile GeneratedComponentManager<?> componentManager;

    public HermesTinkerApplication() {
        // dex + native libraries + resources; MD5s are verified when the patch is installed
        // in the :patch process (and the signature and TINKER_ID on every load), not re-hashed
        // on every cold start; DelegateLastClassLoader, Tinker's default on Android 8.1+.
        super(ShareConstants.TINKER_ENABLE_ALL, DELEGATE, LOADER, false, true);
    }

    /** Called once by the delegate while the base context is attached, before any provider runs. */
    public void setComponentManager(GeneratedComponentManager<?> manager) {
        componentManager = manager;
    }

    @Override
    public Object generatedComponent() {
        GeneratedComponentManager<?> manager = componentManager;
        if (manager == null) {
            throw new IllegalStateException("The Hilt component was requested before HermesApplicationLike attached.");
        }
        return manager.generatedComponent();
    }
}
