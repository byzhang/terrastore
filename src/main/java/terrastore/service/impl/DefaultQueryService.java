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
package terrastore.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import terrastore.common.ErrorMessage;
import terrastore.communication.Node;
import terrastore.communication.ProcessingException;
import terrastore.communication.protocol.GetKeysCommand;
import terrastore.communication.protocol.RangeQueryCommand;
import terrastore.communication.protocol.GetBucketsCommand;
import terrastore.communication.protocol.GetValueCommand;
import terrastore.communication.protocol.GetValuesCommand;
import terrastore.router.MissingRouteException;
import terrastore.router.Router;
import terrastore.service.QueryOperationException;
import terrastore.service.QueryService;
import terrastore.service.comparators.LexicographicalComparator;
import terrastore.store.Value;
import terrastore.store.features.Predicate;
import terrastore.store.features.Range;
import terrastore.store.operators.Comparator;
import terrastore.store.operators.Condition;
import terrastore.util.collect.Maps;
import terrastore.util.collect.Sets;

/**
 * @author Sergio Bossa
 */
public class DefaultQueryService implements QueryService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultQueryService.class);
    private final Router router;
    private final Map<String, Comparator> comparators = new HashMap<String, Comparator>();
    private final Map<String, Condition> conditions = new HashMap<String, Condition>();
    private Comparator defaultComparator = new LexicographicalComparator(true);

    public DefaultQueryService(Router router) {
        this.router = router;
    }

    @Override
    public Set<String> getBuckets() throws QueryOperationException {
        try {
            LOG.debug("Getting bucket names.");
            Set<String> buckets = null;
            Set<Node> nodes = router.broadcastRoute();
            for (Node node : nodes) {
                GetBucketsCommand command = new GetBucketsCommand();
                Set<String> partial = node.<Set<String>>send(command);
                if (buckets != null) {
                    Set<String> intersection = com.google.common.collect.Sets.intersection(buckets, partial);
                    if (intersection.size() != buckets.size()) {
                        throw new QueryOperationException(new ErrorMessage(ErrorMessage.INTERNAL_SERVER_ERROR_CODE, "Missing buckets: " + intersection.toString()));
                    } else {
                        buckets.addAll(partial);
                    }
                } else {
                    buckets = new HashSet<String>(partial);
                }
            }
            return buckets;
        } catch (MissingRouteException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        } catch (ProcessingException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        }
    }

    @Override
    public Value getValue(String bucket, String key, Predicate predicate) throws QueryOperationException {
        try {
            LOG.debug("Getting value with key {} from bucket {}", key, bucket);
            Node node = router.routeToNodeFor(bucket, key);
            GetValueCommand command = null;
            if (predicate == null || predicate.isEmpty()) {
                command = new GetValueCommand(bucket, key);
            } else {
                Condition condition = getCondition(predicate.getConditionType());
                command = new GetValueCommand(bucket, key, predicate, condition);
            }
            Value result = node.<Value>send(command);
            return result;
        } catch (MissingRouteException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        } catch (ProcessingException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        }
    }

    @Override
    public Map<String, Value> getAllValues(String bucket, int limit) throws QueryOperationException {
        try {
            LOG.debug("Getting all values from bucket {}", bucket);
            Set<String> storedKeys = Sets.limited(getAllKeysForBucket(bucket), limit);
            Map<Node, Set<String>> nodeToKeys = router.routeToNodesFor(bucket, storedKeys);
            List<Map<String, Value>> allKeyValues = new ArrayList(nodeToKeys.size());
            for (Map.Entry<Node, Set<String>> nodeToKeysEntry : nodeToKeys.entrySet()) {
                Node node = nodeToKeysEntry.getKey();
                Set<String> keys = nodeToKeysEntry.getValue();
                GetValuesCommand command = new GetValuesCommand(bucket, keys);
                Map<String, Value> partial = node.<Map<String, Value>>send(command);
                allKeyValues.add(partial);
            }
            return Maps.union(allKeyValues);
        } catch (MissingRouteException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        } catch (ProcessingException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        }
    }

    @Override
    public Map<String, Value> queryByRange(String bucket, Range range, Predicate predicate, long timeToLive) throws QueryOperationException {
        try {
            LOG.debug("Range query on bucket {}", bucket);
            Comparator keyComparator = getComparator(range.getKeyComparatorName());
            Condition valueCondition = predicate.isEmpty() ? null : getCondition(predicate.getConditionType());
            Set<String> storedKeys = getKeyRangeForBucket(bucket, range, keyComparator, timeToLive);
            Map<Node, Set<String>> nodeToKeys = router.routeToNodesFor(bucket, storedKeys);
            List<Map<String, Value>> allKeyValues = new ArrayList(nodeToKeys.size());
            for (Map.Entry<Node, Set<String>> nodeToKeysEntry : nodeToKeys.entrySet()) {
                Node node = nodeToKeysEntry.getKey();
                Set<String> keys = nodeToKeysEntry.getValue();
                GetValuesCommand command = null;
                if (valueCondition == null) {
                    command = new GetValuesCommand(bucket, keys);
                } else {
                    command = new GetValuesCommand(bucket, keys, predicate, valueCondition);
                }
                Map<String, Value> partial = node.<Map<String, Value>>send(command);
                allKeyValues.add(partial);
            }
            // TODO: we may use fork/join to build the final map out of all sub-maps.
            return Maps.drain(allKeyValues, new TreeMap<String, Value>(keyComparator));
        } catch (MissingRouteException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        } catch (ProcessingException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        }
    }

    @Override
    public Map<String, Value> queryByPredicate(String bucket, Predicate predicate) throws QueryOperationException {
        try {
            LOG.debug("Predicate-based query on bucket {}", bucket);
            Condition valueCondition = predicate.isEmpty() ? null : getCondition(predicate.getConditionType());
            if (valueCondition != null) {
                Set<String> storedKeys = getAllKeysForBucket(bucket);
                Map<Node, Set<String>> nodeToKeys = router.routeToNodesFor(bucket, storedKeys);
                List<Map<String, Value>> allKeyValues = new ArrayList(nodeToKeys.size());
                for (Map.Entry<Node, Set<String>> nodeToKeysEntry : nodeToKeys.entrySet()) {
                    Node node = nodeToKeysEntry.getKey();
                    Set<String> keys = nodeToKeysEntry.getValue();
                    GetValuesCommand command = new GetValuesCommand(bucket, keys, predicate, valueCondition);
                    Map<String, Value> partial = node.<Map<String, Value>>send(command);
                    allKeyValues.add(partial);
                }
                return Maps.union(allKeyValues);
            } else {
                throw new QueryOperationException(new ErrorMessage(ErrorMessage.BAD_REQUEST_ERROR_CODE, "Wrong predicate!"));
            }
        } catch (MissingRouteException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        } catch (ProcessingException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        }
    }

    @Override
    public Comparator getDefaultComparator() {
        return defaultComparator;
    }

    @Override
    public Map<String, Comparator> getComparators() {
        return comparators;
    }

    @Override
    public Map<String, Condition> getConditions() {
        return conditions;
    }

    @Override
    public Router getRouter() {
        return router;
    }

    public void setDefaultComparator(Comparator defaultComparator) {
        this.defaultComparator = defaultComparator;
    }

    public void setComparators(Map<String, Comparator> comparators) {
        this.comparators.clear();
        this.comparators.putAll(comparators);
    }

    public void setConditions(Map<String, Condition> conditions) {
        this.conditions.clear();
        this.conditions.putAll(conditions);
    }

    private Set<String> getAllKeysForBucket(String bucket) throws QueryOperationException {
        try {
            Set<String> keys = new HashSet<String>();
            Set<Node> nodes = router.broadcastRoute();
            for (Node node : nodes) {
                GetKeysCommand command = new GetKeysCommand(bucket);
                Set<String> partial = node.<Set<String>>send(command);
                keys.addAll(partial);
            }
            return keys;
        } catch (MissingRouteException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        } catch (ProcessingException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        }
    }

    private Set<String> getKeyRangeForBucket(String bucket, Range keyRange, Comparator keyComparator, long timeToLive) throws QueryOperationException {
        try {
            Set<String> keys = new HashSet<String>();
            Set<Node> nodes = router.broadcastRoute();
            for (Node node : nodes) {
                RangeQueryCommand command = new RangeQueryCommand(bucket, keyRange, keyComparator, timeToLive);
                Set<String> partial = node.<Set<String>>send(command);
                keys.addAll(partial);
            }
            return keys;
        } catch (MissingRouteException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        } catch (ProcessingException ex) {
            LOG.error(ex.getMessage(), ex);
            ErrorMessage error = ex.getErrorMessage();
            throw new QueryOperationException(error);
        }
    }

    private Comparator getComparator(String comparatorName) {
        if (comparators.containsKey(comparatorName)) {
            return comparators.get(comparatorName);
        }
        return defaultComparator;
    }

    private Condition getCondition(String conditionType) throws QueryOperationException {
        if (conditions.containsKey(conditionType)) {
            return conditions.get(conditionType);
        } else {
            throw new QueryOperationException(new ErrorMessage(ErrorMessage.BAD_REQUEST_ERROR_CODE, "Wrong condition type: " + conditionType));
        }
    }
}
