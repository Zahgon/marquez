/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.filter;

import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import marquez.common.models.JobId;
import marquez.http.Answer;
import marquez.http.Percent;
import marquez.http.RequestContext;
import marquez.http.RequestFilters;
import marquez.service.JobService;
import marquez.service.models.Job;

/**
 * Filters requests that reference a job that has been symlinked to another job. This filter
 * redirects such requests to the URL with the symlink target's name using a 301 status code.
 *
 * <p>The address it redirects to is built from the path the request was matched to, with the two
 * values that name the job replaced by the ones the symlink points at - which is what it did
 * before, when it asked the runtime for that path.
 */
@Slf4j
public class JobRedirectFilter implements RequestFilters.BeforeResource {

  public static final String JOB_PATH_PARAM = "job";
  public static final String NAMESPACE_PATH_PARAM = "namespace";
  private final JobService jobService;

  public JobRedirectFilter(JobService jobService) {
    this.jobService = jobService;
  }

  @Override
  public void before(RequestContext request) {
    final Map<String, String> pathParameters = request.pathParameters();
    if (!pathParameters.containsKey(NAMESPACE_PATH_PARAM)
        || !pathParameters.containsKey(JOB_PATH_PARAM)) {
      return;
    }
    final String namespace = pathParameters.get(NAMESPACE_PATH_PARAM);
    final String job = pathParameters.get(JOB_PATH_PARAM);
    if (namespace == null || job == null) {
      return;
    }
    final Optional<Job> found = jobService.findJobByName(namespace, job);
    found.ifPresent(
        symlinked -> {
          if (!symlinked.getName().getValue().equals(job)) {
            log.info(
                "Job {}.{} has been redirected to {}.{}",
                namespace,
                job,
                symlinked.getNamespace().getValue(),
                symlinked.getName().getValue());
            final String location = locationFor(request, symlinked.getId());
            log.debug("Redirecting to url {}", location);
            request.abortWith(new Answer(301).header("Location", location));
          }
        });
  }

  /**
   * Builds the address of the same request against the job the symlink points at, from the path the
   * request was matched to.
   */
  private String locationFor(RequestContext request, JobId jobId) {
    final StringBuilder address = new StringBuilder(request.baseUri());
    if (address.length() > 0 && address.charAt(address.length() - 1) == '/') {
      address.setLength(address.length() - 1);
    }
    for (final String segment : request.route().template().declared().split("/", -1)) {
      if (segment.isEmpty()) {
        continue;
      }
      address.append('/').append(Percent.encodeSegment(valueOf(request, segment, jobId)));
    }
    return address.toString();
  }

  private String valueOf(RequestContext request, String segment, JobId jobId) {
    if (!segment.startsWith("{") || !segment.endsWith("}")) {
      return segment;
    }
    String name = segment.substring(1, segment.length() - 1);
    final int colon = name.indexOf(':');
    if (colon >= 0) {
      name = name.substring(0, colon);
    }
    name = name.trim();
    if (JOB_PATH_PARAM.equals(name)) {
      return jobId.getName().getValue();
    }
    if (NAMESPACE_PATH_PARAM.equals(name)) {
      return jobId.getNamespace().getValue();
    }
    final String value = request.pathParameters().get(name);
    return value == null ? "" : value;
  }
}
