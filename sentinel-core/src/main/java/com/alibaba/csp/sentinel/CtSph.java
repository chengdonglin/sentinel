/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.context.NullContext;
import com.alibaba.csp.sentinel.slotchain.MethodResourceWrapper;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slotchain.SlotChainProvider;
import com.alibaba.csp.sentinel.slotchain.StringResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.Rule;

/**
 * {@inheritDoc}
 *
 * @author jialiang.linjl
 * @author leyou(lihao)
 * @author Eric Zhao
 * @see Sph
 */
public class CtSph implements Sph {

    private static final Object[] OBJECTS0 = new Object[0];

    /**
     * Same resource({@link ResourceWrapper#equals(Object)}) will share the same
     * {@link ProcessorSlotChain}, no matter in which {@link Context}.
     */
    private static volatile Map<ResourceWrapper, ProcessorSlotChain> chainMap
        = new HashMap<ResourceWrapper, ProcessorSlotChain>();

    private static final Object LOCK = new Object();

    private AsyncEntry asyncEntryWithNoChain(ResourceWrapper resourceWrapper, Context context) {
        AsyncEntry entry = new AsyncEntry(resourceWrapper, null, context);
        entry.initAsyncContext();
        // The async entry will be removed from current context as soon as it has been created.
        entry.cleanCurrentEntryInLocal();
        return entry;
    }

    private AsyncEntry asyncEntryWithPriorityInternal(ResourceWrapper resourceWrapper, int count, boolean prioritized,
                                                      Object... args) throws BlockException {
        Context context = ContextUtil.getContext();
        if (context instanceof NullContext) {
            // The {@link NullContext} indicates that the amount of context has exceeded the threshold,
            // so here init the entry only. No rule checking will be done.
            return asyncEntryWithNoChain(resourceWrapper, context);
        }
        if (context == null) {
            // Using default context.
            context = InternalContextUtil.internalEnter(Constants.CONTEXT_DEFAULT_NAME);
        }

        // Global switch is turned off, so no rule checking will be done.
        if (!Constants.ON) {
            return asyncEntryWithNoChain(resourceWrapper, context);
        }

        ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);

        // Means processor cache size exceeds {@link Constants.MAX_SLOT_CHAIN_SIZE}, so no rule checking will be done.
        if (chain == null) {
            return asyncEntryWithNoChain(resourceWrapper, context);
        }

