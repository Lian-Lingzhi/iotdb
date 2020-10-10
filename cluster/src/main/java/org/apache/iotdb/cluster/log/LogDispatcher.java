/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.cluster.log;


import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.rpc.thrift.AppendEntriesRequest;
import org.apache.iotdb.cluster.rpc.thrift.AppendEntryRequest;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.rpc.thrift.RaftService.AsyncClient;
import org.apache.iotdb.cluster.rpc.thrift.RaftService.Client;
import org.apache.iotdb.cluster.server.Peer;
import org.apache.iotdb.cluster.server.Timer;
import org.apache.iotdb.cluster.server.handlers.caller.AppendNodeEntryHandler;
import org.apache.iotdb.cluster.server.member.RaftMember;
import org.apache.iotdb.cluster.utils.ClientUtils;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A LogDispatcher servers a raft leader by queuing logs that the leader wants to send to the
 * follower and send the logs in an ordered manner so that the followers will not wait for previous
 * logs for too long. For example: if the leader send 3 logs, log1, log2, log3, concurrently to
 * follower A, the actual reach order may be log3, log2, and log1. According to the protocol, log3
 * and log2 must halt until log1 reaches, as a result, the total delay may increase significantly.
 */
public class LogDispatcher {

  private static final Logger logger = LoggerFactory.getLogger(LogDispatcher.class);
  private RaftMember member;
  private List<BlockingQueue<SendLogRequest>> nodeLogQueues =
      new ArrayList<>();
  private ExecutorService executorService;

  public LogDispatcher(RaftMember member) {
    this.member = member;
    executorService = Executors.newCachedThreadPool();
    for (Node node : member.getAllNodes()) {
      if (!node.equals(member.getThisNode())) {
        nodeLogQueues.add(createQueueAndBindingThread(node));
      }
    }
  }

  public void offer(SendLogRequest log) {
    for (int i = 0; i < nodeLogQueues.size(); i++) {
      BlockingQueue<SendLogRequest> nodeLogQueue = nodeLogQueues.get(i);
      try {
        if (!nodeLogQueue.add(log)) {
          logger.debug("Log queue[{}] of {} is full, ignore the log to this node", i,
              member.getName());
        } else {
          log.setEnqueueTime(System.nanoTime());
        }
      } catch (IllegalStateException e) {
        logger.debug("Log queue[{}] of {} is full, ignore the log to this node", i,
            member.getName());
      }
    }
  }

  private BlockingQueue<SendLogRequest> createQueueAndBindingThread(Node node) {
    BlockingQueue<SendLogRequest> logBlockingQueue =
        new ArrayBlockingQueue<>(
            ClusterDescriptor.getInstance().getConfig().getMinNumOfLogsInMem());
    int bindingThreadNum = 1;
    for (int i = 0; i < bindingThreadNum; i++) {
      executorService.submit(new DispatcherThread(node, logBlockingQueue));
    }
    return logBlockingQueue;
  }

  public static class SendLogRequest {

    private Log log;
    private AtomicInteger voteCounter;
    private AtomicBoolean leaderShipStale;
    private AtomicLong newLeaderTerm;
    private AppendEntryRequest appendEntryRequest;
    private long enqueueTime;

    public SendLogRequest(Log log, AtomicInteger voteCounter,
        AtomicBoolean leaderShipStale, AtomicLong newLeaderTerm,
        AppendEntryRequest appendEntryRequest) {
      this.setLog(log);
      this.setVoteCounter(voteCounter);
      this.setLeaderShipStale(leaderShipStale);
      this.setNewLeaderTerm(newLeaderTerm);
      this.setAppendEntryRequest(appendEntryRequest);
    }

    public AtomicInteger getVoteCounter() {
      return voteCounter;
    }

    public void setVoteCounter(AtomicInteger voteCounter) {
      this.voteCounter = voteCounter;
    }

    public Log getLog() {
      return log;
    }

    public void setLog(Log log) {
      this.log = log;
    }

    public long getEnqueueTime() {
      return enqueueTime;
    }

    public void setEnqueueTime(long enqueueTime) {
      this.enqueueTime = enqueueTime;
    }

