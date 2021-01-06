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

package org.apache.solr.common.cloud;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

import org.apache.solr.cluster.api.SimpleMap;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.annotation.JsonProperty;
import org.apache.solr.common.util.ReflectMapWriter;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.WrappedSimpleMap;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.singletonList;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.common.params.CommonParams.VERSION;

/**
 * This represents the individual replica states in a collection
 * This is an immutable object. When states are modified, a new instance is constructed
 */
public class PerReplicaStates implements ReflectMapWriter {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  public static final char SEPARATOR = ':';


  @JsonProperty
  public final String path;

  @JsonProperty
  public final int cversion;

  @JsonProperty
  public final SimpleMap<State> states;

  /**
   * Construct with data read from ZK
   * @param path path from where this is loaded
   * @param cversion the current child version of the znode
   * @param states the per-replica states (the list of all child nodes)
   */
  public PerReplicaStates(String path, int cversion, List<String> states) {
    this.path = path;
    this.cversion = cversion;
    Map<String, State> tmp = new LinkedHashMap<>();

    for (String state : states) {
      State rs = State.parse(state);
      if (rs == null) continue;
      State existing = tmp.get(rs.replica);
      if (existing == null) {
        tmp.put(rs.replica, rs);
      } else {
        tmp.put(rs.replica, rs.insert(existing));
      }
    }
    this.states = new WrappedSimpleMap<>(tmp);

  }

  /**Get the changed replicas
   */
  public static Set<String> findModifiedReplicas(PerReplicaStates old, PerReplicaStates fresh) {
    Set<String> result = new HashSet<>();
    if (fresh == null) {
      old.states.forEachKey(result::add);
      return result;
    }
    old.states.forEachEntry((s, state) -> {
      // the state is modified or missing
      if (!Objects.equals(fresh.get(s) , state)) result.add(s);
    });
    fresh.states.forEachEntry((s, state) -> { if (old.get(s) == null ) result.add(s);
    });
    return result;
  }

  /**
   * This is a persist operation with retry if a write fails due to stale state
   */
  public static void persist(WriteOps ops, String znode, SolrZkClient zkClient) throws KeeperException, InterruptedException {
    List<Operation> operations = ops.get();
    for (int i = 0; i < 10; i++) {
      try {
        persist(operations, znode, zkClient);
        return;
      } catch (KeeperException.NodeExistsException | KeeperException.NoNodeException e) {
        //state is stale
        log.info("stale state for {}. retrying...", znode);
        operations = ops.get(PerReplicaStates.fetch(znode, zkClient, null));
      }
    }
  }

  /**
   * Persist a set of operations to Zookeeper
   */
  public static void persist(List<Operation> operations, String znode, SolrZkClient zkClient) throws KeeperException, InterruptedException {
    if (operations == null || operations.isEmpty()) return;
    log.debug("Per-replica state being persisted for :{}, ops: {}", znode, operations);

    List<Op> ops = new ArrayList<>(operations.size());
    for (Operation op : operations) {
      //the state of the replica is being updated
      String path = znode + "/" + op.state.asString;
      List<ACL> acls = zkClient.getZkACLProvider().getACLsToAdd(path);
      ops.add(op.typ == Operation.Type.ADD ?
          Op.create(path, null, acls, CreateMode.PERSISTENT) :
          Op.delete(path, -1));
    }
    try {
      zkClient.multi(ops, true);
      if (log.isDebugEnabled()) {
        //nocommit
        try {
          Stat stat = zkClient.exists(znode, null, true);
          log.debug("After update, cversion : {}", stat.getCversion());
        } catch (Exception e) {
        }

      }
    } catch (KeeperException e) {
      log.error("multi op exception : " + e.getMessage() + zkClient.getChildren(znode, null, true));
      throw e;
    }

  }


