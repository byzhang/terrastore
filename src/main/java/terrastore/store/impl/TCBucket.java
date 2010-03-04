/**
 * Copyright 2009 - 2010 Sergio Bossa (sergio.bossa@gmail.com)
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package terrastore.store.impl;

import com.tc.cluster.DsoCluster;
import com.tc.injection.annotations.InjectedDsoInstance;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.collections.ConcurrentDistributedMap;
import org.terracotta.collections.FinegrainedLock;
import org.terracotta.collections.HashcodeLockStrategy;
import org.terracotta.collections.LockType;
import org.terracotta.modules.annotations.HonorTransient;
import org.terracotta.modules.annotations.InstrumentedClass;
import terrastore.common.ErrorMessage;
import terrastore.event.EventBus;
import terrastore.event.ValueChangedEvent;
import terrastore.event.ValueRemovedEvent;
import terrastore.store.BackupManager;
import terrastore.store.Bucket;
import terrastore.store.FlushCallback;
import terrastore.store.FlushCondition;
import terrastore.store.FlushStrategy;
import terrastore.store.SnapshotManager;
import terrastore.store.SortedSnapshot;
import terrastore.store.StoreOperationException;
import terrastore.store.features.Predicate;
import terrastore.store.features.Update;
import terrastore.store.Value;
import terrastore.store.operators.Condition;
import terrastore.store.operators.Function;
import terrastore.store.features.Range;

/**
 * @author Sergio Bossa
 */
@InstrumentedClass
@HonorTransient
public class TCBucket implements Bucket {

    private static final Logger LOG = LoggerFactory.getLogger(TCBucket.class);
    //
    private static final transient ThreadLocal<EventBus> eventBus = new ThreadLocal<EventBus>();
    private static final transient ThreadLocal<SnapshotManager> snapshotManager = new ThreadLocal<SnapshotManager>();
    private static final transient ThreadLocal<BackupManager> backupManager = new ThreadLocal<BackupManager>();
    //
    @InjectedDsoInstance
    private DsoCluster dsoCluster;
    //
    private final String name;
    private final ConcurrentDistributedMap<String, Value> bucket;

    public TCBucket(String name) {
        this.name = name;
        this.bucket = new ConcurrentDistributedMap<String, Value>(LockType.WRITE, new HashcodeLockStrategy(false, true));
    }

    public String getName() {
        return name;
    }

    public void put(String key, Value value) {
        // Use explicit locking to put and publish on the same "transactional" boundary and keep ordering under concurrency.
        lock(key);
        try {
            bucket.putNoReturn(key, value);
            TCBucket.eventBus.get().publish(new ValueChangedEvent(name, key, value.getBytes()));
        } finally {
            unlock(key);
        }
    }

    public void conditionalPut(String key, Value value, Predicate predicate, Condition condition) throws StoreOperationException {
        // Use explicit locking to put and publish on the same "transactional" boundary and keep ordering under concurrency.
        lock(key);
        try {
            Value old = bucket.get(key);
            if (old == null || old.dispatch(key, predicate, condition)) {
                bucket.putNoReturn(key, value);
                TCBucket.eventBus.get().publish(new ValueChangedEvent(name, key, value.getBytes()));
            } else {
                throw new StoreOperationException(new ErrorMessage(ErrorMessage.CONFLICT_ERROR_CODE,
                        "Unsatisfied condition: " + predicate.getConditionType() + ":" + predicate.getConditionExpression() + " for key: " + key));
            }
        } finally {
            unlock(key);
        }
    }

    public Value get(String key) throws StoreOperationException {
        Value value = bucket.get(key);
        if (value != null) {
            return value;
        } else {
            throw new StoreOperationException(new ErrorMessage(ErrorMessage.NOT_FOUND_ERROR_CODE, "Key not found: " + key));
        }
    }

    @Override
    public Value conditionalGet(String key, Predicate predicate, Condition condition) throws StoreOperationException {
        Value value = bucket.get(key);
        if (value != null) {
            if (value.dispatch(key, predicate, condition)) {
                return value;
            } else {
                return null;
            }
        } else {
            throw new StoreOperationException(new ErrorMessage(ErrorMessage.NOT_FOUND_ERROR_CODE, "Key not found: " + key));
        }
    }

