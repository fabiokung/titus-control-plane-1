/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.titus.api.clustermembership.model.event;

import java.util.Objects;

import com.netflix.titus.api.clustermembership.model.ClusterMembershipRevision;

public class ClusterMemberRemovedEvent {

    private final ClusterMembershipRevision revision;

    ClusterMemberRemovedEvent(ClusterMembershipRevision revision) {
        this.revision = revision;
    }

    public ClusterMembershipRevision getRevision() {
        return revision;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ClusterMemberRemovedEvent that = (ClusterMemberRemovedEvent) o;
        return Objects.equals(revision, that.revision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(revision);
    }

    @Override
    public String toString() {
        return "ClusterMemberRemovedEvent{" +
                "revision=" + revision +
                '}';
    }
}