  /**
   * Fetch the latest {@link PerReplicaStates} . It fetches data after checking the {@link Stat#getCversion()} of state.json.
   * If this is not modified, the same object is returned
   */
  public static PerReplicaStates fetch(String path, SolrZkClient zkClient, PerReplicaStates current) {
    try {
      if (current != null) {
        Stat stat = zkClient.exists(current.path, null, true);
        if (stat == null) return new PerReplicaStates(path, -1, Collections.emptyList());
        if (current.cversion == stat.getCversion()) return current;// not modifiedZkStateReaderTest
      }
      Stat stat = new Stat();
      List<String> children = zkClient.getChildren(path, null, stat, true);
      return new PerReplicaStates(path, stat.getCversion(), Collections.unmodifiableList(children));
    } catch (KeeperException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error fetching per-replica states", e);
    } catch (InterruptedException e) {
      SolrZkClient.checkInterrupted(e);
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Thread interrupted when loading per-replica states from " + path, e);
    }
  }


  private static List<Operation> addDeleteStaleNodes(List<Operation> ops, State rs) {
    while (rs != null) {
      ops.add(new Operation(Operation.Type.DELETE, rs));
      rs = rs.duplicate;
    }
    return ops;
  }

  public static String getReplicaName(String s) {
    int idx = s.indexOf(SEPARATOR);
    if (idx > 0) {
      return s.substring(0, idx);
    }
    return null;
  }

  public State get(String replica) {
    return states.get(replica);
  }

  public static class Operation {
    public final Type typ;
    public final State state;

    public Operation(Type typ, State replicaState) {
      this.typ = typ;
      this.state = replicaState;
    }


    public enum Type {
      //add a new node
      ADD,
      //delete an existing node
      DELETE
    }

    @Override
    public String toString() {
      return typ.toString() + " : " + state;
    }
  }


  /**
   * The state of a replica as stored as a node under /collections/collection-name/state.json/replica-state
   */
  public static class State implements MapWriter {

    public final String replica;

    public final Replica.State state;

    public final Boolean isLeader;

    public final int version;

    public final String asString;

    /**
     * if there are multiple entries for the same replica, e.g: core_node_1:12:A core_node_1:13:D
     * <p>
     * the entry with '13' is the latest and the one with '12' is considered a duplicate
     * <p>
     * These are unlikely, but possible
     */
    final State duplicate;

    private State(String serialized, List<String> pieces) {
      this.asString = serialized;
      replica = pieces.get(0);
      version = Integer.parseInt(pieces.get(1));
      String encodedStatus = pieces.get(2);
      this.state = Replica.getState(encodedStatus);
      isLeader = pieces.size() > 3 && "L".equals(pieces.get(3));
      duplicate = null;
    }

    public static State parse(String serialized) {
      List<String> pieces = StrUtils.splitSmart(serialized, ':');
      if (pieces.size() < 3) return null;
      return new State(serialized, pieces);

    }

    public State(String replica, Replica.State state, Boolean isLeader, int version) {
      this(replica, state, isLeader, version, null);
    }

    public State(String replica, Replica.State state, Boolean isLeader, int version, State duplicate) {
      this.replica = replica;
      this.state = state == null ? Replica.State.ACTIVE : state;
      this.isLeader = isLeader == null ? Boolean.FALSE : isLeader;
      this.version = version;
      asString = serialize();
      this.duplicate = duplicate;
    }

    @Override
    public void writeMap(EntryWriter ew) throws IOException {
      ew.put(NAME, replica);
      ew.put(VERSION, version);
      ew.put(ZkStateReader.STATE_PROP, state.toString());
      if (isLeader) ew.put(Slice.LEADER, isLeader);
      ew.putIfNotNull("duplicate", duplicate);
    }

    private State insert(State duplicate) {
      assert this.replica.equals(duplicate.replica);
      if (this.version >= duplicate.version) {
        if (this.duplicate != null) {
          duplicate = new State(duplicate.replica, duplicate.state, duplicate.isLeader, duplicate.version, this.duplicate);
        }
        return new State(this.replica, this.state, this.isLeader, this.version, duplicate);
      } else {
        return duplicate.insert(this);
      }
    }

    /**
     * fetch duplicates entries for this replica
     */
    List<State> getDuplicates() {
      if (duplicate == null) return Collections.emptyList();
      List<State> result = new ArrayList<>();
      State current = duplicate;
      while (current != null) {
        result.add(current);
        current = current.duplicate;
      }
      return result;
    }

