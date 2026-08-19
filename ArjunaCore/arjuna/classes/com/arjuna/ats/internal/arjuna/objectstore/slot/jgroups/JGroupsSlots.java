/*
 * Copyright The Narayana Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arjuna.ats.internal.arjuna.objectstore.slot.jgroups;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.logging.tsLogger;
import com.arjuna.ats.internal.arjuna.objectstore.slot.BackingSlots;
import com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStoreEnvironmentBean;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import org.jgroups.blocks.ReplCache;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * A {@link com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStore} implementation backed by a jGroups cache.
 * It is an in-memory datastore running as a jGroups cluster to maintain data availability
 * (provided the caches are suitably configured to manage replication of data across the cluster).
 *
 * <p><b>NOTE</b>: This is an Experimental feature and is not recommended for production systems.
 * May contain breaking changes in future releases.
 */
public class JGroupsSlots implements BackingSlots {
    private ByteArrayKey[] slots = null;
    private ReplCache<ByteArrayKey, byte[]> cache;
    private JGroupsSlotKeyGenerator jGroupsSlotKeyGenerator;
    private short replicationCount = -1;
    private SlotJournal journal = null;

    /**
     * Overrides {@link BackingSlots#init(SlotStoreEnvironmentBean)} and has the same meaning
     * @param slotStoreConfig the config to use for the initialisation
     * @throws IOException if the cache operation threw an exception
     */
    @Override
    public void init(SlotStoreEnvironmentBean slotStoreConfig) throws IOException {
        JGroupsStoreEnvironmentBean config;

        if (slotStoreConfig instanceof JGroupsStoreEnvironmentBean) {
            config = (JGroupsStoreEnvironmentBean) slotStoreConfig;
        } else {
            config = BeanPopulator.getDefaultInstance(JGroupsStoreEnvironmentBean.class);
        }

        if (!config.isExperimentalEnabled()) {
            throw new IOException(
                    "JGroupsSlotStore is experimental and disabled by default. " +
                    "Call JGroupsStoreEnvironmentBean.setExperimentalEnabled(true) to enable it.");
        }

        tsLogger.i18NLogger.warn_jgroups_slot_store_is_experimental();

        slots = new ByteArrayKey[slotStoreConfig.getNumberOfSlots()];
        jGroupsSlotKeyGenerator = config.getSlotKeyGenerator();

        if (jGroupsSlotKeyGenerator == null) {
            jGroupsSlotKeyGenerator = new JGroupsSlotKeyGenerator() {
                @Override
                public ByteArrayKey generateUniqueKey(int index) {
                    return new ByteArrayKey(new Uid().getBytes());
                }

                @Override
                public void init(JGroupsStoreEnvironmentBean ignore) {
                }
            };
        }
        jGroupsSlotKeyGenerator.init(config);

        try {
            if (config.isWalEnabled()) {
                tsLogger.logger.infof("JGroupsSlots: Enabling write-ahead log with " +
                                "storeDir=%s, syncWrites=%s, syncDeletes=%s, fileSize=%d, minFiles=%d, asyncIO=%s",
                    config.getStoreDir(), config.isWalSyncWrites(), config.isWalSyncDeletes(),
                    config.getWalFileSize(), config.getWalMinFiles(), config.isWalAsyncIO());

                journal = new SlotJournal(config);
                journal.start();

                tsLogger.logger.debugf("JGroupsSlots: write-ahead log loaded %d slots from disk", journal.size());
            }

            cache = config.getCache();
            replicationCount = config.getReplicationCount();
            cache.start();

            Set<ByteArrayKey> existingKeys = cache.getL2Cache().getInternalMap().keySet();
            load(existingKeys);
        } catch (Exception e) {
            try {
                if (cache != null) {
                    cache.stop();
                }
            } catch (Exception ignore) {
            }
            try {
                if (journal != null) {
                    journal.stop();
                }
            } catch (Exception ignore) {
            }

            throw new IOException(e);
        }
    }

