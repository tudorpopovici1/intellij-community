// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.jps.impl;

import com.intellij.openapi.extensions.*;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.util.Ref;
import com.intellij.util.containers.ContainerUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.JpsElement;
import org.jetbrains.jps.model.module.JpsModuleSourceRootType;
import org.jetbrains.jps.model.module.UnknownSourceRootType;
import org.jetbrains.jps.model.module.UnknownSourceRootTypeProperties;
import org.jetbrains.jps.model.serialization.JpsModelSerializerExtension;
import org.jetbrains.jps.model.serialization.module.JpsModuleRootModelSerializer;
import org.jetbrains.jps.model.serialization.module.JpsModuleSourceRootPropertiesSerializer;
import org.jetbrains.jps.model.serialization.module.UnknownSourceRootPropertiesSerializer;
import org.jetbrains.jps.plugin.JpsPluginManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class JpsIdePluginManagerImpl extends JpsPluginManager {
  private final List<PluginDescriptor> myExternalBuildPlugins = new CopyOnWriteArrayList<>();
  private final AtomicInteger myModificationStamp = new AtomicInteger(0);

  public JpsIdePluginManagerImpl() {
    ExtensionsArea rootArea = Extensions.getRootArea();
    if (rootArea == null) {
      return;
    }

    //todo[nik] get rid of this check: currently this class is used in intellij.platform.jps.build tests instead of JpsPluginManagerImpl because intellij.platform.ide.impl module is added to classpath via testFramework
    if (rootArea.hasExtensionPoint(JpsPluginBean.EP_NAME)) {
      final Ref<Boolean> initial = new Ref<>(Boolean.TRUE);
      JpsPluginBean.EP_NAME.getPoint(null).addExtensionPointListener(new ExtensionPointListener<JpsPluginBean>() {
        @Override
        public void extensionAdded(@NotNull JpsPluginBean extension, @NotNull PluginDescriptor pluginDescriptor) {
          if (initial.get()) {
            myExternalBuildPlugins.add(pluginDescriptor);
          }
          else {
            handlePluginAdded(pluginDescriptor);
          }
        }

        @Override
        public void extensionRemoved(@NotNull JpsPluginBean extension, @NotNull PluginDescriptor pluginDescriptor) {
          handlePluginRemoved(pluginDescriptor);
        }
      }, true, null);
      initial.set(Boolean.FALSE);
    }
    if (rootArea.hasExtensionPoint("com.intellij.compileServer.plugin")) {
      ExtensionPoint extensionPoint = rootArea.getExtensionPoint("com.intellij.compileServer.plugin");
      final Ref<Boolean> initial = new Ref<>(Boolean.TRUE);
      //noinspection unchecked
      extensionPoint.addExtensionPointListener(new ExtensionPointListener() {
        @Override
        public void extensionAdded(@NotNull Object extension, @NotNull PluginDescriptor pluginDescriptor) {
          if (initial.get()) {
            myExternalBuildPlugins.add(pluginDescriptor);
          }
          else {
            handlePluginAdded(pluginDescriptor);
          }
        }

        @Override
        public void extensionRemoved(@NotNull Object extension, @NotNull PluginDescriptor pluginDescriptor) {
          handlePluginRemoved(pluginDescriptor);
        }
      }, true, null);
      initial.set(Boolean.FALSE);
    }
  }

  private void handlePluginRemoved(@NotNull PluginDescriptor pluginDescriptor) {
    Map<JpsModuleSourceRootType<?>, JpsModuleSourceRootPropertiesSerializer<?>> removed = new HashMap<>();
    for (JpsModelSerializerExtension extension : loadExtensions(JpsModelSerializerExtension.class)) {
      for (JpsModuleSourceRootPropertiesSerializer<?> serializer : extension.getModuleSourceRootPropertiesSerializers()) {
        removed.put(serializer.getType(), serializer);
      }
    }

    if (myExternalBuildPlugins.remove(pluginDescriptor)) {
      myModificationStamp.incrementAndGet();
    }
    else{
      return;
    }

    for (JpsModelSerializerExtension extension : loadExtensions(JpsModelSerializerExtension.class)) {
      for (JpsModuleSourceRootPropertiesSerializer<?> serializer : extension.getModuleSourceRootPropertiesSerializers()) {
        removed.remove(serializer.getType());
      }
    }

    if (!removed.isEmpty()) {
      for (Project project : ProjectManager.getInstance().getOpenProjects()) {
        replaceWithUnknownRootType(project, removed.values());
      }
    }
  }

  private void handlePluginAdded(@NotNull PluginDescriptor pluginDescriptor) {
    if (myExternalBuildPlugins.contains(pluginDescriptor)) {
      return;
    }
    Set<String> before = new HashSet<>();
    for (JpsModelSerializerExtension extension : loadExtensions(JpsModelSerializerExtension.class)) {
      for (JpsModuleSourceRootPropertiesSerializer<?> serializer : extension.getModuleSourceRootPropertiesSerializers()) {
        before.add(serializer.getTypeId());
      }
    }

    myExternalBuildPlugins.add(pluginDescriptor);
    myModificationStamp.incrementAndGet();

    Map<String, JpsModuleSourceRootPropertiesSerializer<?>> added = new HashMap<>();
    for (JpsModelSerializerExtension extension : loadExtensions(JpsModelSerializerExtension.class)) {
      for (JpsModuleSourceRootPropertiesSerializer<?> serializer : extension.getModuleSourceRootPropertiesSerializers()) {
        added.put(serializer.getTypeId(), serializer);
      }
    }
    added.keySet().removeAll(before);
    
    if (!added.isEmpty()) {
      for (Project project : ProjectManager.getInstance().getOpenProjects()) {
        updateCustomRootTypes(project, added.values());
      }
    }
  }

  public static void replaceWithUnknownRootType(Project project, Collection<JpsModuleSourceRootPropertiesSerializer<?>> unregisteredSerializers) {
    if (unregisteredSerializers.isEmpty()) {
      return;
    }
    Map<JpsModuleSourceRootType<?>, JpsModuleSourceRootPropertiesSerializer<?>> serializers = new HashMap<>();
    for (JpsModuleSourceRootPropertiesSerializer<?> serializer : unregisteredSerializers) {
      serializers.put(serializer.getType(), serializer);
    }
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      ModuleRootModificationUtil.modifyModel(module, model -> {
        boolean shouldCommit = false;
        for (ContentEntry contentEntry : model.getContentEntries()) {
          for (SourceFolder folder : contentEntry.getSourceFolders()) {
            JpsModuleSourceRootPropertiesSerializer<?> removedSerializer = serializers.get(folder.getRootType());
            if (removedSerializer != null) {
              changeType(
                folder,
                UnknownSourceRootPropertiesSerializer.forType(removedSerializer.getTypeId(), folder.getRootType().isForTests()),
                serializeProperties(folder, removedSerializer)
              );
              shouldCommit = true;
            }
          }
        }
        return shouldCommit;
      });
    }
  }

  public static void updateCustomRootTypes(Project project, Collection<JpsModuleSourceRootPropertiesSerializer<?>> registeredSerializers) {
    if (registeredSerializers.isEmpty()) {
      return;
    }
    Map<String, JpsModuleSourceRootPropertiesSerializer<?>> serializers = new HashMap<>();
    for (JpsModuleSourceRootPropertiesSerializer<?> ser : registeredSerializers) {
      serializers.put(ser.getTypeId(), ser);
    }
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      ModuleRootModificationUtil.modifyModel(module, model -> {
        boolean shouldCommit = false;
        for (ContentEntry contentEntry : model.getContentEntries()) {
          for (SourceFolder folder : contentEntry.getSourceFolders()) {
            if (folder.getRootType() instanceof UnknownSourceRootType) {
              UnknownSourceRootType type = (UnknownSourceRootType)folder.getRootType();
              JpsModuleSourceRootPropertiesSerializer<?> serializer = serializers.get(type.getUnknownTypeId());
              if (serializer != null) {
                UnknownSourceRootTypeProperties<?> properties = folder.getJpsElement().getProperties(type);
                Object data = properties != null? properties.getPropertiesData() : null;
                changeType(folder, serializer, data instanceof Element ? (Element)data : null);
                shouldCommit = true;
              }
            }
          }
        }
        return shouldCommit;
      });
    }
  }

  @Nullable
  private static <P extends JpsElement> Element serializeProperties(SourceFolder root, @NotNull JpsModuleSourceRootPropertiesSerializer<P> serializer) {
    P properties = root.getJpsElement().getProperties(serializer.getType());
    if (properties != null) {
      Element sourceElement = new Element(JpsModuleRootModelSerializer.SOURCE_FOLDER_TAG);
      serializer.saveProperties(properties, sourceElement);
      return sourceElement;
    }
    return null;
  }

  private static <P extends JpsElement> void changeType(SourceFolder root, @NotNull JpsModuleSourceRootPropertiesSerializer<P> serializer, @Nullable Element serializedProps) {
    root.changeType(
      serializer.getType(),
      serializedProps != null ? serializer.loadProperties(serializedProps) : serializer.getType().createDefaultProperties()
    );
  }

  @Override
  public int getModificationStamp() {
    return myModificationStamp.get();
  }

  @NotNull
  @Override
  public <T> Collection<T> loadExtensions(@NotNull Class<T> extensionClass) {
    Set<ClassLoader> loaders = new LinkedHashSet<>();
    for (PluginDescriptor plugin : myExternalBuildPlugins) {
      ContainerUtil.addIfNotNull(loaders, plugin.getPluginClassLoader());
    }
    if (loaders.isEmpty()) {
      loaders.add(getClass().getClassLoader());
    }
    return loadExtensionsFrom(loaders, extensionClass);
  }

  @NotNull
  private static <T> Collection<T> loadExtensionsFrom(@NotNull Collection<ClassLoader> loaders, @NotNull Class<T> extensionClass) {
    if (loaders.isEmpty()) {
      return Collections.emptyList();
    }
    String resourceName = "META-INF/services/" + extensionClass.getName();
    Set<Class<T>> classes = new LinkedHashSet<>();
    Set<String> loadedUrls = new HashSet<>();
    for (ClassLoader loader : loaders) {
      try {
        Enumeration<URL> resources = loader.getResources(resourceName);
        while (resources.hasMoreElements()) {
          URL url = resources.nextElement();
          if (loadedUrls.add(url.toExternalForm())) {
            loadImplementations(url, loader, classes);
          }
        }
      }
      catch (IOException e) {
        throw new ServiceConfigurationError("Cannot load configuration files for " + extensionClass.getName(), e);
      }
    }
    List<T> extensions = new ArrayList<>();
    for (Class<T> aClass : classes) {
      try {
        extensions.add(extensionClass.cast(aClass.newInstance()));
      }
      catch (Exception e) {
        throw new ServiceConfigurationError("Class " + aClass.getName() + " cannot be instantiated", e);
      }
    }
    return extensions;
  }

  private static <T> void loadImplementations(URL url, ClassLoader loader, Set<? super Class<T>> result) throws IOException {
    for (String name : loadClassNames(url)) {
      try {
        //noinspection unchecked
        result.add((Class<T>)Class.forName(name, false, loader));
      }
      catch (ClassNotFoundException e) {
        throw new ServiceConfigurationError("Cannot find class " + name, e);
      }
    }
  }

  private static List<String> loadClassNames(URL url) throws IOException {
    List<String> result = new ArrayList<>();
    try (BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        int i = line.indexOf('#');
        if (i >= 0) line = line.substring(0, i);
        line = line.trim();
        if (!line.isEmpty()) {
          result.add(line);
        }
      }
    }
    return result;
  }
}
