package org.jenkinsci.plugins.pipeline.maven.listeners;

import hudson.Extension;
import hudson.console.ModelHyperlinkNote;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.plugins.pipeline.maven.GlobalPipelineMavenConfig;
import org.jenkinsci.plugins.pipeline.maven.MavenArtifact;
import org.jenkinsci.plugins.pipeline.maven.cause.MavenDependencyCauseHelper;
import org.jenkinsci.plugins.pipeline.maven.cause.MavenDependencyUpstreamCause;
import org.jenkinsci.plugins.pipeline.maven.publishers.PipelineGraphPublisher;
import org.jenkinsci.plugins.pipeline.maven.publishers.PipelineGraphPublisher.PipelineGraphPublisherAction;
import org.jenkinsci.plugins.pipeline.maven.trigger.WorkflowJobDependencyTrigger;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.inject.Inject;

/**
 * Trigger downstream pipelines.
 *
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
@Extension
public class DownstreamPipelineTriggerRunListener extends RunListener<WorkflowRun> {

    private final static Logger LOGGER = Logger.getLogger(DownstreamPipelineTriggerRunListener.class.getName());

    @Inject
    public GlobalPipelineMavenConfig globalPipelineMavenConfig;

    //
    // HACK: skip all transitive
    //       i.e.: reducing downstream pipelines to iterate over
    //       as usual this is not pretty, just works
    //
    private List<String> getDownstreamPipelines(WorkflowRun build) {
        if (build == null) {
            return Collections.EMPTY_LIST;
        }
        return
            globalPipelineMavenConfig.getDao().listDownstreamJobs(build.getParent().getFullName(), build.getNumber());
    }
    private Set<String> reduceDownstreamTriggers(
            WorkflowJob upstreamPipeline, String skipDownstreamTriggersPattern,
            List<String> downstreamPipelines, Set<String> downTriggers, Set<String> downRemovedTriggers) {

        for (String downstreamPipelineFullName : downstreamPipelines) {
            if ((skipDownstreamTriggersPattern != null)
                && downstreamPipelineFullName.matches(skipDownstreamTriggersPattern)) {
                continue;
            }

            final WorkflowJob downstreamPipeline =
                Jenkins.getInstance().getItemByFullName(downstreamPipelineFullName, WorkflowJob.class);

            if (downstreamPipeline == null) {
                continue;
            }

            WorkflowRun build = downstreamPipeline.getLastCompletedBuild();

            if (build == null) {
                continue;
            }

            List<String> transitives = getDownstreamPipelines(build);
            if (transitives == null) {
                continue;
            }

            transitives.remove(downstreamPipelineFullName);
            transitives.removeAll(downRemovedTriggers);

            for (String transitive : transitives) {
                if (downTriggers.remove(transitive)) {
                    downRemovedTriggers.add(transitive);
                }
            }

            reduceDownstreamTriggers(
                upstreamPipeline, skipDownstreamTriggersPattern, transitives, downTriggers, downRemovedTriggers);
        }
        return downTriggers;
    }
    //

    @Override
    public void onCompleted(WorkflowRun upstreamBuild, @Nonnull TaskListener listener) {
        LOGGER.log(Level.FINER, "onCompleted({0})", new Object[]{upstreamBuild});
        long startTimeInNanos = System.nanoTime();
        if(LOGGER.isLoggable(Level.FINER)) {
            listener.getLogger().println("[withMaven] pipelineGraphPublisher - triggerDownstreamPipelines");
        }

        if (!globalPipelineMavenConfig.getTriggerDownstreamBuildsResultsCriteria().contains(upstreamBuild.getResult())) {
            if (LOGGER.isLoggable(Level.FINER)) {
                listener.getLogger().println("[withMaven] Skip triggering downstream jobs for upstream build with ignored result status " + upstreamBuild + ": " + upstreamBuild.getResult());
            }
            return;
        }

        try {
            this.globalPipelineMavenConfig.getPipelineTriggerService().checkNoInfiniteLoopOfUpstreamCause(upstreamBuild);
        } catch (IllegalStateException e) {
            listener.getLogger().println("[withMaven] WARNING abort infinite build trigger loop. Please consider opening a Jira issue: " + e.getMessage());
            return;
        }

        WorkflowJob upstreamPipeline = upstreamBuild.getParent();

        String upstreamPipelineFullName = upstreamPipeline.getFullName();
        int upstreamBuildNumber = upstreamBuild.getNumber();
        Map<MavenArtifact, SortedSet<String>> downstreamPipelinesByArtifact = globalPipelineMavenConfig.getDao().listDownstreamJobsByArtifact(upstreamPipelineFullName, upstreamBuildNumber);

        Map<String, Set<MavenArtifact>> jobsToTrigger = new TreeMap<>();

        // build the list of pipelines to trigger
        for(Map.Entry<MavenArtifact, SortedSet<String>> entry: downstreamPipelinesByArtifact.entrySet()) {

            MavenArtifact mavenArtifact = entry.getKey();
            SortedSet<String> downstreamPipelines = entry.getValue();

            // HACK: p
            Set<String> downTriggers = new HashSet<>(downstreamPipelines);
            Set<String> downRemovedTriggers = new HashSet<>();
            String skipDownstreamTriggersPattern = null;
            PipelineGraphPublisherAction pipelineGraphPublisherAction =
                    upstreamBuild.getAction(PipelineGraphPublisherAction.class);
            if (pipelineGraphPublisherAction != null) {
                PipelineGraphPublisher pipelineGraphPublisher = pipelineGraphPublisherAction.getPipelineGraphPublisher();
                skipDownstreamTriggersPattern = pipelineGraphPublisher.getSkipDownstreamTriggersPattern();
                reduceDownstreamTriggers(
                        upstreamPipeline, skipDownstreamTriggersPattern,
                        new ArrayList<>(downstreamPipelines), downTriggers, downRemovedTriggers);
            }
            // HACK: skip all transitive
            for (String removed : downRemovedTriggers) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    listener.getLogger()
                        .println(
                            "[withMaven] Skip triggering transitive downstream pipeline "
                                + ModelHyperlinkNote.encodeTo(
                                    Jenkins.getInstance().getItemByFullName(removed, WorkflowJob.class))
                                + ".");
                }
                continue;
            }
            //

            downstreamPipelinesLoop:
            for (String downstreamPipelineFullName : downTriggers) {

                if (jobsToTrigger.containsKey(downstreamPipelineFullName)) {
                    // downstream pipeline has already been added to the list of pipelines to trigger,
                    // we have already verified that it's meeting requirements (not an infinite loop, authorized by security, not excessive triggering, buildable...)
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Skip eligibility check of pipeline " + downstreamPipelineFullName + " for artifact " + mavenArtifact.getShortDescription() + ", eligibility already confirmed");
                    }
                    Set<MavenArtifact> mavenArtifacts = jobsToTrigger.get(downstreamPipelineFullName);
                    if (mavenArtifacts == null) {
                        listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Invalid state, no artifacts found for pipeline '" + downstreamPipelineFullName + "' while evaluating " + mavenArtifact.getShortDescription());
                    } else {
                        mavenArtifacts.add(mavenArtifact);
                    }
                    continue;
                }

                if (Objects.equals(downstreamPipelineFullName, upstreamPipelineFullName)) {
                    // Don't trigger myself
                    continue;
                }

                final WorkflowJob downstreamPipeline = Jenkins.getInstance().getItemByFullName(downstreamPipelineFullName, WorkflowJob.class);
                if (downstreamPipeline == null || downstreamPipeline.getLastBuild() == null) {
                    LOGGER.log(Level.FINE, "Downstream pipeline {0} or downstream pipeline last build not found from upstream build {1}. Database synchronization issue or security restriction?",
                            new Object[]{downstreamPipelineFullName, upstreamBuild.getFullDisplayName(), Jenkins.getAuthentication()});
                    continue;
                }

                int downstreamBuildNumber = downstreamPipeline.getLastBuild().getNumber();

                List<MavenArtifact> downstreamPipelineGeneratedArtifacts = globalPipelineMavenConfig.getDao().getGeneratedArtifacts(downstreamPipelineFullName, downstreamBuildNumber);
                if (LOGGER.isLoggable(Level.FINEST)) {
                    listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Pipeline " + ModelHyperlinkNote.encodeTo(downstreamPipeline) + " evaluated for because it has a dependency on " + mavenArtifact + " generates " + downstreamPipelineGeneratedArtifacts);
                }

                for (MavenArtifact downstreamPipelineGeneratedArtifact : downstreamPipelineGeneratedArtifacts) {
                    if (Objects.equals(mavenArtifact.getGroupId(), downstreamPipelineGeneratedArtifact.getGroupId()) &&
                            Objects.equals(mavenArtifact.getArtifactId(), downstreamPipelineGeneratedArtifact.getArtifactId())) {
                        if (LOGGER.isLoggable(Level.FINE)) {
                            listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Skip triggering " + ModelHyperlinkNote.encodeTo(downstreamPipeline) + " for " + mavenArtifact + " because it generates artifact with same groupId:artifactId " + downstreamPipelineGeneratedArtifact);
                        }
                        continue downstreamPipelinesLoop;
                    }
                }

                Map<MavenArtifact, SortedSet<String>> downstreamDownstreamPipelinesByArtifact = globalPipelineMavenConfig.getDao().listDownstreamJobsByArtifact(downstreamPipelineFullName, downstreamBuildNumber);
                for (Map.Entry<MavenArtifact, SortedSet<String>> entry2 : downstreamDownstreamPipelinesByArtifact.entrySet()) {
                    SortedSet<String> downstreamDownstreamPipelines = entry2.getValue();
                    if (downstreamDownstreamPipelines.contains(upstreamPipelineFullName)) {
                        listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Infinite loop detected: skip triggering " + ModelHyperlinkNote.encodeTo(downstreamPipeline) + " " +
                                " (dependency: " + mavenArtifact.getShortDescription() + ") because it is itself triggering this pipeline " +
                                ModelHyperlinkNote.encodeTo(upstreamPipeline) + " (dependency: " + entry2.getKey().getShortDescription() + ")");
                        // prevent infinite loop
                        continue downstreamPipelinesLoop;
                    }
                }

                // HACK: add a way to exclude triggers by pattern
                if ((skipDownstreamTriggersPattern != null)
                        && downstreamPipelineFullName.matches(skipDownstreamTriggersPattern)) {
                    listener.getLogger()
                        .println(
                            "[withMaven] Skip triggering of downstream pipeline "
                                + ModelHyperlinkNote.encodeTo(downstreamPipeline)
                                + " because of pattern matching.");
                    continue;
                }
                //

                // Avoid excessive triggering
                // See #46313
                Map<String, Integer> transitiveUpstreamPipelines = globalPipelineMavenConfig.getDao().listTransitiveUpstreamJobs(downstreamPipelineFullName, downstreamBuildNumber);
                for (String transitiveUpstreamPipelineName : transitiveUpstreamPipelines.keySet()) {
                    // Skip if one of the downstream's upstream is already building or in queue
                    // Then it will get triggered anyway by that upstream, we don't need to trigger it again
                    WorkflowJob transitiveUpstreamPipeline = Jenkins.getInstance().getItemByFullName(transitiveUpstreamPipelineName, WorkflowJob.class);

                    if (transitiveUpstreamPipeline == null) {
                        // security: not allowed to view this transitive upstream pipeline, continue to loop
                        continue;
                    } else if (transitiveUpstreamPipeline.getFullName().equals(upstreamPipeline.getFullName())) {
                        // this upstream pipeline of  the current downstreamPipeline is the upstream pipeline itself, continue to loop
                        continue;
                    } else if (transitiveUpstreamPipeline.isBuilding()) {
                        listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Skip triggering " + ModelHyperlinkNote.encodeTo(downstreamPipeline) +
                                " because it has a dependency already building: " + ModelHyperlinkNote.encodeTo(transitiveUpstreamPipeline));
                        continue downstreamPipelinesLoop;
                    } else if (transitiveUpstreamPipeline.isInQueue()) {
                        listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Skip triggering " + ModelHyperlinkNote.encodeTo(downstreamPipeline) +
                                " because it has a dependency already building or in queue: " + ModelHyperlinkNote.encodeTo(transitiveUpstreamPipeline));
                        continue downstreamPipelinesLoop;
                    } else if (downstreamPipelines.contains(transitiveUpstreamPipelineName)) {
                         // Skip if this downstream pipeline will be triggered by another one of our downstream pipelines
                         // That's the case when one of the downstream's transitive upstream is our own downstream
                         listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Skip triggering " + ModelHyperlinkNote.encodeTo(downstreamPipeline) +
                                " because it has a dependency on a pipeline that will be triggered by this build: " + ModelHyperlinkNote.encodeTo(transitiveUpstreamPipeline));
                        continue downstreamPipelinesLoop;
                    }
                }

                if (!downstreamPipeline.isBuildable()) {
                    LOGGER.log(Level.FINE, "Skip triggering of non buildable (disabled: {0}, isHoldOffBuildUntilSave: {1}) downstream pipeline {2} from upstream build {3}",
                            new Object[]{downstreamPipeline.isDisabled(), downstreamPipeline.isHoldOffBuildUntilSave(), downstreamPipeline.getFullName(), upstreamBuild.getFullDisplayName()});
                    continue;
                }

                WorkflowJobDependencyTrigger downstreamPipelineTrigger = this.globalPipelineMavenConfig.getPipelineTriggerService().getWorkflowJobDependencyTrigger(downstreamPipeline);
                if (downstreamPipelineTrigger == null) {
                    LOGGER.log(Level.FINE, "Skip triggering of downstream pipeline {0} from upstream build {1}: dependency trigger not configured", new Object[]{downstreamPipeline.getFullName(), upstreamBuild.getFullDisplayName()});
                    continue;
                }

                boolean downstreamVisibleByUpstreamBuildAuth = this.globalPipelineMavenConfig.getPipelineTriggerService().isDownstreamVisibleByUpstreamBuildAuth(downstreamPipeline);
                boolean upstreamVisibleByDownstreamBuildAuth = this.globalPipelineMavenConfig.getPipelineTriggerService().isUpstreamBuildVisibleByDownstreamBuildAuth(upstreamPipeline, downstreamPipeline);

                if (LOGGER.isLoggable(Level.FINER)) {
                    LOGGER.log(Level.FINER,
                            "upstreamPipeline (" + upstreamPipelineFullName + ", visibleByDownstreamBuildAuth: " + upstreamVisibleByDownstreamBuildAuth + "), " +
                                    " downstreamPipeline (" + downstreamPipeline.getFullName() + ", visibleByUpstreamBuildAuth: " + downstreamVisibleByUpstreamBuildAuth + "), " +
                                    "upstreamBuildAuth: " + Jenkins.getAuthentication());
                }
                if (downstreamVisibleByUpstreamBuildAuth && upstreamVisibleByDownstreamBuildAuth) {
                    Set<MavenArtifact> mavenArtifactsCausingTheTrigger = jobsToTrigger.computeIfAbsent(downstreamPipelineFullName, k -> new TreeSet<>());
                    if(mavenArtifactsCausingTheTrigger.contains(mavenArtifact)) {
                        // TODO display warning
                    } else {
                        mavenArtifactsCausingTheTrigger.add(mavenArtifact);
                    }
                } else {
                    LOGGER.log(Level.FINE, "Skip triggering of {0} by {1}: downstreamVisibleByUpstreamBuildAuth: {2}, upstreamVisibleByDownstreamBuildAuth: {3}",
                            new Object[]{downstreamPipeline.getFullName(), upstreamBuild.getFullDisplayName(), downstreamVisibleByUpstreamBuildAuth, upstreamVisibleByDownstreamBuildAuth});
                }
            }
        }

        // trigger the pipelines
        for (Map.Entry<String, Set<MavenArtifact>> entry: jobsToTrigger.entrySet()) {
            String downstreamJobFullName = entry.getKey();
            Job downstreamJob = Jenkins.getInstance().getItemByFullName(downstreamJobFullName, Job.class);
            if (downstreamJob == null) {
                listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Illegal state: " + downstreamJobFullName + " not resolved");
                continue;
            }
            Set<MavenArtifact> mavenArtifacts = entry.getValue();

            // See jenkins.triggers.ReverseBuildTrigger.RunListenerImpl.onCompleted(Run, TaskListener)
            MavenDependencyUpstreamCause cause = new MavenDependencyUpstreamCause(upstreamBuild, mavenArtifacts);

            Run downstreamJobLastBuild = downstreamJob.getLastBuild();
            if (downstreamJobLastBuild == null) {
                // should never happen, we need at least one build to know the dependencies
            } else {
                List<MavenArtifact> matchingMavenDependencies = MavenDependencyCauseHelper.isSameCause(cause, downstreamJobLastBuild.getCauses());
                if (matchingMavenDependencies.size() > 0) {
                    downstreamJobLastBuild.addAction(new CauseAction(cause));
                    listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Skip triggering downstream pipeline " + ModelHyperlinkNote.encodeTo(downstreamJob) + " as it was already triggered for Maven dependencies: " +
                                    matchingMavenDependencies.stream().map(mavenDependency -> mavenDependency == null ? null : mavenDependency.getShortDescription()).collect(Collectors.joining(", ")));
                    try {
                        downstreamJobLastBuild.save();
                    } catch (IOException e) {
                        listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Failure to update build " + downstreamJobLastBuild.getFullDisplayName() + ": " + e.toString());
                    }
                    continue;
                } else {
                    // trigger build
                }
            }

            Queue.Item queuedItem = ParameterizedJobMixIn.scheduleBuild2(downstreamJob, -1, new CauseAction(cause));

            String dependenciesMessage = cause.getMavenArtifactsDescription();
            if (queuedItem == null) {
                listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Skip triggering downstream pipeline " + ModelHyperlinkNote.encodeTo(downstreamJob) + " due to dependencies on " +
                        dependenciesMessage + ", invocation rejected.");
            } else {
                listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - Triggering downstream pipeline " + ModelHyperlinkNote.encodeTo(downstreamJob) + "#" + downstreamJob.getNextBuildNumber() + " due to dependency on " +
                        dependenciesMessage + " ...");
            }

        }
        long durationInMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - startTimeInNanos, TimeUnit.NANOSECONDS);
        if (durationInMillis > TimeUnit.MILLISECONDS.convert(5, TimeUnit.SECONDS) || LOGGER.isLoggable(Level.FINE)) {
            listener.getLogger().println("[withMaven] downstreamPipelineTriggerRunListener - completed in " + durationInMillis + " ms");
        }
    }


}
