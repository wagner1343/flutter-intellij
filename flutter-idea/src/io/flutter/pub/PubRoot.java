/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.pub;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.jetbrains.lang.dart.util.DotPackagesFileUtil;
import io.flutter.FlutterUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A snapshot of the root directory of a pub package.
 * <p>
 * That is, a directory containing (at a minimum) a pubspec.yaml file.
 */
public class PubRoot {
  private static final Logger LOG = Logger.getInstance(PubRoot.class);

  @NotNull
  private final VirtualFile root;

  @NotNull
  private final VirtualFile pubspec;

  private PubRoot(@NotNull VirtualFile root, @NotNull VirtualFile pubspec) {
    this.root = root;
    this.pubspec = pubspec;
  }

  /**
   * Returns the first pub root containing the given file.
   */
  @Nullable
  public static PubRoot forFile(@Nullable VirtualFile file) {
    if (file == null) {
      return null;
    }

    if (file.isDirectory()) {
      final PubRoot root = forDirectory(file.getParent());
      if (root != null) return root;
    }

    return forFile(file.getParent());
  }

  /**
   * Returns the appropriate pub root for an event.
   * <p>
   * Refreshes the returned pubroot's directory (not any others).
   */
  @Nullable
  public static PubRoot forEventWithRefresh(@NotNull final AnActionEvent event) {
    final PsiFile psiFile = CommonDataKeys.PSI_FILE.getData(event.getDataContext());
    if (psiFile != null) {
      final PubRoot root = forPsiFile(psiFile);
      if (root != null) {
        return root.refresh();
      }
    }

    final Module module = LangDataKeys.MODULE.getData(event.getDataContext());
    if (module != null) {
      final List<PubRoot> roots = PubRoots.forModule(module);
      if (!roots.isEmpty()) {
        return roots.get(0);
      }
    }

    return null;
  }

  /**
   * Returns the pub root for a PsiFile, if any.
   * <p>
   * The file must be within a content root that has a pubspec.yaml file.
   * <p>
   * Based on the filesystem cache; doesn't refresh anything.
   */
  @Nullable
  public static PubRoot forPsiFile(@NotNull PsiFile psiFile) {
    final VirtualFile file = psiFile.getVirtualFile();
    if (file == null) {
      return null;
    }
    if (isPubspec(file)) {
      return forDirectory(file.getParent());
    }
    else {
      return forDescendant(file, psiFile.getProject());
    }
  }

  /**
   * Returns the pub root for the content root containing a file or directory.
   * <p>
   * Based on the filesystem cache; doesn't refresh anything.
   */
  @Nullable
  public static PubRoot forDescendant(@NotNull VirtualFile fileOrDir, @NotNull Project project) {
    final ProjectFileIndex index = ProjectRootManager.getInstance(project).getFileIndex();
    final VirtualFile root = index.getContentRootForFile(fileOrDir);
    return forDirectory(root);
  }

  /**
   * Returns the PubRoot for a directory, provided it contains a pubspec.yaml file.
   * <p>
   * Otherwise returns null.
   * <p>
   * (The existence check is based on the filesystem cache; doesn't refresh anything.)
   */
  @Nullable
  public static PubRoot forDirectory(@Nullable VirtualFile dir) {
    if (dir == null || !dir.isDirectory() || dir.getPath().endsWith("/")) {
      return null;
    }

    final VirtualFile pubspec = dir.findChild("pubspec.yaml");
    if (pubspec == null || !pubspec.exists() || pubspec.isDirectory()) {
      return null;
    }

    return new PubRoot(dir, pubspec);
  }

  /**
   * Returns the PubRoot for a directory, provided it contains a pubspec.yaml file.
   * <p>
   * Refreshes the directory and the lib directory (if present). Returns null if not found.
   */
  @Nullable
  public static PubRoot forDirectoryWithRefresh(@NotNull VirtualFile dir) {
    // Ensure file existence and timestamps are up to date.
    dir.refresh(false, false);

    return forDirectory(dir);
  }

  /**
   * Returns the relative path to a file or directory within this PubRoot.
   * <p>
   * Returns null for the pub root directory, or it not within the pub root.
   */
  @Nullable
  public String getRelativePath(@NotNull VirtualFile file) {
    final String root = this.root.getPath();
    final String path = file.getPath();
    if (!path.startsWith(root) || path.length() < root.length() + 2) {
      return null;
    }
    return path.substring(root.length() + 1);
  }

  private static final String /*@NotNull*/ [] TEST_DIRS = new String[] { // TODO 2022.1
    "/test/",
    "/integration_test/",
    "/test_driver/",
    "/testing/"
  };

