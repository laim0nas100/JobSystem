package com.github.laim0nas100.jobsystem.events;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 *
 * @author laim0nas100
 */
public class EventListeners {

    //should be reassigned at executor
    protected Map<Serializable, List<JobEventListener>> jobExecutorMap = Collections.EMPTY_MAP;

    protected Map<Serializable, List<JobEventListener>> extendableMap;

    protected transient List<JobEventListener> allValues;

    public EventListeners() {
    }

    public void assignJobExecutorMap(Map<Serializable, List<JobEventListener>> map) {
        allValues = null;//reset
        jobExecutorMap = Objects.requireNonNull(map);
    }

    protected Map<Serializable, List<JobEventListener>> getMap(boolean init) {
        if (extendableMap == null) {
            if (init) {
                extendableMap = new HashMap<>();
            } else {
                return Collections.EMPTY_MAP;
            }
        }
        return extendableMap;
    }

    public int size() {
        return jobExecutorMap.size() + getMap(false).size();
    }

    public boolean isEmpty() {
        return jobExecutorMap.isEmpty() && getMap(false).isEmpty();
    }

    public boolean containsKey(Serializable key) {
        return jobExecutorMap.containsKey(key) || getMap(false).containsKey(key);
    }

    public boolean containsValue(JobEventListener value) {
        return this.values().contains(value);
    }

    public List<JobEventListener> get(Serializable key) {

        List<JobEventListener> first = jobExecutorMap.getOrDefault(key, null);
        List<JobEventListener> second = getMap(false).getOrDefault(key, null);
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        //both present
        List<JobEventListener> both = new ArrayList<>(first.size() + second.size());
        both.addAll(first);
        both.addAll(second);
        return both;

    }

    public void addAll(Serializable key, List<JobEventListener> list) {
        allValues = null;//reset
        getMap(true).computeIfAbsent(key, k -> new ArrayList<>()).addAll(list);
    }

    public void add(Serializable key, JobEventListener value) {
        allValues = null;//reset
        getMap(true).computeIfAbsent(key, k -> new ArrayList<>(1)).add(value);
        
    }

    public List<JobEventListener> remove(Serializable key) {
        allValues = null;//reset
        return getMap(true).remove(key);
    }

    public void putAll(Map<? extends Serializable, ? extends List<JobEventListener>> m) {
        allValues = null;//reset
        getMap(true).putAll(m);
    }

    public void clear() {
        allValues = null;//reset
        getMap(false).clear();
    }

    /**
     * Doesn't follow the conventional Map semantics where key removal reflects
     * on the map. Combines both key sets in a new set;
     *
     * {@inheritDoc}
     *
     * @return
     */
    public Set<Serializable> keySet() {
        Set<Serializable> keys = new HashSet<>();
        keys.addAll(jobExecutorMap.keySet());
        keys.addAll(getMap(false).keySet());
        return keys;
    }

    /**
     * Doesn't follow the conventional Map semantics where value removal reflects
     * on the map. Combines both key value in a new list;
     *
     * {@inheritDoc}
     *
     * @return
     */
    public List<JobEventListener> values() {
        if (allValues != null) {
            return allValues;
        }
        List<JobEventListener> all = new ArrayList<>();
        for (List<JobEventListener> values : jobExecutorMap.values()) {
            all.addAll(values);
        }
        for (List<JobEventListener> values : getMap(false).values()) {
            all.addAll(values);
        }
        return allValues = all;
    }

}
