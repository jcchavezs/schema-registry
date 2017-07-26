/**
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.kafka.schemaregistry.masterelector.kafka;

import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.MockClient;
import org.apache.kafka.clients.consumer.internals.ConsumerNetworkClient;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.requests.FindCoordinatorResponse;
import org.apache.kafka.common.requests.JoinGroupRequest.ProtocolMetadata;
import org.apache.kafka.common.requests.JoinGroupResponse;
import org.apache.kafka.common.requests.SyncGroupRequest;
import org.apache.kafka.common.requests.SyncGroupResponse;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.test.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.confluent.kafka.schemaregistry.storage.SchemaRegistryIdentity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SchemaRegistryCoordinatorTest {

  private static final String LEADER_ID = "leader";
  private static final String MEMBER_ID = "member";
  private static final String LEADER_HOST = "leaderHost";
  private static final int LEADER_PORT = 8083;

  private static final SchemaRegistryIdentity LEADER_INFO = new SchemaRegistryIdentity(
      LEADER_HOST,
      LEADER_PORT,
      true
  );
  private static final SchemaRegistryIdentity INELIGIBLE_LEADER_INFO = new SchemaRegistryIdentity(
      LEADER_HOST,
      LEADER_PORT,
      false
  );

  private String groupId = "test-group";
  private int sessionTimeoutMs = 10;
  private int rebalanceTimeoutMs = 60;
  private int heartbeatIntervalMs = 2;
  private long retryBackoffMs = 100;
  private MockTime time;
  private MockClient client;
  private Cluster cluster = TestUtils.singletonCluster("topic", 1);
  private Node node = cluster.nodes().get(0);
  private Metadata metadata;
  private Metrics metrics;
  private ConsumerNetworkClient consumerClient;
  private MockRebalanceListener rebalanceListener;
  private SchemaRegistryCoordinator coordinator;

  @Before
  public void setup() {
    this.time = new MockTime();
    this.client = new MockClient(time);
    this.metadata = new Metadata(0, Long.MAX_VALUE, true);
    this.metadata.update(cluster, Collections.<String>emptySet(), time.milliseconds());
    this.consumerClient = new ConsumerNetworkClient(client, metadata, time, 100, 1000);
    this.metrics = new Metrics(time);
    this.rebalanceListener = new MockRebalanceListener();

    client.setNode(node);

    this.coordinator = new SchemaRegistryCoordinator(
        consumerClient,
        groupId,
        rebalanceTimeoutMs,
        sessionTimeoutMs,
        heartbeatIntervalMs,
        metrics,
        "sr-" + groupId,
        time,
        retryBackoffMs,
        LEADER_INFO,
        rebalanceListener
    );
  }

  @After
  public void teardown() {
    this.metrics.close();
  }

  // We only test functionality unique to SchemaRegistryCoordinator. Most functionality is already
  // well tested via the tests that cover AbstractCoordinator & ConsumerCoordinator.

  @Test
  public void testMetadata() {
    List<ProtocolMetadata> serialized = coordinator.metadata();
    assertEquals(1, serialized.size());

    ProtocolMetadata defaultMetadata = serialized.get(0);
    assertEquals(SchemaRegistryCoordinator.SR_SUBPROTOCOL_V0, defaultMetadata.name());
    SchemaRegistryIdentity state
        = SchemaRegistryProtocol.deserializeMetadata(defaultMetadata.metadata());
    assertEquals(LEADER_INFO, state);
  }

  @Test
  public void testNormalJoinGroupLeader() {
    final String consumerId = LEADER_ID;

    client.prepareResponse(groupCoordinatorResponse(node, Errors.NONE));
    coordinator.ensureCoordinatorReady();

    // normal join group
    Map<String, SchemaRegistryIdentity> memberInfo = Collections.singletonMap(consumerId, LEADER_INFO);
    client.prepareResponse(joinGroupLeaderResponse(1, consumerId, memberInfo, Errors.NONE));
    SyncGroupResponse syncGroupResponse = syncGroupResponse(
        SchemaRegistryProtocol.Assignment.NO_ERROR,
        consumerId,
        LEADER_INFO,
        Errors.NONE
    );
    client.prepareResponse(new MockClient.RequestMatcher() {
      @Override
      public boolean matches(AbstractRequest body) {
        SyncGroupRequest sync = (SyncGroupRequest) body;
        return sync.memberId().equals(consumerId) &&
               sync.generationId() == 1 &&
               sync.groupAssignment().containsKey(consumerId);
      }
    }, syncGroupResponse);
    coordinator.ensureActiveGroup();

    assertFalse(coordinator.needRejoin());
    assertEquals(0, rebalanceListener.revokedCount);
    assertEquals(1, rebalanceListener.assignedCount);
    assertFalse(rebalanceListener.assignments.get(0).failed());
    assertEquals(consumerId, rebalanceListener.assignments.get(0).master());
    assertEquals(LEADER_INFO, rebalanceListener.assignments.get(0).masterIdentity());
  }

  @Test
  public void testJoinGroupLeaderNoneEligible() {
    final String consumerId = LEADER_ID;

    client.prepareResponse(groupCoordinatorResponse(node, Errors.NONE));
    coordinator.ensureCoordinatorReady();

    Map<String, SchemaRegistryIdentity> memberInfo = Collections.singletonMap(
        consumerId,
        INELIGIBLE_LEADER_INFO
    );
    client.prepareResponse(joinGroupLeaderResponse(1, consumerId, memberInfo, Errors.NONE));
    SyncGroupResponse syncGroupResponse = syncGroupResponse(
        SchemaRegistryProtocol.Assignment.NO_ERROR,
        null,
        null,
        Errors.NONE
    );
    client.prepareResponse(new MockClient.RequestMatcher() {
      @Override
      public boolean matches(AbstractRequest body) {
        SyncGroupRequest sync = (SyncGroupRequest) body;
        return sync.memberId().equals(consumerId) &&
               sync.generationId() == 1 &&
               sync.groupAssignment().containsKey(consumerId);
      }
    }, syncGroupResponse);

    coordinator.ensureActiveGroup();

    assertFalse(coordinator.needRejoin());
    assertEquals(0, rebalanceListener.revokedCount);
    assertEquals(1, rebalanceListener.assignedCount);
    // No leader isn't considered a failure
    assertFalse(rebalanceListener.assignments.get(0).failed());
    assertNull(rebalanceListener.assignments.get(0).master());
    assertNull(rebalanceListener.assignments.get(0).masterIdentity());
  }

  @Test
  public void testJoinGroupLeaderDuplicateUrls() {
    final String consumerId = LEADER_ID;

    client.prepareResponse(groupCoordinatorResponse(node, Errors.NONE));
    coordinator.ensureCoordinatorReady();

    Map<String, SchemaRegistryIdentity> memberInfo = new HashMap<>();
    // intentionally duplicate info to get duplicate URLs
    memberInfo.put(LEADER_ID, LEADER_INFO);
    memberInfo.put(MEMBER_ID, LEADER_INFO);
    client.prepareResponse(joinGroupLeaderResponse(1, consumerId, memberInfo, Errors.NONE));
    SyncGroupResponse syncGroupResponse = syncGroupResponse(
        SchemaRegistryProtocol.Assignment.DUPLICATE_URLS,
        null,
        null,
        Errors.NONE
    );
    client.prepareResponse(new MockClient.RequestMatcher() {
      @Override
      public boolean matches(AbstractRequest body) {
        SyncGroupRequest sync = (SyncGroupRequest) body;
        return sync.memberId().equals(consumerId) &&
               sync.generationId() == 1 &&
               sync.groupAssignment().containsKey(consumerId);
      }
    }, syncGroupResponse);

    coordinator.ensureActiveGroup();

    assertFalse(coordinator.needRejoin());
    assertEquals(0, rebalanceListener.revokedCount);
    assertEquals(1, rebalanceListener.assignedCount);
    assertTrue(rebalanceListener.assignments.get(0).failed());
    assertNull(rebalanceListener.assignments.get(0).master());
    assertNull(rebalanceListener.assignments.get(0).masterIdentity());
  }

  @Test
  public void testNormalJoinGroupFollower() {
    final String consumerId = MEMBER_ID;

    client.prepareResponse(groupCoordinatorResponse(node, Errors.NONE));
    coordinator.ensureCoordinatorReady();

    // normal join group
    client.prepareResponse(joinGroupFollowerResponse(1, consumerId, LEADER_ID, Errors.NONE));
    SyncGroupResponse syncGroupResponse = syncGroupResponse(
        SchemaRegistryProtocol.Assignment.NO_ERROR,
        LEADER_ID,
        LEADER_INFO,
        Errors.NONE
    );
    client.prepareResponse(new MockClient.RequestMatcher() {
      @Override
      public boolean matches(AbstractRequest body) {
        SyncGroupRequest sync = (SyncGroupRequest) body;
        return sync.memberId().equals(consumerId) &&
               sync.generationId() == 1 &&
               sync.groupAssignment().isEmpty();
      }
    }, syncGroupResponse);
    coordinator.ensureActiveGroup();

    assertFalse(coordinator.needRejoin());
    assertEquals(0, rebalanceListener.revokedCount);
    assertEquals(1, rebalanceListener.assignedCount);
    assertFalse(rebalanceListener.assignments.get(0).failed());
    assertEquals(LEADER_ID, rebalanceListener.assignments.get(0).master());
    assertEquals(LEADER_INFO, rebalanceListener.assignments.get(0).masterIdentity());
  }

  private FindCoordinatorResponse groupCoordinatorResponse(Node node, Errors error) {
    return new FindCoordinatorResponse(error, node);
  }

  private JoinGroupResponse joinGroupLeaderResponse(
      int generationId,
      String memberId,
      Map<String, SchemaRegistryIdentity> memberMasterEligibility,
      Errors error
  ) {
    Map<String, ByteBuffer> metadata = new HashMap<>();
    for (Map.Entry<String, SchemaRegistryIdentity> configStateEntry : memberMasterEligibility.entrySet()) {
      SchemaRegistryIdentity memberIdentity = configStateEntry.getValue();
      ByteBuffer buf = SchemaRegistryProtocol.serializeMetadata(memberIdentity);
      metadata.put(configStateEntry.getKey(), buf);
    }
    return new JoinGroupResponse(error, generationId, SchemaRegistryCoordinator
        .SR_SUBPROTOCOL_V0, memberId, memberId, metadata);
  }

  private JoinGroupResponse joinGroupFollowerResponse(
      int generationId,
      String memberId,
      String leaderId,
      Errors error
  ) {
    return new JoinGroupResponse(
        error,
        generationId,
        SchemaRegistryCoordinator.SR_SUBPROTOCOL_V0,
        memberId,
        leaderId,
        Collections.<String, ByteBuffer>emptyMap()
    );
  }

  private SyncGroupResponse syncGroupResponse(
      short assignmentError,
      String master,
      SchemaRegistryIdentity masterIdentity,
      Errors error
  ) {
    SchemaRegistryProtocol.Assignment assignment = new SchemaRegistryProtocol.Assignment(
        assignmentError, master, masterIdentity
    );
    ByteBuffer buf = SchemaRegistryProtocol.serializeAssignment(assignment);
    return new SyncGroupResponse(error, buf);
  }

  private static class MockRebalanceListener implements SchemaRegistryRebalanceListener {
    public List<SchemaRegistryProtocol.Assignment> assignments = new ArrayList<>();

    public int revokedCount = 0;
    public int assignedCount = 0;

    @Override
    public void onAssigned(SchemaRegistryProtocol.Assignment assignment, int generation) {
      this.assignments.add(assignment);
      assignedCount++;
    }

    @Override
    public void onRevoked() {
      revokedCount++;
    }
  }
}