    /**
     * Overrides {@link BackingSlots#write(int, byte[], boolean)}
     * The write semantics depend on how the cache was configured {@link JGroupsStoreEnvironmentBean#setCache(ReplCache)}
     *
     * Overrides @link {BackingSlots} and has the same meaning
     *
     * @param slot the index, from 0 to config numberOfSlots-1
     * @param data the content.
     * @param sync not used (use {@link JGroupsStoreEnvironmentBean#setReplicationCount} to control how write operations
     *             behave)
     *
     * @throws IOException if the cache operation threw an exception
     */
    @Override
    public void write(int slot, byte[] data, boolean sync) throws IOException {
        try {
            if (journal != null) {
                journal.write(slot, slots[slot], data);
            }

            /*
             * cache the value until explicitly removed (timeout 0) by the transaction manager.
             * The replicationCount controls how many nodes will see the write operation,
             * -1 means don't cache at all in the L1 cache (L1 is the local cache L2 is the distributed one).
             *
             * A non-zero timeout value is the number of milliseconds to keep an idle (unaccessed) element in the cache
             * - we never want to timeout entries instead relying on the TM to explicitly remove the item when it
             * is no longer in doubt.
             */
            cache.put(slots[slot], data, replicationCount, 0);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * Overrides {@link BackingSlots#read(int)}
     * The read semantics depend on how the cache ({@link JGroupsStoreEnvironmentBean#setCache(ReplCache)} setCache(Cache)})
     * was configured
     *
     * @param slot the index, from 0 to config numberOfSlots-1
     *
     * @throws IOException if the cache operation threw an exception
     */
    @Override
    public byte[] read(int slot) throws IOException {
        try {
            byte[] data = cache.get(slots[slot]);

            if (data == null && journal != null) {
                data = journal.read(slot);
            }

            return data;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * Overrides {@link BackingSlots#clear(int, boolean)} and has the same meaning
     * @param slot the index, from 0 to config numberOfSlots-1
     * @param sync not used because the sync behaviour depends on the cache configuration
     * @throws IOException if the cache operation threw an exception
     */
    @Override
    public void clear(int slot, boolean sync) throws IOException {
        try {
            ByteArrayKey key = slots[slot];

            if (journal != null) {
                journal.delete(slot);
            }

            cache.remove(key);
            cache.getL2Cache().remove(key);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void stop() {
        if (journal != null) {
            try {
                journal.stop();
                tsLogger.logger.debugf("JGroupsSlots: write-ahead log stopped");
            } catch (Exception e) {
                tsLogger.logger.infof("JGroupsSlots: Error stopping write-ahead log: %s", e.getMessage());
            }
        }
        if (cache != null) {
            cache.stop();
        }
    }

    /**
     * Populate slots[] from WAL positions (if available) and cache keys.
     *
     * When a WAL is present, its slot-to-key mapping is authoritative: cache keys that appear
     * in the WAL are placed at their recorded positions, and WAL entries absent from the cache
     * are recovered and replicated. Remaining cache keys (those with no WAL record) are placed
     * at free positions, and any still-empty slots are filled with generated keys.
     *
     * This avoids the slot-reassignment problem: because ConcurrentHashMap iteration order is
     * non-deterministic, a naive load would assign cache keys to arbitrary slot positions,
     * causing the WAL index to disagree with the runtime layout. By consulting the WAL first,
     * the slot assignments are stable across restarts and no journal rebasing is needed.
     */
    private void load(Set<ByteArrayKey> cacheKeys) throws Exception {
        Set<ByteArrayKey> placed = new HashSet<>();

        if (journal != null) {
            boolean warned = false;
            int recoveredCount = 0;

            for (Integer slotId : journal.getSlotIds()) {
                if (slotId < 0 || slotId >= slots.length) {
                    if (!warned) {
                        tsLogger.i18NLogger.warn_slot_store_too_few_slots(journal.size(), slots.length);
                        warned = true;
                    }
                    continue;
                }

                ByteArrayKey walKey = journal.getKey(slotId);

                if (cacheKeys.contains(walKey)) {
                    slots[slotId] = walKey;
                    placed.add(walKey);
                } else {
                    byte[] data = journal.read(slotId);
                    slots[slotId] = walKey;
                    placed.add(walKey);
                    cache.put(walKey, data, replicationCount, 0);
                    recoveredCount++;
                }
            }

            tsLogger.logger.debugf("JGroupsSlots: Recovered %d slots from write-ahead log", recoveredCount);
        }

        int nextFree = 0;
        for (ByteArrayKey key : cacheKeys) {
            if (placed.contains(key)) {
                continue;
            }
            while (nextFree < slots.length && slots[nextFree] != null) {
                nextFree++;
            }
            if (nextFree >= slots.length) {
                throw new IOException(
                    tsLogger.i18NLogger.get_jgroups_too_few_slots(cacheKeys.size(), slots.length));
            }
            slots[nextFree] = key;
            nextFree++;
        }

        while (nextFree < slots.length) {
            if (slots[nextFree] == null) {
                slots[nextFree] = jGroupsSlotKeyGenerator.generateUniqueKey(nextFree);
            }
            nextFree++;
        }
    }
}