    public void remove(String key) throws StoreOperationException {
        // Use explicit locking to remove and publish on the same "transactional" boundary and keep ordering under concurrency.
        lock(key);
        try {
            Value removed = bucket.remove(key);
            if (removed == null) {
                throw new StoreOperationException(new ErrorMessage(ErrorMessage.NOT_FOUND_ERROR_CODE, "Key not found: " + key));
            }
            TCBucket.eventBus.get().publish(new ValueRemovedEvent(name, key));
        } finally {
            unlock(key);
        }
    }

    @Override
    public Value update(final String key, final Update update, final Function function, final ExecutorService updateExecutor) throws StoreOperationException {
        long timeout = update.getTimeoutInMillis();
        Future<Value> task = null;
        // Use explicit locking to update and block concurrent operations on the same key,
        // and also publish on the same "transactional" boundary and keep ordering under concurrency.
        lock(key);
        try {
            final Value value = bucket.get(key);
            if (value != null) {
                task = updateExecutor.submit(new Callable<Value>() {

                    @Override
                    public Value call() {
                        return value.dispatch(key, update, function);
                    }
                });
                Value result = task.get(timeout, TimeUnit.MILLISECONDS);
                bucket.put(key, result);
                TCBucket.eventBus.get().publish(new ValueChangedEvent(name, key, result.getBytes()));
                return result;
            } else {
                throw new StoreOperationException(new ErrorMessage(ErrorMessage.NOT_FOUND_ERROR_CODE, "Key not found: " + key));
            }
        } catch (TimeoutException ex) {
            task.cancel(true);
            throw new StoreOperationException(new ErrorMessage(ErrorMessage.INTERNAL_SERVER_ERROR_CODE, "Update cancelled due to long execution time."));
        } catch (Exception ex) {
            throw new StoreOperationException(new ErrorMessage(ErrorMessage.INTERNAL_SERVER_ERROR_CODE, ex.getMessage()));
        } finally {
            unlock(key);
        }
    }

    public Set<String> keys() {
        return bucket.keySet();
    }

    public Set<String> keysInRange(Range keyRange, Comparator<String> keyComparator, long timeToLive) {
        SortedSnapshot snapshot = TCBucket.snapshotManager.get().getOrComputeSortedSnapshot(this, keyComparator, keyRange.getKeyComparatorName(), timeToLive);
        return snapshot.keysInRange(keyRange.getStartKey(), keyRange.getEndKey(), keyRange.getLimit());
    }

    @Override
    public void flush(FlushStrategy flushStrategy, FlushCondition flushCondition) {
        if (dsoCluster != null) {
            Collection<String> keys = dsoCluster.getKeysForLocalValues(bucket);
            LOG.info("Request to flush {} keys on bucket {}", keys.size(), name);
            flushStrategy.flush(this, keys, flushCondition, new FlushCallback() {

                @Override
                public void doFlush(String key) {
                    Value value = bucket.get(key);
                    bucket.flush(key, value);
                }
            });
        } else {
            LOG.warn("Running outside of cluster, no keys to flush!");
        }
    }

    @Override
    public void exportBackup(String destination) throws StoreOperationException {
        TCBucket.backupManager.get().exportBackup(this, destination);
    }

    @Override
    public void importBackup(String source) throws StoreOperationException {
        TCBucket.backupManager.get().importBackup(this, source);
    }

    @Override
    public void setEventBus(EventBus eventBus) {
        TCBucket.eventBus.set(eventBus);
    }

    @Override
    public void setSnapshotManager(SnapshotManager snapshotManager) {
        TCBucket.snapshotManager.set(snapshotManager);
    }

    @Override
    public void setBackupManager(BackupManager backupManager) {
        TCBucket.backupManager.set(backupManager);
    }

    private void lock(String key) {
        FinegrainedLock lock = bucket.createFinegrainedLock(key);
        lock.lock();
    }

    private void unlock(String key) {
        FinegrainedLock lock = bucket.createFinegrainedLock(key);
        lock.unlock();
    }
}
