package io.agent.helm.examples.codingworkflow;

import io.agent.helm.core.agent.AgentSessionApi;
import io.agent.helm.core.type.TypeDescriptor;
import io.agent.helm.core.workflow.WorkflowConfig;
import io.agent.helm.core.workflow.WorkflowContext;
import io.agent.helm.core.workflow.WorkflowDefinition;

public final class CodingWorkflow implements WorkflowDefinition<CodingWorkflowInput, CodingWorkflowOutput> {
    private static final int MAX_FIX_ROUNDS = 2;

    private final GitHubProjectTools github;

    public CodingWorkflow(GitHubProjectTools github) {
        this.github = github;
    }

    @Override
    public String name() {
        return "coding-workflow";
    }

    @Override
    public WorkflowConfig config() {
        return WorkflowConfig.of(new CodingAgent());
    }

    @Override
    public TypeDescriptor<CodingWorkflowInput> inputType() {
        return TypeDescriptor.of(CodingWorkflowInput.class);
    }

    @Override
    public TypeDescriptor<CodingWorkflowOutput> outputType() {
        return TypeDescriptor.of(CodingWorkflowOutput.class);
    }

    @Override
    public CodingWorkflowOutput run(WorkflowContext<CodingWorkflowInput> context) throws Exception {
        CodingWorkflowInput input = context.input();
        AgentSessionApi session = context.harness().session("issue-" + input.issueNumber());

        GitHubProjectTools.IssueContext issue =
                github.readIssue(input.owner(), input.repository(), input.issueNumber());
        String branchName = branchName(input.issueNumber(), issue.title());

        String design = prompt(
                session,
                "skills/design/SKILL.md",
                """
            Read this GitHub issue and produce a concrete implementation design.

            Title:
            %s

            Body:
            %s

            Comments:
            %s
            """
                        .formatted(issue.title(), issue.body(), String.join("\n\n", issue.comments())));

        String designReview = prompt(
                session,
                "skills/design-review/SKILL.md",
                """
            Review this implementation design before any code is changed.

            Issue title:
            %s

            Design:
            %s
            """
                        .formatted(issue.title(), design));

        github.createBranch(input.owner(), input.repository(), input.baseBranch(), branchName);

        prompt(
                session,
                "skills/implementation/SKILL.md",
                """
            Implement the approved design on branch %s.

            Design:
            %s

            Design review:
            %s
            """
                        .formatted(branchName, design, designReview));

        GitHubProjectTools.VerificationResult verification = verifyOrThrow(input, branchName);
        String review = reviewAndFixUntilClean(session, input, branchName, verification);

        GitHubProjectTools.PullRequest pr = github.createPullRequest(new GitHubProjectTools.PullRequestRequest(
                input.owner(),
                input.repository(),
                input.baseBranch(),
                branchName,
                "Fix issue #" + input.issueNumber() + ": " + issue.title(),
                pullRequestBody(issue, design, designReview, verification, review),
                input.openDraftPullRequest()));

        return new CodingWorkflowOutput(
                input.issueNumber(),
                branchName,
                pr.url(),
                firstParagraph(design),
                firstParagraph(verification.output()),
                firstParagraph(review));
    }

    private GitHubProjectTools.VerificationResult verifyOrThrow(CodingWorkflowInput input, String branchName) {
        GitHubProjectTools.VerificationResult verification =
                github.runVerification(input.owner(), input.repository(), branchName);
        if (!verification.passed()) {
            throw new IllegalStateException("Verification failed on branch %s with command `%s`."
                    .formatted(branchName, verification.command()));
        }
        return verification;
    }

    private String reviewAndFixUntilClean(
            AgentSessionApi session,
            CodingWorkflowInput input,
            String branchName,
            GitHubProjectTools.VerificationResult verification)
            throws Exception {
        String review = "";
        for (int round = 1; round <= MAX_FIX_ROUNDS; round++) {
            String diff = github.readDiff(input.owner(), input.repository(), input.baseBranch(), branchName);
            review = prompt(
                    session,
                    "skills/code-review/SKILL.md",
                    """
                Review this diff. If there are blocking correctness, safety, or durability issues,
                describe them clearly. If there are no blockers, say "APPROVED".

                Verification:
                %s

                Diff:
                %s
                """
                            .formatted(verification.output(), diff));

            if (review.trim().equals("APPROVED")) {
                return review;
            }

            prompt(
                    session,
                    "skills/implementation/SKILL.md",
                    """
                Fix the blocking review findings below. Do not expand scope.

                Review findings:
                %s
                """
                            .formatted(review));

            verification = verifyOrThrow(input, branchName);
        }

        throw new IllegalStateException("Code review still has blocking findings after fix rounds.");
    }

    private static String prompt(AgentSessionApi session, String skillPath, String task) {
        return session.prompt("Use " + skillPath + " for this task.\n\n" + task).text();
    }

    private static String branchName(int issueNumber, String title) {
        String slug = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.length() > 48) {
            slug = slug.substring(0, 48).replaceAll("-$", "");
        }
        if (slug.isBlank()) {
            slug = "work";
        }
        return "helm/issue-" + issueNumber + "-" + slug;
    }

    private static String pullRequestBody(
            GitHubProjectTools.IssueContext issue,
            String design,
            String designReview,
            GitHubProjectTools.VerificationResult verification,
            String review) {
        return """
            ## Source issue

            %s

            ## Design

            %s

            ## Design review

            %s

            ## Verification

            Command: `%s`

            %s

            ## Code review

            %s
            """
                .formatted(issue.title(), design, designReview, verification.command(), verification.output(), review);
    }

    private static String firstParagraph(String value) {
        String trimmed = value.trim();
        int paragraphEnd = trimmed.indexOf("\n\n");
        return paragraphEnd >= 0 ? trimmed.substring(0, paragraphEnd) : trimmed;
    }
}