        AsyncEntry asyncEntry = new AsyncEntry(resourceWrapper, chain, context);
        try {
            chain.entry(context, resourceWrapper, null, count, prioritized, args);
            // Initiate the async context only when the entry successfully passed the slot chain.
            asyncEntry.initAsyncContext();
            // The asynchronous call may take time in background, and current context should not be hanged on it.
            // So we need to remove current async entry from current context.
            asyncEntry.cleanCurrentEntryInLocal();
        } catch (BlockException e1) {
            // When blocked, the async entry will be exited on current context.
            // The async context will not be initialized.
            asyncEntry.exitForContext(context, count, args);
            throw e1;
        } catch (Throwable e1) {
            // This should not happen, unless there are errors existing in Sentinel internal.
            // When this happens, async context is not initialized.
            RecordLog.warn("Sentinel unexpected exception in asyncEntryInternal", e1);

            asyncEntry.cleanCurrentEntryInLocal();
        }
        return asyncEntry;
    }

    private AsyncEntry asyncEntryInternal(ResourceWrapper resourceWrapper, int count, Object... args)
        throws BlockException {
        return asyncEntryWithPriorityInternal(resourceWrapper, count, false, args);
    }

    /**
     * 重要
     * @param resourceWrapper  资源示例
     * @param count 默认为1
     * @param prioritized 默认为false
     * @param args
     * @return
     * @throws BlockException
     */
    private Entry entryWithPriority(ResourceWrapper resourceWrapper, int count, boolean prioritized, Object... args)
        throws BlockException {
        // 从ThreadLocal 中获取context
        // 一个请求占用一个线程，一个线程绑定一个context
        Context context = ContextUtil.getContext();
        // 若contexts NullContext类型，表示当前系统的context梳理已经超出的阈值
        //即请求的梳理已经超出阈值，此时返回一个无需做规则检测的资源对象
        if (context instanceof NullContext) {
            // The {@link NullContext} indicates that the amount of context has exceeded the threshold,
            // so here init the entry only. No rule checking will be done.
            return new CtEntry(resourceWrapper, null, context);
        }
        // 若当前线程没有绑定context,则创建一个context,创建一个context并将其放入到ThreadLocal中
        if (context == null) {
            // Using default context.
            context = InternalContextUtil.internalEnter(Constants.CONTEXT_DEFAULT_NAME);
        }
        // 如果全局是关闭的，则直接返回一个无需做规则检测的资源对象
        // Global switch is close, no rule checking will do.
        if (!Constants.ON) {
            return new CtEntry(resourceWrapper, null, context);
        }
        // 查询SlotChain
        ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);

        /*
         * Means amount of resources (slot chain) exceeds {@link Constants.MAX_SLOT_CHAIN_SIZE},
         * so no rule checking will be done.
         */
        if (chain == null) {
            // 若没有找到 slot chain，意味请chain数量超出阈值，则直接返回一个无需做规则检测的资源对象
            return new CtEntry(resourceWrapper, null, context);
        }
        // 创建一个资源操作对象
        Entry e = new CtEntry(resourceWrapper, chain, context);
        try {
            // 对资源进行操作
            chain.entry(context, resourceWrapper, null, count, prioritized, args);
        } catch (BlockException e1) {
            e.exit(count, args);
            throw e1;
        } catch (Throwable e1) {
            // This should not happen, unless there are errors existing in Sentinel internal.
            RecordLog.info("Sentinel unexpected exception", e1);
        }
        return e;
    }

    /**
     * Do all {@link Rule}s checking about the resource.
     *
     * <p>Each distinct resource will use a {@link ProcessorSlot} to do rules checking. Same resource will use
     * same {@link ProcessorSlot} globally. </p>
     *
     * <p>Note that total {@link ProcessorSlot} count must not exceed {@link Constants#MAX_SLOT_CHAIN_SIZE},
     * otherwise no rules checking will do. In this condition, all requests will pass directly, with no checking
     * or exception.</p>
     *
     * @param resourceWrapper resource name
     * @param count           tokens needed
     * @param args            arguments of user method call
     * @return {@link Entry} represents this call
     * @throws BlockException if any rule's threshold is exceeded
     */
    public Entry entry(ResourceWrapper resourceWrapper, int count, Object... args) throws BlockException {
        return entryWithPriority(resourceWrapper, count, false, args);
    }

    /**
     * Get {@link ProcessorSlotChain} of the resource. new {@link ProcessorSlotChain} will
     * be created if the resource doesn't relate one.
     *
     * <p>Same resource({@link ResourceWrapper#equals(Object)}) will share the same
     * {@link ProcessorSlotChain} globally, no matter in witch {@link Context}.<p/>
     *
     * <p>
     * Note that total {@link ProcessorSlot} count must not exceed {@link Constants#MAX_SLOT_CHAIN_SIZE},
     * otherwise null will return.
     * </p>
     *
     * @param resourceWrapper target resource
     * @return {@link ProcessorSlotChain} of the resource
     */
    ProcessorSlot<Object> lookProcessChain(ResourceWrapper resourceWrapper) {
        // 从缓存map获取当前资源的slotChain
        // 缓存map的key为资源， value为其相关的slotChain
        ProcessorSlotChain chain = chainMap.get(resourceWrapper);
        // DCL 双重检测锁， 防止并发创建
        // 缓存没有相关的slotChain,则创建一个并法如缓存
        if (chain == null) {
            synchronized (LOCK) {
                chain = chainMap.get(resourceWrapper);
                if (chain == null) {
                    // Entry size limit 》= chain数量最大阈值，直接返回null, 不在创建新的chain
                    if (chainMap.size() >= Constants.MAX_SLOT_CHAIN_SIZE) {
                        return null;
                    }
                    // 创建新的chain
                    chain = SlotChainProvider.newSlotChain();

                    Map<ResourceWrapper, ProcessorSlotChain> newMap = new HashMap<ResourceWrapper, ProcessorSlotChain>(
                        chainMap.size() + 1);
                    newMap.putAll(chainMap);
                    newMap.put(resourceWrapper, chain);
                    chainMap = newMap;
                }
            }
        }
        return chain;
    }

    /**
     * Get current size of created slot chains.
     *
     * @return size of created slot chains
     * @since 0.2.0
     */
    public static int entrySize() {
        return chainMap.size();
    }

    /**
     * Reset the slot chain map. Only for internal test.
     *
     * @since 0.2.0
     */
    static void resetChainMap() {
        chainMap.clear();
    }

    /**
     * Only for internal test.
     *
     * @since 0.2.0
     */
    static Map<ResourceWrapper, ProcessorSlotChain> getChainMap() {
        return chainMap;
    }

    /**
     * This class is used for skip context name checking.
     */
    private final static class InternalContextUtil extends ContextUtil {
        static Context internalEnter(String name) {
            return trueEnter(name, "");
        }

        static Context internalEnter(String name, String origin) {
            return trueEnter(name, origin);
        }
    }

    @Override
    public Entry entry(String name) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, EntryType.OUT);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(Method method) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, EntryType.OUT);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, EntryType type) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(String name, EntryType type) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, EntryType type, int count) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(String name, EntryType type, int count) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, int count) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, EntryType.OUT);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(String name, int count) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, EntryType.OUT);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, EntryType type, int count, Object... args) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
        return entry(resource, count, args);
    }

    @Override
    public Entry entry(String name, EntryType type, int count, Object... args) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entry(resource, count, args);
    }

    @Override
    public AsyncEntry asyncEntry(String name, EntryType type, int count, Object... args) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return asyncEntryInternal(resource, count, args);
    }

    @Override
    public Entry entryWithPriority(String name, EntryType type, int count, boolean prioritized) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entryWithPriority(resource, count, prioritized);
    }

    @Override
    public Entry entryWithPriority(String name, EntryType type, int count, boolean prioritized, Object... args)
        throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entryWithPriority(resource, count, prioritized, args);
    }

    @Override
    public Entry entryWithType(String name, int resourceType, EntryType entryType, int count, Object[] args)
        throws BlockException {
        // count参数： 表示当前请求可用增加多少个计数
        // 注意第五个参数为false
        return entryWithType(name, resourceType, entryType, count, false, args);
    }

    @Override
    public Entry entryWithType(String name, int resourceType, EntryType entryType, int count, boolean prioritized,
                               Object[] args) throws BlockException {
        // 将信息封装为一个资源对象
        StringResourceWrapper resource = new StringResourceWrapper(name, entryType, resourceType);
        // 返回一个资源操作对象entry, 具有优先级
        // prioritized 若为true，则表示当前访问必须等待”根据优先级技术出的时间“猜可用通过
        // prioritized 若为false,则表示当前无需等待
        return entryWithPriority(resource, count, prioritized, args);
    }

    @Override
    public AsyncEntry asyncEntryWithType(String name, int resourceType, EntryType entryType, int count,
                                         boolean prioritized, Object[] args) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, entryType, resourceType);
        return asyncEntryWithPriorityInternal(resource, count, prioritized, args);
    }
}
