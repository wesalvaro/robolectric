package org.robolectric.internal;

import org.robolectric.ApkLoader;
import org.robolectric.android.internal.ParallelUniverse;
import org.robolectric.annotation.Config;
import org.robolectric.internal.bytecode.Sandbox;
import org.robolectric.internal.dependency.DependencyJar;
import org.robolectric.internal.dependency.DependencyResolver;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.Fs;
import org.robolectric.res.FsFile;
import org.robolectric.res.PackageResourceTable;
import org.robolectric.res.ResourcePath;
import org.robolectric.res.ResourceTableFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nonnull;

public class SdkEnvironment extends Sandbox {
  private final SdkConfig sdkConfig;
  private final ExecutorService executorService;
  private final ParallelUniverseInterface parallelUniverse;
  private final List<ShadowProvider> shadowProviders;

  private FsFile compileTimeSystemResourcesFile;
  private PackageResourceTable systemResourceTable;

  SdkEnvironment(SdkConfig sdkConfig, boolean useLegacyResources, ClassLoader robolectricClassLoader) {
    super(robolectricClassLoader);

    this.sdkConfig = sdkConfig;

    executorService = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(
          "main thread for SdkEnvironment(sdk=" + sdkConfig + "; " +
              "resources=" + (useLegacyResources ? "legacy" : "binary") + ")");
      thread.setContextClassLoader(robolectricClassLoader);
      return thread;
    });

    parallelUniverse = getParallelUniverse();

    this.shadowProviders = new ArrayList<>();
    for (ShadowProvider shadowProvider : ServiceLoader.load(ShadowProvider.class, robolectricClassLoader)) {
      shadowProviders.add(shadowProvider);
    }
  }

  public synchronized FsFile getCompileTimeSystemResourcesFile(DependencyResolver dependencyResolver) {
    if (compileTimeSystemResourcesFile == null) {
      DependencyJar compileTimeJar = new SdkConfig(27).getAndroidSdkDependency();
      compileTimeSystemResourcesFile =
          Fs.newFile(dependencyResolver.getLocalArtifactUrl(compileTimeJar).getFile());
    }
    return compileTimeSystemResourcesFile;
  }

  public synchronized PackageResourceTable getSystemResourceTable(DependencyResolver dependencyResolver) {
    if (systemResourceTable == null) {
      ResourcePath resourcePath = createRuntimeSdkResourcePath(dependencyResolver);
      systemResourceTable = new ResourceTableFactory().newFrameworkResourceTable(resourcePath);
    }
    return systemResourceTable;
  }

  @Nonnull
  private ResourcePath createRuntimeSdkResourcePath(DependencyResolver dependencyResolver) {
    try {
      Fs systemResFs = Fs.fromJar(dependencyResolver.getLocalArtifactUrl(sdkConfig.getAndroidSdkDependency()));
      Class<?> androidRClass = getRobolectricClassLoader().loadClass("android.R");
      Class<?> androidInternalRClass = getRobolectricClassLoader().loadClass("com.android.internal.R");
      // TODO: verify these can be loaded via raw-res path
      return new ResourcePath(androidRClass,
          systemResFs.join("raw-res/res"),
          systemResFs.join("raw-res/assets"),
          androidInternalRClass);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public SdkConfig getSdkConfig() {
    return sdkConfig;
  }

  public <V> V executeSynchronously(Callable<V> callable) throws Exception {
    Future<V> future = executorService.submit(callable);
    return future.get();
  }

  public void initialize(ApkLoader apkLoader, MethodConfig methodConfig) {
    parallelUniverse.setSdkConfig(sdkConfig);
    parallelUniverse.setResourcesMode(methodConfig.useLegacyResources());

    parallelUniverse.setUpApplicationState(
        apkLoader,
        methodConfig.getMethod(),
        methodConfig.getConfig(),
        methodConfig.getAppManifest(),
        this);
  }

  @SuppressWarnings("NewApi")
  private ParallelUniverseInterface getParallelUniverse() {
    try {
      return bootstrappedClass(ParallelUniverse.class)
          .asSubclass(ParallelUniverseInterface.class)
          .getConstructor()
          .newInstance();
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  public void tearDown() {
    parallelUniverse.tearDownApplication();
  }

  public void reset() {
    for (ShadowProvider shadowProvider : shadowProviders) {
      shadowProvider.reset();
    }
  }

  public interface MethodConfig {
    Method getMethod();

    Config getConfig();

    AndroidManifest getAppManifest();

    boolean useLegacyResources();
  }

  @FunctionalInterface
  public interface CallWithThrowable<T> {
    void call() throws Throwable;
  }
}
