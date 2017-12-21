/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.netflix.titus.testkit.embedded.cloud.agent;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.google.protobuf.ByteString;
import io.netflix.titus.api.json.ObjectMappers;
import io.netflix.titus.api.model.EfsMount;
import io.netflix.titus.common.aws.AwsInstanceType;
import io.netflix.titus.common.util.CollectionsExt;
import io.netflix.titus.master.mesos.TitusExecutorDetails;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.TaskState;
import rx.Observable;
import rx.Observer;

import static io.netflix.titus.common.util.CollectionsExt.asSet;

/**
 * A holder to the task submitted on an simulated Titus agent. It provides methods to manipulate task's
 * state, which is reported back to TitusMaster via Mesos. The task always starts in {@link TaskState#TASK_STAGING}
 * state.
 */
public class TaskExecutorHolder {

    private static final Map<TaskState, Set<TaskState>> VALID_STATE_TRANSITIONS = CollectionsExt.<TaskState, Set<TaskState>>newHashMap()
            .entry(TaskState.TASK_STAGING, asSet(TaskState.TASK_STARTING, TaskState.TASK_FAILED, TaskState.TASK_KILLED))
            .entry(TaskState.TASK_STARTING, asSet(TaskState.TASK_RUNNING, TaskState.TASK_FAILED, TaskState.TASK_KILLED))
            .entry(TaskState.TASK_RUNNING, asSet(TaskState.TASK_FINISHED, TaskState.TASK_ERROR, TaskState.TASK_FAILED,
                    TaskState.TASK_KILLED, TaskState.TASK_KILLED
            ))
            .entry(TaskState.TASK_FINISHED, asSet())
            .entry(TaskState.TASK_ERROR, asSet())
            .entry(TaskState.TASK_FAILED, asSet())
            .entry(TaskState.TASK_KILLED, asSet())
            .entry(TaskState.TASK_LOST, asSet())
            .toMap();

    private final String jobId;
    private final String taskId;
    private final SimulatedTitusAgent agent;
    private final AwsInstanceType instanceType;
    private final double taskCPUs;
    private final double taskGPUs;
    private final double taskMem;
    private final double taskDisk;
    private final Set<Long> allocatedPorts;
    private final String containerIp;
    private final double taskNetworkMbs;
    private final List<EfsMount> efsMounts;
    private final Observer<Protos.TaskStatus> stateUpdatesObserver;

    private volatile Protos.TaskStatus currentTaskStatus;
    private volatile Function<TaskState, Long> delayFunction;

    TaskExecutorHolder(String jobId,
                       String taskId,
                       SimulatedTitusAgent agent,
                       AwsInstanceType instanceType,
                       double taskCPUs,
                       double taskGPUs,
                       double taskMem,
                       double taskDisk,
                       Set<Long> allocatedPorts,
                       String containerIp,
                       double taskNetworkMbs,
                       List<EfsMount> efsMounts,
                       Observer<Protos.TaskStatus> stateUpdatesObserver) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.agent = agent;
        this.instanceType = instanceType;
        this.taskCPUs = taskCPUs;
        this.taskGPUs = taskGPUs;
        this.taskMem = taskMem;
        this.taskDisk = taskDisk;
        this.allocatedPorts = allocatedPorts;
        this.containerIp = containerIp;
        this.taskNetworkMbs = taskNetworkMbs;
        this.efsMounts = efsMounts;
        this.stateUpdatesObserver = stateUpdatesObserver;
        this.currentTaskStatus = newTaskStatusBuilder().setState(TaskState.TASK_STAGING).setMessage("Task staging").build();
        this.delayFunction = taskState -> 0L; // No transition delay by default

