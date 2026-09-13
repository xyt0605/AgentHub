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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.vo.DynamicConfigNamespaceVO;
import com.nageoffer.ai.ragent.rag.dao.entity.DynamicConfigDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.DynamicConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 动态配置服务
 * <p>
 * 聚合所有 {@link DynamicConfigContributor}，负责：
 * 启动时快照 yaml 值并应用数据库覆盖（yaml 为兜底默认，DB 记录存在则覆盖）；
 * 保存时合并密钥 → 校验 → 应用到运行时 bean（热生效）→ 持久化 → 发布变更事件；
 * 重置时删除数据库覆盖并恢复 yaml 快照。
 */
@Slf4j
@Service
public class DynamicConfigService implements SmartInitializingSingleton {

    private final List<DynamicConfigContributor> contributorList;
    private final Map<String, DynamicConfigContributor> contributors = new LinkedHashMap<>();
    private final DynamicConfigMapper dynamicConfigMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public DynamicConfigService(List<DynamicConfigContributor> contributorList,
                                DynamicConfigMapper dynamicConfigMapper,
                                ObjectMapper objectMapper,
                                ApplicationEventPublisher eventPublisher,
                                TransactionTemplate transactionTemplate) {
        this.contributorList = contributorList;
        this.dynamicConfigMapper = dynamicConfigMapper;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        contributorList.stream()
                .sorted(Comparator.comparing(DynamicConfigContributor::key))
                .forEach(contributor -> {
                    DynamicConfigContributor previous = contributors.put(contributor.key(), contributor);
                    if (previous != null) {
                        throw new IllegalStateException("动态配置命名空间重复注册: " + contributor.key());
                    }
                });
        for (DynamicConfigContributor contributor : contributors.values()) {
            contributor.snapshotYaml();
            DynamicConfigDO record = findByKey(contributor.key());
            if (record != null) {
                try {
                    contributor.apply(objectMapper.readTree(record.getConfigValue()));
                    log.info("动态配置命名空间已应用数据库覆盖: key={}", contributor.key());
                } catch (Exception e) {
                    log.error("动态配置覆盖应用失败，回落 yaml 默认: key={}", contributor.key(), e);
                }
            }
        }
        log.info("动态配置服务就绪，命名空间: {}", contributors.keySet());
    }

