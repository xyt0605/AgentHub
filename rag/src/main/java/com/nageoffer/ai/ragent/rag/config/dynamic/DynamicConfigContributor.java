/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.config.dynamic;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 动态配置命名空间贡献者
 * <p>
 * 每个贡献者托管一个配置命名空间（如 ai / pipeline / agent）：
 * 负责把配置 bean 导出为 JSON（敏感字段脱敏）、把保存的 JSON 应用回配置 bean
 * （实现免重启热生效）、以及在删除数据库覆盖后恢复 yaml 启动快照。
 */
public interface DynamicConfigContributor {

    /**
     * 命名空间唯一标识，与 t_dynamic_config.config_key 对应
     */
    String key();

    /**
     * 展示名称（管理后台面板用）
     */
    String label();

    /**
     * 命名空间说明
     */
    String description();

    /**
     * 导出当前生效配置（敏感字段脱敏，供前端展示与编辑）
     */
    JsonNode export();

    /**
     * 校验待保存的配置，不合法抛 ClientException
     */
    void validate(JsonNode config);

    /**
     * 合并敏感字段：入参中被脱敏（含 ***）或留空的密钥回填当前生效值，
     * 返回合并后的完整配置
     */
    JsonNode resolveSecrets(JsonNode incoming);

    /**
     * 将完整配置应用到运行时配置 bean（热生效）
     */
    void apply(JsonNode config);

    /**
     * 记录 yaml 启动快照（在应用数据库覆盖之前调用）
     */
    void snapshotYaml();

    /**
     * 恢复为 yaml 启动快照（删除数据库覆盖时调用）
     */
    void restoreYaml();
}
