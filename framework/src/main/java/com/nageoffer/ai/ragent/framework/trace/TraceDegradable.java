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

package com.nageoffer.ai.ragent.framework.trace;

/**
 * 可降级的 Trace 返回值
 * <p>
 * 让「正常返回但内部已降级」这件事对 trace 可见：某些节点（典型如多通道检索）
 * 为了不拖垮整条链路，会把底层异常（embedding 401、向量库不可达、通道超时）
 * 吞成空结果继续往下走。方法没抛异常，{@link RagTraceNode} 切面便只能记 SUCCESS，
 * 结果是「密钥失效」与「知识库里确实没有」在 trace 上长得一模一样，无从排查。
 * <p>
 * 由 {@code @RagTraceNode} 标注的方法返回实现了本接口的对象时，切面会读取
 * {@link #traceDegradedReason()}：非空即把该节点记为 DEGRADED 并带上原因，
 * 与真正抛异常的 ERROR 区分开
 */
public interface TraceDegradable {

    /**
     * 本次执行的降级原因，返回 null / 空串表示未降级（正常成功）
     */
    String traceDegradedReason();
}