    public AtomicBoolean getLeaderShipStale() {
      return leaderShipStale;
    }

    public void setLeaderShipStale(AtomicBoolean leaderShipStale) {
      this.leaderShipStale = leaderShipStale;
    }

    public AtomicLong getNewLeaderTerm() {
      return newLeaderTerm;
    }

    void setNewLeaderTerm(AtomicLong newLeaderTerm) {
      this.newLeaderTerm = newLeaderTerm;
    }

    public AppendEntryRequest getAppendEntryRequest() {
      return appendEntryRequest;
    }

    public void setAppendEntryRequest(
        AppendEntryRequest appendEntryRequest) {
      this.appendEntryRequest = appendEntryRequest;
    }
  }

  class DispatcherThread implements Runnable {

    private Node receiver;
    private BlockingQueue<SendLogRequest> logBlockingDeque;
    private List<SendLogRequest> currBatch = new ArrayList<>();
    private Peer peer;

    DispatcherThread(Node receiver,
        BlockingQueue<SendLogRequest> logBlockingDeque) {
      this.receiver = receiver;
      this.logBlockingDeque = logBlockingDeque;
      this.peer = member.getPeerMap().computeIfAbsent(receiver,
          r -> new Peer(member.getLogManager().getLastLogIndex()));
    }

    @Override
    public void run() {
      Thread.currentThread().setName("LogDispatcher-" + member.getName() + "-" + receiver);
      try {
        while (!Thread.interrupted()) {
          SendLogRequest poll = logBlockingDeque.take();
          // do serialization here to avoid taking LogManager for too long
          poll.getAppendEntryRequest().setEntry(poll.getLog().serialize());
          currBatch.add(poll);
          logBlockingDeque.drainTo(currBatch);
          if (logger.isDebugEnabled()) {
            logger.debug("Sending {} logs to {}", currBatch.size(), receiver);
          }
          if (currBatch.size() > 1) {
            sendLogs(currBatch);
          } else {
            sendLog(currBatch.get(0));
          }

          currBatch.clear();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        logger.error("Unexpected error in log dispatcher", e);
      }
      logger.info("Dispatcher exits");
    }

    private void appendEntriesAsync(List<ByteBuffer> logList, AppendEntriesRequest request,
        List<SendLogRequest> currBatch)
        throws TException {
      AsyncMethodCallback<Long> handler = new AppendEntriesHandler(currBatch);
      AsyncClient client = member.getSendLogAsyncClient(receiver);
      if (logger.isDebugEnabled()) {
        logger.debug("{}: Catching up {} with {} logs", member.getName(), receiver, logList.size());
      }
      client.appendEntries(request, handler);
    }

    private void appendEntriesSync(List<ByteBuffer> logList, AppendEntriesRequest request,
        List<SendLogRequest> currBatch) {

      long start;
      if (Timer.ENABLE_INSTRUMENTING) {
        start = System.nanoTime();
      }
      if (!member.waitForPrevLog(peer, currBatch.get(0).getLog())) {
        logger.warn("{}: node {} timed out when appending {}", member.getName(), receiver,
            currBatch.get(0).getLog());
        return;
      }
      Timer.Statistic.RAFT_SENDER_WAIT_FOR_PREV_LOG.addNanoFromStart(start);

      Client client = member.getSyncClient(receiver);
      AsyncMethodCallback<Long> handler = new AppendEntriesHandler(currBatch);
      try {
        if (Timer.ENABLE_INSTRUMENTING) {
          start = System.nanoTime();
        }
        long result = client.appendEntries(request);
        Timer.Statistic.RAFT_SENDER_SEND_LOG.addNanoFromStart(start);
        if (result != -1 && logger.isInfoEnabled()) {
          logger.info("{}: Append {} logs to {}, resp: {}", member.getName(), logList.size(),
              receiver, result);
        }
        handler.onComplete(result);
      } catch (TException e) {
        handler.onError(e);
        logger.warn("Failed logs: {}, first index: {}", logList, request.prevLogIndex + 1);
      } finally {
        ClientUtils.putBackSyncClient(client);
      }
    }

    private AppendEntriesRequest prepareRequest(List<ByteBuffer> logList,
        List<SendLogRequest> currBatch) {
      AppendEntriesRequest request = new AppendEntriesRequest();

      if (member.getHeader() != null) {
        request.setHeader(member.getHeader());
      }
      request.setLeader(member.getThisNode());
      request.setLeaderCommit(member.getLogManager().getCommitLogIndex());

      synchronized (member.getTerm()) {
        request.setTerm(member.getTerm().get());
      }

      request.setEntries(logList);
      // set index for raft
      request.setPrevLogIndex(currBatch.get(0).getLog().getCurrLogIndex() - 1);
      try {
        request.setPrevLogTerm(currBatch.get(0).getAppendEntryRequest().prevLogTerm);
      } catch (Exception e) {
        logger.error("getTerm failed for newly append entries", e);
      }
      return request;
    }

    private void sendLogs(List<SendLogRequest> currBatch) throws TException {
      List<ByteBuffer> logList = new ArrayList<>();
      for (SendLogRequest request : currBatch) {
        Timer.Statistic.LOG_DISPATCHER_LOG_IN_QUEUE.addNanoFromStart(request.getLog().getCreateTime());
        logList.add(request.getAppendEntryRequest().entry);
      }

      AppendEntriesRequest appendEntriesReques = prepareRequest(logList, currBatch);
      if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
        appendEntriesAsync(logList, appendEntriesReques, new ArrayList<>(currBatch));
      } else {
        appendEntriesSync(logList, appendEntriesReques, currBatch);
      }
      for (SendLogRequest batch : currBatch) {
        Timer.Statistic.LOG_DISPATCHER_FROM_CREATE_TO_END.addNanoFromStart(batch.getLog().getCreateTime());
      }
    }