    private String serialize() {
      StringBuilder sb = new StringBuilder(replica)
          .append(":")
          .append(version)
          .append(":")
          .append(state.shortName);
      if (isLeader) sb.append(":").append("L");
      return sb.toString();
    }


    @Override
    public String toString() {
      return asString;
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof State) {
        State that = (State) o;
        return Objects.equals(this.asString, that.asString);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return asString.hashCode();
    }
  }


  /**This is a helper class that encapsulates various operations performed on the per-replica states
   * Do not directly manipulate the per replica states as it can become difficult to debug them
   *
   */
  public static abstract class WriteOps {
    private PerReplicaStates rs;
    List<Operation> ops;
    private boolean preOp = true;

    /**
     * state of a replica is changed
     *
     * @param newState the new state
     */
    public static WriteOps flipState(String replica, Replica.State newState, PerReplicaStates rs) {
      return new WriteOps() {
        @Override
        protected List<Operation> refresh(PerReplicaStates rs) {
          List<Operation> ops = new ArrayList<>(2);
          State existing = rs.get(replica);
          if (existing == null) {
            ops.add(new Operation(Operation.Type.ADD, new State(replica, newState, Boolean.FALSE, 0)));
          } else {
            ops.add(new Operation(Operation.Type.ADD, new State(replica, newState, existing.isLeader, existing.version + 1)));
            addDeleteStaleNodes(ops, existing);
          }
          if (log.isDebugEnabled()) {
            log.debug("flipState on {}, {} -> {}, ops :{}", rs.path, replica, newState, ops);
          }
          return ops;
        }
      }.init(rs);
    }

    public PerReplicaStates getPerReplicaStates() {
      return rs;
    }


    /**Switch a collection from/to perReplicaState=true
     */
    public static WriteOps modifyCollection(DocCollection coll, boolean enable, PerReplicaStates prs) {
      return new WriteOps() {
        @Override
        List<Operation> refresh(PerReplicaStates prs) {
          return enable ? enable(coll) : disable(prs);
        }

        List<Operation> enable(DocCollection coll) {
          List<Operation> result = new ArrayList<>();
          coll.forEachReplica((s, r) -> result.add(new Operation(Operation.Type.ADD, new State(r.getName(), r.getState(), r.isLeader(), 0))));
          return result;
        }

        List<Operation> disable(PerReplicaStates prs) {
          List<Operation> result = new ArrayList<>();
          prs.states.forEachEntry((s, state) -> result.add(new Operation(Operation.Type.DELETE, state)));
          return result;
        }
      }.init(prs);

    }

    /**
     * Flip the leader replica to a new one
     *
     * @param allReplicas  allReplicas of the shard
     * @param next next leader
     */
    public static WriteOps flipLeader(Set<String> allReplicas, String next, PerReplicaStates rs) {
      return new WriteOps() {

        @Override
        protected List<Operation> refresh(PerReplicaStates rs) {
          List<Operation> ops = new ArrayList<>();
          if (next != null) {
            State st = rs.get(next);
            if (st != null) {
              if (!st.isLeader) {
                ops.add(new Operation(Operation.Type.ADD, new State(st.replica, Replica.State.ACTIVE, Boolean.TRUE, st.version + 1)));
                ops.add(new Operation(Operation.Type.DELETE, st));
              }
              //else do not do anything , that node is the leader
            } else {
              //there is no entry for the new leader.
              //create one
              ops.add(new Operation(Operation.Type.ADD, new State(next, Replica.State.ACTIVE, Boolean.TRUE, 0)));
            }
          }

          //now go through all other replicas and unset previous leader
          for (String r : allReplicas) {
            State st = rs.get(r);
            if (st == null) continue;//unlikely
            if (!Objects.equals(r, next)) {
              if (st.isLeader) {
                //some other replica is the leader now. unset
                ops.add(new Operation(Operation.Type.ADD, new State(st.replica, st.state, Boolean.FALSE, st.version + 1)));
                ops.add(new Operation(Operation.Type.DELETE, st));
              }
            }
          }
          if (log.isDebugEnabled()) {
            log.debug("flipLeader on:{}, {} -> {}, ops: {}", rs.path, allReplicas, next, ops);
          }
          return ops;
        }

      }.init(rs);
    }

