// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.compiler.backwardRefs;

import com.intellij.compiler.server.BuildManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWithId;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.containers.Queue;
import com.intellij.util.indexing.InvertedIndexUtil;
import com.intellij.util.indexing.StorageException;
import com.intellij.util.indexing.ValueContainer;
import gnu.trove.THashSet;
import gnu.trove.TIntHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.backwardRefs.CompilerBackwardReferenceIndex;
import org.jetbrains.jps.backwardRefs.LightRef;
import org.jetbrains.jps.backwardRefs.NameEnumerator;
import org.jetbrains.jps.backwardRefs.SignatureData;
import org.jetbrains.jps.backwardRefs.index.CompilerIndices;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static java.util.stream.Collectors.toList;

class CompilerReferenceReader {
  private final static Logger LOG = Logger.getInstance(CompilerReferenceReader.class);

  private final CompilerBackwardReferenceIndex myIndex;
  private final File myBuildDir;

  private CompilerReferenceReader(File buildDir) {
    myIndex = new CompilerBackwardReferenceIndex(buildDir, true);
    myBuildDir = buildDir;
  }

  @Nullable
  TIntHashSet findReferentFileIds(@NotNull LightRef ref, boolean checkBaseClassAmbiguity) throws StorageException {
    LightRef.NamedLightRef[] hierarchy;
    if (ref instanceof LightRef.LightClassHierarchyElementDef) {
      hierarchy = new LightRef.NamedLightRef[]{(LightRef.NamedLightRef)ref};
    }
    else {
      LightRef.LightClassHierarchyElementDef hierarchyElement = ((LightRef.LightMember)ref).getOwner();
      hierarchy = getHierarchy(hierarchyElement, checkBaseClassAmbiguity, false, -1);
    }
    if (hierarchy == null) return null;
    TIntHashSet set = new TIntHashSet();
    for (LightRef.NamedLightRef aClass : hierarchy) {
      final LightRef overriderUsage = ref.override(aClass.getName());
      addUsages(overriderUsage, set);
    }
    return set;
  }

  @Nullable
  TIntHashSet findFileIdsWithImplicitToString(@NotNull LightRef ref) throws StorageException {
    TIntHashSet result = new TIntHashSet();
    myIndex.get(CompilerIndices.IMPLICIT_TO_STRING).getData(ref).forEach(
      new ValueContainer.ContainerAction<Void>() {
        @Override
        public boolean perform(int id, Void value) {
          final VirtualFile file = findFile(id);
          if (file != null) {
            result.add(((VirtualFileWithId)file).getId());
          }
          return true;
        }
      });
    return result;
  }

  /**
   * @return two maps of classes grouped per file
   *
   * 1st map: inheritors. Can be used without explicit inheritance verification
   * 2nd map: candidates. One need to check that these classes are really direct inheritors
   */
  @NotNull
  Map<VirtualFile, SearchId[]> getDirectInheritors(@NotNull LightRef searchElement,
                                                 @NotNull GlobalSearchScope searchScope,
                                                 @NotNull GlobalSearchScope dirtyScope,
                                                 @NotNull FileType fileType,
                                                 @NotNull CompilerHierarchySearchType searchType) throws StorageException {
    GlobalSearchScope effectiveSearchScope = GlobalSearchScope.notScope(dirtyScope).intersectWith(searchScope);
    LanguageLightRefAdapter adapter = LanguageLightRefAdapter.findAdapter(fileType);
    LOG.assertTrue(adapter != null, "adapter is null for file type: " + fileType);
    Class<? extends LightRef> requiredLightRefClass = searchType.getRequiredClass(adapter);

    Map<VirtualFile, SearchId[]> candidatesPerFile = new HashMap<>();
    myIndex.get(CompilerIndices.BACK_HIERARCHY).getData(searchElement).forEach((fileId, defs) -> {
        final List<LightRef> requiredCandidates = defs.stream().filter(requiredLightRefClass::isInstance).collect(toList());
        if (requiredCandidates.isEmpty()) return true;
        final VirtualFile file = findFile(fileId);
        if (file != null && effectiveSearchScope.contains(file)) {
          candidatesPerFile.put(file, searchType.convertToIds(requiredCandidates, myIndex.getByteSeqEum()));
        }
        return true;
      });
    return candidatesPerFile.isEmpty() ? Collections.emptyMap() : candidatesPerFile;
  }

