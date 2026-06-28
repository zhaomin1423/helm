package io.github.zhaomin.helm.examples.codingworkflow;

public record CodingWorkflowOutput(
    int issueNumber,
    String branchName,
    String pullRequestUrl,
    String designSummary,
    String verificationSummary,
    String reviewSummary
) {}
