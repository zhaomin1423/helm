package io.agent.helm.examples.codingworkflow;

import java.util.List;

public interface GitHubProjectTools {
    IssueContext readIssue(String owner, String repository, int issueNumber);

    String createBranch(String owner, String repository, String baseBranch, String branchName);

    VerificationResult runVerification(String owner, String repository, String branchName);

    String readDiff(String owner, String repository, String baseBranch, String branchName);

    PullRequest createPullRequest(PullRequestRequest request);

    record IssueContext(String title, String body, List<String> comments, List<String> linkedFiles) {}

    record VerificationResult(boolean passed, String command, String output) {}

    record PullRequestRequest(
            String owner,
            String repository,
            String baseBranch,
            String branchName,
            String title,
            String body,
            boolean draft) {}

    record PullRequest(String url, int number) {}
}
