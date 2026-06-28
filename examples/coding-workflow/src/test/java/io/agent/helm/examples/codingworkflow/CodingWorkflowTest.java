package io.agent.helm.examples.codingworkflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.runtime.FakeProvider;
import io.agent.helm.runtime.InMemoryRuntimeStore;
import io.agent.helm.runtime.WorkflowInvokeRequest;
import io.agent.helm.runtime.WorkflowRunHandle;
import io.agent.helm.runtime.WorkflowRuntime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CodingWorkflowTest {
    @Test
    void runsCodingWorkflowWithFakeProviderAndGithubTools() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(response("## Requirement\nFix the timeout.\n\n## Proposed change\nAdd retry."));
        provider.enqueue(response("## Decision\nAPPROVED"));
        provider.enqueue(response("## Changes made\nImplemented retry."));
        provider.enqueue(response("APPROVED"));
        FakeGitHubProjectTools github = new FakeGitHubProjectTools();
        WorkflowRuntime runtime = WorkflowRuntime.builder()
                .workflow(new CodingWorkflow(github))
                .provider(provider)
                .store(new InMemoryRuntimeStore())
                .build();

        WorkflowRunHandle<CodingWorkflowOutput> handle = runtime.invoke(new WorkflowInvokeRequest<>(
                "coding-workflow", new CodingWorkflowInput("acme", "billing-service", 42, "main", true)));

        assertThat(handle.result().issueNumber()).isEqualTo(42);
        assertThat(handle.result().branchName()).isEqualTo("helm/issue-42-fix-billing-timeout");
        assertThat(handle.result().pullRequestUrl()).isEqualTo("https://github.com/acme/billing-service/pull/108");
        assertThat(handle.result().designSummary()).isEqualTo("## Requirement\nFix the timeout.");
        assertThat(handle.result().verificationSummary()).isEqualTo("Unit tests passed.");
        assertThat(handle.result().reviewSummary()).isEqualTo("APPROVED");
        assertThat(github.createdBranches()).containsExactly("helm/issue-42-fix-billing-timeout");
        assertThat(github.pullRequests()).hasSize(1);
        assertThat(github.pullRequests().getFirst().draft()).isTrue();
    }

    private static ModelStreamEvent[] response(String text) {
        return new ModelStreamEvent[] {
            new ModelStreamEvent.ContentDelta(text), new ModelStreamEvent.Completed(new TokenUsage(1, 1))
        };
    }

    private static final class FakeGitHubProjectTools implements GitHubProjectTools {
        private final List<String> createdBranches = new ArrayList<>();
        private final List<PullRequestRequest> pullRequests = new ArrayList<>();

        @Override
        public IssueContext readIssue(String owner, String repository, int issueNumber) {
            return new IssueContext(
                    "Fix billing timeout", "Billing requests time out.", List.of("Please add retry."), List.of());
        }

        @Override
        public String createBranch(String owner, String repository, String baseBranch, String branchName) {
            createdBranches.add(branchName);
            return branchName;
        }

        @Override
        public VerificationResult runVerification(String owner, String repository, String branchName) {
            return new VerificationResult(true, "mvn test", "Unit tests passed.");
        }

        @Override
        public String readDiff(String owner, String repository, String baseBranch, String branchName) {
            return "diff --git a/BillingService.java b/BillingService.java";
        }

        @Override
        public PullRequest createPullRequest(PullRequestRequest request) {
            pullRequests.add(request);
            return new PullRequest("https://github.com/acme/billing-service/pull/108", 108);
        }

        private List<String> createdBranches() {
            return createdBranches;
        }

        private List<PullRequestRequest> pullRequests() {
            return pullRequests;
        }
    }
}
