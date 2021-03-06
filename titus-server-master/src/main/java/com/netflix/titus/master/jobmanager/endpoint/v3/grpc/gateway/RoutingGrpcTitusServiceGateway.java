/*
 * Copyright 2018 Netflix, Inc.
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

package com.netflix.titus.master.jobmanager.endpoint.v3.grpc.gateway;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.netflix.titus.api.agent.service.AgentManagementService;
import com.netflix.titus.api.jobmanager.model.job.ContainerResources;
import com.netflix.titus.api.jobmanager.model.job.JobFunctions;
import com.netflix.titus.api.jobmanager.service.JobManagerException;
import com.netflix.titus.api.model.Page;
import com.netflix.titus.api.model.Pagination;
import com.netflix.titus.api.model.PaginationUtil;
import com.netflix.titus.api.model.ResourceDimension;
import com.netflix.titus.api.model.Tier;
import com.netflix.titus.api.service.TitusServiceException;
import com.netflix.titus.api.service.TitusServiceException.ErrorCode;
import com.netflix.titus.common.util.CollectionsExt;
import com.netflix.titus.common.util.RegExpExt;
import com.netflix.titus.common.util.StringExt;
import com.netflix.titus.common.util.tuple.Pair;
import com.netflix.titus.grpc.protogen.Job;
import com.netflix.titus.grpc.protogen.JobChangeNotification;
import com.netflix.titus.grpc.protogen.JobChangeNotification.NotificationCase;
import com.netflix.titus.grpc.protogen.JobDescriptor;
import com.netflix.titus.grpc.protogen.JobGroupInfo;
import com.netflix.titus.grpc.protogen.Task;
import com.netflix.titus.grpc.protogen.TaskStatus;
import com.netflix.titus.master.config.CellInfoResolver;
import com.netflix.titus.master.config.MasterConfiguration;
import com.netflix.titus.master.endpoint.common.CellDecorator;
import com.netflix.titus.master.endpoint.common.TaskSummary;
import com.netflix.titus.master.endpoint.grpc.GrpcEndpointConfiguration;
import com.netflix.titus.master.jobmanager.service.JobManagerUtil;
import com.netflix.titus.master.model.ResourceDimensions;
import com.netflix.titus.master.service.management.ApplicationSlaManagementService;
import com.netflix.titus.runtime.endpoint.JobQueryCriteria;
import com.netflix.titus.runtime.endpoint.v3.grpc.V3GrpcModelConverters;
import com.netflix.titus.runtime.jobmanager.JobManagerCursors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

@Singleton
public class RoutingGrpcTitusServiceGateway implements GrpcTitusServiceGateway {

    public static final String NAME_V2_ENGINE_GATEWAY = "grpcToV2Engine";
    public static final String NAME_V3_ENGINE_GATEWAY = "grpcToV3Engine";
    private static final Logger logger = LoggerFactory.getLogger(RoutingGrpcTitusServiceGateway.class);
    private final GrpcTitusServiceGateway v2EngineGateway;
    private final GrpcTitusServiceGateway v3EngineGateway;
    private final AgentManagementService agentManagementService;
    private final ApplicationSlaManagementService capacityGroupService;
    private final GrpcEndpointConfiguration configuration;
    private final MasterConfiguration masterConfiguration;
    private final CellDecorator cellDecorator;

    private final Function<String, Matcher> whiteListJobClusterInfoMatcher;
    private final Function<String, Matcher> blackListJobClusterInfoMatcher;
    private final Function<String, Matcher> blackListImageMatcher;

    @Inject
    public RoutingGrpcTitusServiceGateway(
            @Named("grpcToV2Engine") GrpcTitusServiceGateway v2EngineGateway,
            @Named("grpcToV3Engine") GrpcTitusServiceGateway v3EngineGateway,
            AgentManagementService agentManagementService,
            ApplicationSlaManagementService capacityGroupService,
            GrpcEndpointConfiguration configuration,
            MasterConfiguration masterConfiguration,
            CellInfoResolver cellInfoResolver) {
        this.v2EngineGateway = v2EngineGateway;
        this.v3EngineGateway = v3EngineGateway;
        this.masterConfiguration = masterConfiguration;
        this.cellDecorator = new CellDecorator(cellInfoResolver::getCellName);
        this.agentManagementService = agentManagementService;
        this.capacityGroupService = capacityGroupService;
        this.configuration = configuration;

        this.whiteListJobClusterInfoMatcher = RegExpExt.dynamicMatcher(
                configuration::getV3EnabledApps,
                "titus.master.grpcServer.v3EnabledApps",
                0,
                logger
        );
        this.blackListJobClusterInfoMatcher = RegExpExt.dynamicMatcher(
                configuration::getNotV3EnabledApps,
                "titus.master.grpcServer.notV3EnabledApps",
                0,
                logger
        );
        this.blackListImageMatcher = RegExpExt.dynamicMatcher(
                configuration::getNotV3EnabledImages,
                "titus.master.grpcServer.notV3EnabledImages",
                0,
                logger
        );
    }

    @Override
    public Observable<String> createJob(JobDescriptor jobDescriptor) {
        if (configuration.isJobSizeValidationEnabled()) {
            // TODO Move this code to V3GrpcTitusServiceGateway once we get rid of V2 engine.
            com.netflix.titus.api.jobmanager.model.job.JobDescriptor coreJobDescriptor = V3GrpcModelConverters.toCoreJobDescriptor(jobDescriptor);

            Tier tier = findTier(coreJobDescriptor);
            ResourceDimension requestedResources = toResourceDimension(coreJobDescriptor.getContainer().getContainerResources());
            List<ResourceDimension> tierResourceLimits = getTierResourceLimits(tier);
            if (isTooLarge(requestedResources, tierResourceLimits)) {
                return Observable.error(JobManagerException.invalidContainerResources(tier, requestedResources, tierResourceLimits));
            }
        }

        final JobDescriptor withCellInfo = cellDecorator.ensureCellInfo(jobDescriptor);
        boolean v3Enabled = isV3Enabled(withCellInfo);

        if (!v3Enabled && !masterConfiguration.isV2Enabled()) {
            return Observable.error(JobManagerException.v2EngineOff());
        }

        return v3Enabled ? v3EngineGateway.createJob(withCellInfo) : v2EngineGateway.createJob(withCellInfo);
    }

    private Tier findTier(com.netflix.titus.api.jobmanager.model.job.JobDescriptor jobDescriptor) {
        return JobManagerUtil.getTierAssignment(jobDescriptor, capacityGroupService).getLeft();
    }

    private boolean isTooLarge(ResourceDimension requestedResources, List<ResourceDimension> tierResourceLimits) {
        return tierResourceLimits.stream().noneMatch(limit -> ResourceDimensions.isBigger(limit, requestedResources));
    }

    private List<ResourceDimension> getTierResourceLimits(Tier tier) {
        return agentManagementService.getInstanceGroups().stream()
                .filter(instanceGroup -> instanceGroup.getTier().equals(tier))
                .map(instanceGroup ->
                        agentManagementService.findResourceLimits(instanceGroup.getInstanceType()).orElse(instanceGroup.getResourceDimension())
                )
                .collect(Collectors.toList());
    }

    private ResourceDimension toResourceDimension(ContainerResources containerResources) {
        return ResourceDimension.newBuilder()
                .withCpus(containerResources.getCpu())
                .withGpu(containerResources.getGpu())
                .withMemoryMB(containerResources.getMemoryMB())
                .withDiskMB(containerResources.getDiskMB())
                .withNetworkMbs(containerResources.getNetworkMbps())
                .build();
    }

    @Override
    public Observable<Void> killJob(String user, String jobId) {
        return JobFunctions.isV2JobId(jobId) ? v2EngineGateway.killJob(user, jobId) : v3EngineGateway.killJob(user, jobId);
    }

    @Override
    public Observable<Job> findJobById(String jobId, boolean includeArchivedTasks, Set<TaskStatus.TaskState> taskStates) {
        return JobFunctions.isV2JobId(jobId)
                ? v2EngineGateway.findJobById(jobId, includeArchivedTasks, taskStates)
                : v3EngineGateway.findJobById(jobId, includeArchivedTasks, taskStates);
    }

    @Override
    public Pair<List<Job>, Pagination> findJobsByCriteria(JobQueryCriteria<TaskStatus.TaskState, JobDescriptor.JobSpecCase> queryCriteria, Optional<Page> pageOpt) {
        Page page = pageOpt.orElseThrow(() ->
                TitusServiceException.newBuilder(ErrorCode.INVALID_ARGUMENT, "Page not provided").build()
        );

        // Get all matching entities, as we need totals anyway.
        Pair<List<Job>, Pagination> v2Result = v2EngineGateway.findJobsByCriteria(queryCriteria, Optional.of(Page.unlimited()));
        Pair<List<Job>, Pagination> v3Result = v3EngineGateway.findJobsByCriteria(queryCriteria, Optional.of(Page.unlimited()));

        return PaginationUtil.takePageWithCursor(
                page,
                CollectionsExt.merge(v2Result.getLeft(), v3Result.getLeft()),
                JobManagerCursors.jobCursorOrderComparator(),
                JobManagerCursors::jobIndexOf,
                JobManagerCursors::newCursorFrom
        );
    }

    @Override
    public Pair<List<Task>, Pagination> findTasksByCriteria(JobQueryCriteria<TaskStatus.TaskState, JobDescriptor.JobSpecCase> queryCriteria, Optional<Page> pageOpt) {
        Page page = pageOpt.orElseThrow(() ->
                TitusServiceException.newBuilder(ErrorCode.INVALID_ARGUMENT, "Page not provided").build()
        );

        // Get all matching entities, as we need totals anyway.
        Pair<List<Task>, Pagination> v2Result = v2EngineGateway.findTasksByCriteria(queryCriteria, Optional.of(Page.unlimited()));
        Pair<List<Task>, Pagination> v3Result = v3EngineGateway.findTasksByCriteria(queryCriteria, Optional.of(Page.unlimited()));

        return PaginationUtil.takePageWithCursor(
                page,
                CollectionsExt.merge(v2Result.getLeft(), v3Result.getLeft()),
                JobManagerCursors.taskCursorOrderComparator(),
                JobManagerCursors::taskIndexOf,
                JobManagerCursors::newCursorFrom
        );
    }

    @Override
    public Observable<Task> findTaskById(String taskId) {
        if (JobFunctions.isV2Task(taskId)) {
            return v2EngineGateway.findTaskById(taskId);
        }
        return v3EngineGateway.findTaskById(taskId).onErrorResumeNext(v2EngineGateway.findTaskById(taskId));
    }

    @Override
    public Observable<Void> resizeJob(String user, String jobId, int desired, int min, int max) {
        if (JobFunctions.isV2JobId(jobId)) {
            return v2EngineGateway.resizeJob(user, jobId, desired, min, max);
        }
        return v3EngineGateway.resizeJob(user, jobId, desired, min, max);
    }

    @Override
    public Observable<Void> updateJobProcesses(String user, String jobId, boolean disableDecreaseDesired, boolean disableIncreaseDesired) {
        if (JobFunctions.isV2JobId(jobId)) {
            return v2EngineGateway.updateJobProcesses(user, jobId, disableDecreaseDesired, disableIncreaseDesired);
        }
        return v3EngineGateway.updateJobProcesses(user, jobId, disableDecreaseDesired, disableIncreaseDesired);
    }

    @Override
    public Observable<Void> changeJobInServiceStatus(String user, String serviceJobId, boolean inService) {
        if (JobFunctions.isV2JobId(serviceJobId)) {
            return v2EngineGateway.changeJobInServiceStatus(user, serviceJobId, inService);
        }
        return v3EngineGateway.changeJobInServiceStatus(user, serviceJobId, inService);
    }

    @Override
    public Observable<Void> killTask(String user, String taskId, boolean shrink) {
        if (JobFunctions.isV2Task(taskId)) {
            return v2EngineGateway.killTask(user, taskId, shrink);
        }
        return v3EngineGateway.killTask(user, taskId, shrink).onErrorResumeNext(
                ex -> v2EngineGateway.killTask(user, taskId, shrink)
        );
    }

    @Override
    public Observable<List<TaskSummary>> getTaskSummary() {
        throw new IllegalStateException("not implemented yet");
    }

    @Override
    public Observable<JobChangeNotification> observeJobs() {
        return v2EngineGateway.observeJobs().flatMap(event -> {
            if (event.getNotificationCase() == NotificationCase.SNAPSHOTEND) {
                return v3EngineGateway.observeJobs();
            }
            return Observable.just(event);
        });
    }

    @Override
    public Observable<JobChangeNotification> observeJob(String jobId) {
        return JobFunctions.isV2JobId(jobId) ? v2EngineGateway.observeJob(jobId) : v3EngineGateway.observeJob(jobId);
    }

    private boolean isV3Enabled(JobDescriptor jobDescriptor) {
        String jobGroupId = buildJobGroupId(jobDescriptor);
        boolean inAppWhiteList = whiteListJobClusterInfoMatcher.apply(jobGroupId).matches();
        boolean inAppBlackList = blackListJobClusterInfoMatcher.apply(jobGroupId).matches();

        if (!inAppWhiteList || inAppBlackList) {
            return false;
        }

        String imageName = jobDescriptor.getContainer().getImage().getName();
        if (StringExt.isEmpty(imageName)) {
            // Lack of name, implies that the digest is set, which is supported only in V3 engine
            return true;
        }

        return !blackListImageMatcher.apply(imageName).matches();
    }

    private String buildJobGroupId(JobDescriptor jobDescriptor) {
        JobGroupInfo jobGroupInfo = jobDescriptor.getJobGroupInfo();
        return jobDescriptor.getApplicationName() + '-' + jobGroupInfo.getStack() + '-' + jobGroupInfo.getDetail() + '-' + jobGroupInfo.getSequence();
    }
}
