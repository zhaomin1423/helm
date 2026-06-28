package io.agent.helm.examples.codingworkflow;

public record CodingWorkflowInput(
        String owner, String repository, int issueNumber, String baseBranch, boolean openDraftPullRequest) {}