  /**
   * Returns true if the given file is a directory that contains tests.
   */
  public boolean hasTests(@NotNull VirtualFile dir) {
    if (!dir.isDirectory()) return false;

    // We can run tests in the pub root. (It will look in the test dir.)
    if (getRoot().equals(dir)) return true;

    String path = dir.getPath() + "/";
    // Any directory in a test dir or below is also okay.
    for (String testDir : TEST_DIRS) {
      if (path.contains(testDir)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Refreshes the pubroot and lib directories and returns an up-to-date snapshot.
   * <p>
   * Returns null if the directory or pubspec file is no longer there.
   */
  @Nullable
  public PubRoot refresh() {
    return forDirectoryWithRefresh(root);
  }

  @NotNull
  public VirtualFile getRoot() {
    return root;
  }

  @NotNull
  public String getPath() {
    return root.getPath();
  }

  @NotNull
  public VirtualFile getPubspec() {
    return pubspec;
  }

  private FlutterUtils.FlutterPubspecInfo cachedPubspecInfo;

  /**
   * Returns true if the pubspec declares a flutter dependency.
   */
  public boolean declaresFlutter() {
    validateUpdateCachedPubspecInfo();
    return cachedPubspecInfo.declaresFlutter();
  }

  /**
   * Check if the cache needs to be updated.
   */
  private void validateUpdateCachedPubspecInfo() {
    if (cachedPubspecInfo == null || cachedPubspecInfo.getModificationStamp() != pubspec.getModificationStamp()) {
      cachedPubspecInfo = FlutterUtils.getFlutterPubspecInfo(pubspec);
    }
  }

  /**
   * Returns true if the pubspec indicates that it is a Flutter plugin.
   */
  public boolean isFlutterPlugin() {
    return FlutterUtils.isFlutterPlugin(pubspec);
  }

  /**
   * Returns true if the directory content looks like a Flutter module.
   */
  public boolean isFlutterModule() {
    return root.findChild(".android") != null;
  }

  public boolean isNonEditableFlutterModule() {
    return isFlutterModule() && root.findChild("android") == null;
  }

  @Nullable
  public VirtualFile getPackageConfigFile() {
    final VirtualFile tools = root.findChild(".dart_tool");
    if (tools == null || !tools.isDirectory()) {
      return null;
    }
    final VirtualFile config = tools.findChild("package_config.json");
    if (config != null && !config.isDirectory()) {
      return config;
    }
    return null;
  }

  @Nullable
  public VirtualFile getPackagesFile() {
    // Obsolete by Flutter 2.0
    final VirtualFile packages = root.findChild(".packages");
    if (packages != null && !packages.isDirectory()) {
      return packages;
    }

    return null;
  }

  public @Nullable Map<String, String> getPackagesMap() {
    final var packageConfigFile = getPackageConfigFile();
    if (packageConfigFile != null) {
      return DotPackagesFileUtil.getPackagesMapFromPackageConfigJsonFile(packageConfigFile);
    }

    final var packagesFile = getPackagesFile();
    if (packagesFile != null) {
      return DotPackagesFileUtil.getPackagesMap(packagesFile);
    }

    return null;
  }

  /**
   * Returns true if the packages are up to date wrt pubspec.yaml.
   */
  public boolean hasUpToDatePackages() {
    final VirtualFile configFile = getPackageConfigFile();
    if (configFile != null) {
      return pubspec.getTimeStamp() < configFile.getTimeStamp();
    }
    final VirtualFile packagesFile = getPackagesFile();
    if (packagesFile == null) {
      return false;
    }

    return pubspec.getTimeStamp() < packagesFile.getTimeStamp();
  }

  @Nullable
  public VirtualFile getLib() {
    final VirtualFile lib = root.findChild("lib");
    if (lib != null && lib.isDirectory()) {
      return lib;
    }

    return null;
  }

  /**
   * Returns a file in lib if it exists.
   */
  @Nullable
  public VirtualFile getFileToOpen() {
    final VirtualFile main = getLibMain();
    if (main != null) {
      return main;
    }

    final VirtualFile lib = getLib();
    if (lib != null) {
      final VirtualFile[] files = lib.getChildren();
      if (files.length != 0) {
        return files[0];
      }
    }
    return null;
  }

  /**
   * Returns lib/main.dart if it exists.
   */
  @Nullable
  public VirtualFile getLibMain() {
    final VirtualFile lib = getLib();
    return lib == null ? null : lib.findChild("main.dart");
  }

  /**
   * Returns example/lib/main.dart if it exists.
   */
  public VirtualFile getExampleLibMain() {
    final VirtualFile exampleDir = root.findChild("example");
    if (exampleDir != null) {
      final VirtualFile libDir = exampleDir.findChild("lib");
      if (libDir != null) {
        return libDir.findChild("main.dart");
      }
    }
    return null;
  }

  @Nullable
  public VirtualFile getIntegrationTestDir() {
    return root.findChild("integration_test");
  }

  @Nullable
  public VirtualFile getExampleDir() {
    return root.findChild("example");
  }

  /**
   * Returns the android subdirectory if it exists.
   */
  @Nullable
  public VirtualFile getAndroidDir() {
    VirtualFile dir = root.findChild("android");
    if (dir == null) {
      dir = root.findChild(".android");
    }
    return dir;
  }

  /**
   * Returns the ios subdirectory if it exists.
   */
  @Nullable
  public VirtualFile getiOsDir() {
    VirtualFile dir = root.findChild("ios");
    if (dir == null) {
      dir = root.findChild(".ios");
    }
    return dir;
  }

  /**
   * Returns true if the project has a module for the "android" directory.
   */
  public boolean hasAndroidModule(Project project) {
    final VirtualFile androidDir = getAndroidDir();
    if (androidDir == null) {
      return false;
    }

    for (Module module : ModuleManager.getInstance(project).getModules()) {
      for (VirtualFile contentRoot : ModuleRootManager.getInstance(module).getContentRoots()) {
        if (contentRoot.equals(androidDir)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns the module containing this pub root, if any.
   */
  @Nullable
  public Module getModule(@NotNull Project project) {
    if (project.isDisposed()) {
      return null;
    }
    return ProjectRootManager.getInstance(project).getFileIndex().getModuleForFile(pubspec);
  }

  /**
   * Returns true if the PubRoot is an ancestor of the given file.
   */
  public boolean contains(@NotNull VirtualFile file) {
    VirtualFile dir = file.getParent();
    while (dir != null) {
      if (dir.equals(root)) {
        return true;
      }
      dir = dir.getParent();
    }
    return false;
  }

  @Override
  public String toString() {
    return "PubRoot(" + root.getName() + ")";
  }

  public static boolean isPubspec(@NotNull VirtualFile file) {
    return !file.isDirectory() && file.getName().equals("pubspec.yaml");
  }
}