        stateUpdatesObserver.onNext(currentTaskStatus);
    }

    public String getJobId() {
        return jobId;
    }

    public String getTaskId() {
        return taskId;
    }

    public Protos.TaskStatus getTaskStatus() {
        return currentTaskStatus;
    }

    public SimulatedTitusAgent getAgent() {
        return agent;
    }

    public AwsInstanceType getInstanceType() {
        return instanceType;
    }

    public String getContainerIp() {
        return containerIp;
    }

    public double getTaskCPUs() {
        return taskCPUs;
    }

    public double getTaskGPUs() {
        return taskGPUs;
    }

    public double getTaskMem() {
        return taskMem;
    }

    public double getTaskDisk() {
        return taskDisk;
    }

    public Set<Long> getAllocatedPorts() {
        return allocatedPorts;
    }

    public double getTaskNetworkMbs() {
        return taskNetworkMbs;
    }

    public List<EfsMount> getEfsMounts() {
        return efsMounts;
    }

    public TaskState getState() {
        return currentTaskStatus.getState();
    }

    public void delayStateTransition(Function<TaskState, Long> delayFunction) {
        this.delayFunction = delayFunction;
    }

    /**
     * Call this method to perform valid state transition (STARTING -> RUNNING). For testing error cases
     * with invalid state transitions (RUNNING -> STARTING) use {@link #transitionToUnchecked(TaskState, Protos.TaskStatus.Reason, String)}.
     */
    public TaskState transitionTo(TaskState nextState) {
        return transitionTo(nextState, Protos.TaskStatus.Reason.REASON_TASK_UNKNOWN, "Reason for the state transition not given");
    }

    public TaskState transitionTo(TaskState nextState, Protos.TaskStatus.Reason reason, String reasonMessage) {
        TaskState currentTaskState = currentTaskStatus.getState();
        if (!VALID_STATE_TRANSITIONS.get(currentTaskState).contains(nextState) && !isTerminal(nextState)) {
            throw new IllegalArgumentException("State transition " + currentTaskState + " -> " + nextState + " not allowed");
        }
        return transitionToUnchecked(nextState, reason, reasonMessage);
    }

    public TaskState transitionToUnchecked(TaskState nextState, Protos.TaskStatus.Reason reason, String reasonMessage) {
        TaskState oldState = currentTaskStatus.getState();

        Protos.TaskStatus.Builder statusBuilder = newTaskStatusBuilder()
                .setState(nextState)
                .setReason(reason)
                .setMessage(reasonMessage);

        if (nextState == TaskState.TASK_STARTING && containerIp != null) {
            TitusExecutorDetails details = new TitusExecutorDetails(
                    Collections.singletonMap("nfvpc", containerIp),
                    new TitusExecutorDetails.NetworkConfiguration(
                            true,
                            containerIp,
                            null,
                            null,
                            null
                    )
            );
            try {
                statusBuilder.setData(ByteString.copyFrom(ObjectMappers.compactMapper().writeValueAsString(details), Charset.defaultCharset()));
            } catch (Exception e) {
                // IGNORE
            }
        }

        currentTaskStatus = statusBuilder.build();

        long delay = delayFunction.apply(nextState);
        if (delay <= 0) {
            emitTaskStateUpdate();
        } else {
            Observable.timer(delay, TimeUnit.MILLISECONDS).subscribe(tick -> emitTaskStateUpdate());
        }

        return oldState;
    }

    private void emitTaskStateUpdate() {
        stateUpdatesObserver.onNext(currentTaskStatus);
        if (isTerminal(currentTaskStatus.getState())) {
            stateUpdatesObserver.onCompleted();
            agent.removeCompletedTask(this);
        }
    }

    @Override
    public String toString() {
        return "TaskExecutorHolder{" +
                "jobId='" + jobId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", agent=" + agent +
                ", instanceType=" + instanceType +
                ", taskCPUs=" + taskCPUs +
                ", taskGPUs=" + taskGPUs +
                ", taskMem=" + taskMem +
                ", taskDisk=" + taskDisk +
                ", allocatedPorts=" + allocatedPorts +
                ", taskNetworkMbs=" + taskNetworkMbs +
                ", stateUpdatesObserver=" + stateUpdatesObserver +
                ", currentTaskState=" + currentTaskStatus +
                '}';
    }

    private boolean isTerminal(TaskState taskState) {
        return taskState == TaskState.TASK_FINISHED || taskState == TaskState.TASK_ERROR
                || taskState == TaskState.TASK_FAILED || taskState == TaskState.TASK_KILLED
                || taskState == TaskState.TASK_KILLED;
    }

    private Protos.TaskStatus.Builder newTaskStatusBuilder() {
        return Protos.TaskStatus.newBuilder()
                .setTaskId(Protos.TaskID.newBuilder().setValue(taskId).build())
                .setSlaveId(agent.getSlaveId())
                .setHealthy(true);
    }
}
