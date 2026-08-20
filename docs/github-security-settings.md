# GitHub security settings

P3.14 keeps repository controls in versioned files and applies GitHub-hosted
controls through `scripts/configure-github-security.sh`. The script requires an
authenticated GitHub CLI session whose token has repository Administration
write access. It never reads or prints the token.

The apply mode deliberately refuses to run until `.github/CODEOWNERS` and all
five CI jobs are present on the target branch. This prevents enabling required
checks that no commit can satisfy.

After the P3.14 repository changes are merged and the workflow has completed at
least once, run:

```bash
gh auth login
./scripts/configure-github-security.sh --apply alad1nks/jaiqal main
./scripts/configure-github-security.sh alad1nks/jaiqal main
```

Before relying on the required `dependency-review` check, enable **Dependency
Graph** in **Settings → Code security**. GitHub's dependency review action is not
supported while the repository dependency graph is disabled.

The resulting protection requires an up-to-date pull request, one approving
review, code-owner review, dismissal of stale approvals, approval after the last
push, resolved conversations, and successful `verification`, `sast`,
`supply-chain`, and `dependency-review` checks. The rule applies to repository
administrators, forbids force pushes and branch deletion, and allows no review
bypass identities. Repository secret scanning and push protection are enabled by
the same command.

If a full-history scan finds a credential, revoke or rotate it before rewriting
history. Removing it only from the current branch does not invalidate copies in
old commits, forks, clones, caches, or logs.
