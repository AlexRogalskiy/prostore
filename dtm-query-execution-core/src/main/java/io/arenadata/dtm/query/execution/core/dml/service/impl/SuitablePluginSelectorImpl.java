/*
 * Copyright © 2021 ProStore
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.arenadata.dtm.query.execution.core.dml.service.impl;

import io.arenadata.dtm.common.dml.SelectCategory;
import io.arenadata.dtm.common.reader.SourceType;
import io.arenadata.dtm.query.execution.core.plugin.configuration.properties.PluginSelectCategoryProperties;
import io.arenadata.dtm.query.execution.core.dml.service.SuitablePluginSelector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class SuitablePluginSelectorImpl implements SuitablePluginSelector {

    private final PluginSelectCategoryProperties pluginSelectCategoryProperties;

    @Autowired
    public SuitablePluginSelectorImpl(PluginSelectCategoryProperties pluginSelectCategoryProperties) {
        this.pluginSelectCategoryProperties = pluginSelectCategoryProperties;
    }

    @Override
    public Optional<SourceType> selectByCategory(SelectCategory category, Set<SourceType> acceptablePlugins) {
        List<SourceType> prioritySourceTypes = pluginSelectCategoryProperties.getMapping().get(category);
        for (SourceType sourceType: prioritySourceTypes) {
            if (acceptablePlugins.contains(sourceType)) {
                return Optional.of(sourceType);
            }
        }
        return Optional.empty();
    }
}