    /**
     * Delete a replica entry from per-replica states
     *
     * @param replica name of the replica to be deleted
     */
    public static WriteOps deleteReplica(String replica, PerReplicaStates rs) {
      return new WriteOps() {
        @Override
        protected List<Operation> refresh(PerReplicaStates rs) {
          List<Operation> result;
          if (rs == null) {
            result = Collections.emptyList();
          } else {
            State state = rs.get(replica);
            result = addDeleteStaleNodes(new ArrayList<>(), state);
          }
          return result;
        }
      }.init(rs);
    }

    public static WriteOps addReplica(String replica, Replica.State state, boolean isLeader, PerReplicaStates rs) {
      return new WriteOps() {
        @Override
        protected List<Operation> refresh(PerReplicaStates rs) {
          return singletonList(new Operation(Operation.Type.ADD,
              new State(replica, state, isLeader, 0)));
        }
      }.init(rs);
    }

    /**
     * mark a bunch of replicas as DOWN
     */
    public static WriteOps downReplicas(List<String> replicas, PerReplicaStates rs) {
      return new WriteOps() {
        @Override
        List<Operation> refresh(PerReplicaStates rs) {
          List<Operation> ops = new ArrayList<>();
          for (String replica : replicas) {
            State r = rs.get(replica);
            if (r != null) {
              if (r.state == Replica.State.DOWN && !r.isLeader) continue;
              ops.add(new Operation(Operation.Type.ADD, new State(replica, Replica.State.DOWN, Boolean.FALSE, r.version + 1)));
              addDeleteStaleNodes(ops, r);
            } else {
              ops.add(new Operation(Operation.Type.ADD, new State(replica, Replica.State.DOWN, Boolean.FALSE, 0)));
            }
          }
          if (log.isDebugEnabled()) {
            log.debug("for coll: {} down replicas {}, ops {}", rs, replicas, ops);
          }
          return ops;
        }
      }.init(rs);
    }

    /**
     * Just creates and deletes a summy entry so that the {@link Stat#getCversion()} of states.json
     * is updated
     */
    public static WriteOps touchChildren() {
      WriteOps result = new WriteOps() {
        @Override
        List<Operation> refresh(PerReplicaStates rs) {
          List<Operation> ops = new ArrayList<>();
          State st = new State(".dummy." + System.nanoTime(), Replica.State.DOWN, Boolean.FALSE, 0);
          ops.add(new Operation(Operation.Type.ADD, st));
          ops.add(new Operation(Operation.Type.DELETE, st));
          if (log.isDebugEnabled()) {
            log.debug("touchChildren {}", ops);
          }
          return ops;
        }
      };
      result.preOp = false;
      result.ops = result.refresh(null);
      return result;
    }

    WriteOps init(PerReplicaStates rs) {
      if (rs == null) return null;
      get(rs);
      return this;
    }

    public List<Operation> get() {
      return ops;
    }

    public List<Operation> get(PerReplicaStates rs) {
      ops = refresh(rs);
      if (ops == null) ops = Collections.emptyList();
      this.rs = rs;
      return ops;
    }

    /**
     * To be executed before collection state.json is persisted
     */
    public boolean isPreOp() {
      return preOp;
    }

    /**
     * if a multi operation fails because the state got modified from behind,
     * refresh the operation and try again
     *
     * @param prs The new state
     */
    abstract List<Operation> refresh(PerReplicaStates prs);

    @Override
    public String toString() {
      return ops.toString();
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("{").append(path).append("/[").append(cversion).append("]: [");
    appendStates(sb);
    return sb.append("]}").toString();
  }

  private StringBuilder appendStates(StringBuilder sb) {
    states.forEachEntry(new BiConsumer<String, State>() {
      int count = 0;
      @Override
      public void accept(String s, State state) {
        if (count++ > 0) sb.append(", ");
        sb.append(state.asString);
        for (State d : state.getDuplicates()) sb.append(d.asString);
      }
    });
    return sb;
  }

}