    private void sendLog(SendLogRequest logRequest) {
      Timer.Statistic.LOG_DISPATCHER_LOG_IN_QUEUE.addNanoFromStart(logRequest.getLog().getCreateTime());
      member.sendLogToFollower(logRequest.getLog(), logRequest.getVoteCounter(), receiver,
          logRequest.getLeaderShipStale(), logRequest.getNewLeaderTerm(),
          logRequest.getAppendEntryRequest());
      Timer.Statistic.LOG_DISPATCHER_FROM_CREATE_TO_END
          .addNanoFromStart(logRequest.getLog().getCreateTime());
    }

    class AppendEntriesHandler implements AsyncMethodCallback<Long> {

      private final List<AsyncMethodCallback<Long>> singleEntryHandlers;

      private AppendEntriesHandler(List<SendLogRequest> batch) {
        singleEntryHandlers = new ArrayList<>(batch.size());
        for (SendLogRequest sendLogRequest : batch) {
          AppendNodeEntryHandler handler = getAppendNodeEntryHandler(sendLogRequest.getLog(),
              sendLogRequest.getVoteCounter()
              , receiver,
              sendLogRequest.getLeaderShipStale(), sendLogRequest.getNewLeaderTerm(), peer);
          singleEntryHandlers.add(handler);
        }
      }

      @Override
      public void onComplete(Long aLong) {
        for (AsyncMethodCallback<Long> singleEntryHandler : singleEntryHandlers) {
          singleEntryHandler.onComplete(aLong);
        }
      }

      @Override
      public void onError(Exception e) {
        for (AsyncMethodCallback<Long> singleEntryHandler : singleEntryHandlers) {
          singleEntryHandler.onError(e);
        }
      }
    }
  }


  public AppendNodeEntryHandler getAppendNodeEntryHandler(Log log, AtomicInteger voteCounter,
      Node node, AtomicBoolean leaderShipStale, AtomicLong newLeaderTerm, Peer peer) {
    AppendNodeEntryHandler handler = new AppendNodeEntryHandler();
    handler.setReceiver(node);
    handler.setVoteCounter(voteCounter);
    handler.setLeaderShipStale(leaderShipStale);
    handler.setLog(log);
    handler.setMember(member);
    handler.setPeer(peer);
    handler.setReceiverTerm(newLeaderTerm);
    return handler;
  }
}