    /**
     * 列出全部命名空间及覆盖状态
     */
    public List<DynamicConfigNamespaceVO> listNamespaces() {
        return contributors.values().stream()
                .map(contributor -> {
                    DynamicConfigDO record = findByKey(contributor.key());
                    return DynamicConfigNamespaceVO.builder()
                            .key(contributor.key())
                            .label(contributor.label())
                            .description(contributor.description())
                            .overridden(record != null)
                            .updateBy(record == null ? null : record.getUpdateBy())
                            .updateTime(record == null ? null : record.getUpdateTime())
                            .build();
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 导出指定命名空间的生效配置（敏感字段已脱敏）
     */
    public JsonNode getEffective(String namespace) {
        return requireContributor(namespace).export();
    }

    /**
     * 保存配置：合并密钥 → 校验 → 热应用 → 持久化 → 发布事件
     */
    public void save(String namespace, JsonNode incoming, String updateBy) {
        DynamicConfigContributor contributor = requireContributor(namespace);
        JsonNode payload = unwrapResultEnvelope(incoming);
        JsonNode merged = contributor.resolveSecrets(payload);
        contributor.validate(merged);
        try {
            contributor.apply(merged);
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("配置应用失败：" + e.getMessage());
        }
        String json = merged.toString();
        Date now = new Date();
        transactionTemplate.executeWithoutResult(status -> {
            DynamicConfigDO record = findByKey(namespace);
            if (record == null) {
                record = new DynamicConfigDO();
                record.setConfigKey(namespace);
                record.setCreateTime(now);
            }
            record.setConfigValue(json);
            record.setUpdateBy(updateBy);
            record.setUpdateTime(now);
            if (record.getId() == null) {
                dynamicConfigMapper.insert(record);
            } else {
                dynamicConfigMapper.updateById(record);
            }
        });
        eventPublisher.publishEvent(new DynamicConfigUpdatedEvent(namespace, true));
        log.info("动态配置已保存并生效: key={}, updateBy={}", namespace, updateBy);
    }

    /**
     * 重置为 yaml 默认：删除数据库覆盖并恢复启动快照
     */
    public void reset(String namespace, String updateBy) {
        DynamicConfigContributor contributor = requireContributor(namespace);
        transactionTemplate.executeWithoutResult(status -> {
            DynamicConfigDO record = findByKey(namespace);
            if (record != null) {
                dynamicConfigMapper.deleteById(record.getId());
            }
        });
        contributor.restoreYaml();
        eventPublisher.publishEvent(new DynamicConfigUpdatedEvent(namespace, false));
        log.info("动态配置已重置为 yaml 默认: key={}, operator={}", namespace, updateBy);
    }

    private DynamicConfigContributor requireContributor(String namespace) {
        DynamicConfigContributor contributor = contributors.get(namespace);
        if (contributor == null) {
            throw new ClientException("未知配置命名空间: " + namespace);
        }
        return contributor;
    }

    /**
     * 防御性解包：外部调用方（如 curl 调试）可能把 GET 的完整 Result 信封
     * {"code":"0","data":{...}} 原样 PUT 回来，若不剥离会把信封当配置存库，
     * 应用时顶层找不到业务字段导致运行时配置被清空
     */
    private JsonNode unwrapResultEnvelope(JsonNode incoming) {
        if (incoming != null && incoming.isObject()
                && incoming.hasNonNull("code") && incoming.path("data").isObject()
                && (incoming.has("message") || incoming.has("requestId") || incoming.has("success"))) {
            return incoming.path("data");
        }
        return incoming;
    }

    private DynamicConfigDO findByKey(String namespace) {
        return dynamicConfigMapper.selectOne(new LambdaQueryWrapper<DynamicConfigDO>()
                .eq(DynamicConfigDO::getConfigKey, namespace));
    }

    /**
     * 供连通性测试等场景读取完整 JSON（未脱敏）
     */
    public JsonNode readStored(String namespace) {
        DynamicConfigContributor contributor = requireContributor(namespace);
        DynamicConfigDO record = findByKey(namespace);
        if (record != null) {
            try {
                return objectMapper.readTree(record.getConfigValue());
            } catch (Exception e) {
                log.warn("动态配置 JSON 解析失败，回落运行时值: key={}", namespace, e);
            }
        }
        return contributor.export();
    }

    /**
     * 判断字符串是否为脱敏占位（保存时需保留原值）
     */
    public static boolean isMaskedSecret(String value) {
        return value == null || value.isBlank() || value.contains("***");
    }

    /**
     * API Key 展示脱敏：短值全掩，长值保留首 6 末 4
     */
    public static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 10) {
            return "******";
        }
        return trimmed.substring(0, 6) + "***" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * 将（可能被 @Configuration CGLIB 代理的）配置 bean 导出为 JSON。
     * 代理类带有合成字段（$$beanFactory 等），直接序列化会失败或污染输出，
     * 故先浅拷贝到同类型的纯净实例再序列化；嵌套对象均为普通 POJO，不受代理影响。
     */
    public static <T> JsonNode exportPlain(ObjectMapper objectMapper, Class<T> type, T proxied) {
        try {
            T plain = type.getDeclaredConstructor().newInstance();
            org.springframework.beans.BeanUtils.copyProperties(proxied, plain);
            return objectMapper.valueToTree(plain);
        } catch (Exception e) {
            throw new ClientException("配置导出失败：" + e.getMessage());
        }
    }

    /**
     * 便捷方法：在 ObjectNode 上写掩码字段（值为 null 时移除）
     */
    public static void putMasked(ObjectNode node, String field, String rawValue) {
        String masked = maskSecret(rawValue);
        if (masked == null) {
            node.remove(field);
        } else {
            node.put(field, masked);
        }
    }

    /**
     * 取文本字段，缺省回退
     */
    public static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    public static boolean nonNull(Object value) {
        return Objects.nonNull(value);
    }
}