  @Nullable
  Integer getAnonymousCount(@NotNull LightRef.LightClassHierarchyElementDef classDef, boolean checkDefinitions) {
    try {
      if (checkDefinitions && getDefinitionCount(classDef) != DefCount.ONE) {
        return null;
      }
      final int[] count = {0};
      myIndex.get(CompilerIndices.BACK_HIERARCHY).getData(classDef).forEach((id, value) -> {
        count[0] += value.size();
        return true;
      });
      return count[0];
    }
    catch (StorageException e) {
      throw new RuntimeException(e);
    }
  }

  int getOccurrenceCount(@NotNull LightRef element) {
    try {
      int[] result = new int[]{0};
      myIndex.get(CompilerIndices.BACK_USAGES).getData(element).forEach(
        (id, value) -> {
          result[0] += value;
          return true;
        });
      return result[0];
    }
    catch (StorageException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  List<LightRef> getMembersFor(@NotNull SignatureData data) {
    try {
      List<LightRef> result = new ArrayList<>();
      myIndex.get(CompilerIndices.BACK_MEMBER_SIGN).getData(data).forEach((id, refs) -> {
        result.addAll(refs);
        return true;
      });
      return result;
    } catch (StorageException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  TIntHashSet getAllContainingFileIds(@NotNull LightRef ref) throws StorageException {
    return InvertedIndexUtil.collectInputIdsContainingAllKeys(myIndex.get(CompilerIndices.BACK_USAGES), Collections.singletonList(ref), null, null, null);
  }

  @NotNull
  NameEnumerator getNameEnumerator() {
    return myIndex.getByteSeqEum();
  }

  void close(boolean removeIndex) {
    myIndex.close();
    if (removeIndex) {
      CompilerBackwardReferenceIndex.removeIndexFiles(myBuildDir);
    }
  }

  public CompilerBackwardReferenceIndex getIndex() {
    return myIndex;
  }

  @NotNull
  OccurrenceCounter<LightRef> getTypeCastOperands(@NotNull LightRef.LightClassHierarchyElementDef castType, @Nullable TIntHashSet fileIds) throws StorageException {
    OccurrenceCounter<LightRef> result = new OccurrenceCounter<>();
    myIndex.get(CompilerIndices.BACK_CAST).getData(castType).forEach((id, values) -> {
      if (fileIds != null && !fileIds.contains(id)) return true;
      for (LightRef ref : values) {
        result.add(ref);
      }
      return true;
    });
    return result;
  }

  static boolean exists(Project project) {
    File buildDir = BuildManager.getInstance().getProjectSystemDirectory(project);
    if (buildDir == null || CompilerBackwardReferenceIndex.versionDiffers(buildDir)) {
      return false;
    }
    return CompilerBackwardReferenceIndex.exist(buildDir);
  }

  static CompilerReferenceReader create(Project project) {
    if (!exists(project)) return null;
    try {
      return new CompilerReferenceReader(BuildManager.getInstance().getProjectSystemDirectory(project));
    }
    catch (RuntimeException e) {
      LOG.error("An exception while initialization of compiler reference index.", e);
      return null;
    }
  }

  private void addUsages(LightRef usage, TIntHashSet sink) throws StorageException {
    myIndex.get(CompilerIndices.BACK_USAGES).getData(usage).forEach(
      (id, value) -> {
        final VirtualFile file = findFile(id);
        if (file != null) {
          sink.add(((VirtualFileWithId)file).getId());
        }
        return true;
      });
  }

  private VirtualFile findFile(int id) {
    try {
      String path = myIndex.getFilePathEnumerator().valueOf(id);
      assert path != null;
      return VfsUtil.findFileByIoFile(new File(path), false);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Nullable("return null if the class hierarchy contains ambiguous qualified names")
  LightRef.LightClassHierarchyElementDef[] getHierarchy(LightRef.LightClassHierarchyElementDef hierarchyElement,
                                                        boolean checkBaseClassAmbiguity,
                                                        boolean includeAnonymous,
                                                        int interruptNumber) {
    try {
      Set<LightRef.LightClassHierarchyElementDef> result = new THashSet<>();
      Queue<LightRef.LightClassHierarchyElementDef> q = new Queue<>(10);
      q.addLast(hierarchyElement);
      while (!q.isEmpty()) {
        LightRef.LightClassHierarchyElementDef curClass = q.pullFirst();
        if (interruptNumber != -1 && result.size() > interruptNumber) {
          break;
        }
        if (result.add(curClass)) {
          if (result.size() % 100 == 0) {
            ProgressManager.checkCanceled();
          }

          if (!(curClass instanceof LightRef.LightAnonymousClassDef) && (checkBaseClassAmbiguity || curClass != hierarchyElement)) {
            if (hasMultipleDefinitions(curClass)) {
              return null;
            }
          }
          myIndex.get(CompilerIndices.BACK_HIERARCHY).getData(curClass).forEach((id, children) -> {
            for (LightRef child : children) {
              if (child instanceof LightRef.LightClassHierarchyElementDef && (includeAnonymous || !(child instanceof LightRef.LightAnonymousClassDef))) {
                q.addLast((LightRef.LightClassHierarchyElementDef)child);
              }
            }
            return true;
          });
        }
      }
      return result.toArray(LightRef.LightClassHierarchyElementDef.EMPTY_ARRAY);
    }
    catch (StorageException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  LightRef.LightClassHierarchyElementDef[] getDirectInheritors(LightRef.LightClassHierarchyElementDef hierarchyElement) throws StorageException {
    Set<LightRef.LightClassHierarchyElementDef> result = new THashSet<>();
    myIndex.get(CompilerIndices.BACK_HIERARCHY).getData(hierarchyElement).forEach((id, children) -> {
      for (LightRef child : children) {
        if (child instanceof LightRef.LightClassHierarchyElementDef && !(child instanceof LightRef.LightAnonymousClassDef)) {
          result.add((LightRef.LightClassHierarchyElementDef)child);
        }
      }
      return true;
    });
    return result.toArray(LightRef.LightClassHierarchyElementDef.EMPTY_ARRAY);
  }

  private enum DefCount { NONE, ONE, MANY}
  private boolean hasMultipleDefinitions(LightRef.NamedLightRef def) throws StorageException {
    DefCount count = getDefinitionCount(def);
    if (count == DefCount.NONE) {
      //diagnostic
      String name = def instanceof LightRef.LightAnonymousClassDef ? String.valueOf(def.getName()) : getNameEnumerator().getName(def.getName());
      LOG.error("Can't get definition files for: " + name + ", class: " + def.getClass());
    }
    return count == DefCount.MANY;
  }

  @NotNull
  private DefCount getDefinitionCount(LightRef.NamedLightRef def) throws StorageException {
    DefCount[] result = new DefCount[]{DefCount.NONE};
    myIndex.get(CompilerIndices.BACK_CLASS_DEF).getData(def).forEach((id, value) -> {
      if (result[0] == DefCount.NONE) {
        result[0] = DefCount.ONE;
        return true;
      }
      if (result[0] == DefCount.ONE) {
        result[0] = DefCount.MANY;
        return true;
      }
      return false;
    });
    return result[0];
  }
}