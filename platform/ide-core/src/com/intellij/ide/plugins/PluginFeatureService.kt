// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceGetOrSet", "ReplacePutWithAssignment")

package com.intellij.ide.plugins

import com.intellij.ide.plugins.advertiser.FeaturePluginData
import com.intellij.ide.plugins.advertiser.PluginData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.platform.settings.CacheStateTag
import com.intellij.platform.settings.objectSettingValueSerializer
import com.intellij.platform.settings.settingDescriptorFactoryFactory
import com.intellij.util.concurrency.annotations.RequiresBlockingContext
import kotlinx.serialization.Serializable
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls

@ApiStatus.Internal
@Service(Service.Level.APP)
class PluginFeatureService {
  companion object {
    fun getInstance(): PluginFeatureService {
      return ApplicationManager.getApplication().getService(PluginFeatureService::class.java)
    }

    @Suppress("FunctionName")
    @RequiresBlockingContext
    fun __getPluginForFeature(featureType: @NonNls String, implementationName: @NonNls String): FeaturePluginData? {
      return runBlockingCancellable {
        serviceAsync<PluginFeatureService>().getPluginForFeature(featureType, implementationName)
      }
    }
  }

  @Serializable
  data class FeaturePluginList(
    @JvmField val featureMap: Map<@NonNls String, FeaturePluginData> = emptyMap(),
  )

  private val serializer = objectSettingValueSerializer<FeaturePluginList>()
  private val settingGroup = settingDescriptorFactoryFactory(PluginId.getId("com.intellij")).group(key = "pluginFeature") {
    tags = listOf(CacheStateTag)
  }

  suspend fun <T : Any> collectFeatureMapping(
    featureType: @NonNls String,
    ep: ExtensionPointName<T>,
    idMapping: (T) -> @NonNls String,
    displayNameMapping: (T) -> @Nls String,
  ) {
    val featureMap = LinkedHashMap<@NonNls String, FeaturePluginData>()
    // fold
    ep.processWithPluginDescriptor { ext, descriptor ->
      featureMap.put(idMapping(ext), FeaturePluginData(displayName = displayNameMapping(ext), pluginData = PluginData(descriptor)))
    }

    updateFeatureMapping(featureType, featureMap)
  }

  private suspend fun updateFeatureMapping(featureType: @NonNls String, featureMap: Map<@NonNls String, FeaturePluginData>) {
    val setting = settingGroup.setting(featureType, serializer)
    val existingMap = setting.get()
    if (existingMap == null) {
      setting.set(FeaturePluginList(featureMap))
    }
    else {
      val newMap = LinkedHashMap<@NonNls String, FeaturePluginData>()
      newMap.putAll(existingMap.featureMap)
      newMap.putAll(featureMap)
      setting.set(FeaturePluginList(newMap))
    }
  }

  suspend fun getPluginForFeature(featureType: @NonNls String, implementationName: @NonNls String): FeaturePluginData? {
    return settingGroup.setting(featureType, serializer).get()?.featureMap?.get(implementationName)
  }
}
