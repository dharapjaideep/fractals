# Personal Workflow Preferences
Personal conventions for working with Claude Code effectively.

## Long response convention
When a response (plan, diff, analysis, explanation) is longer than 10 lines, ALWAYS:
1. Print the response to the terminal as normal
2. Also write it to `/tmp/claude_response.txt` (overwrite each time — latest response only)

This makes long responses easy to review in an editor rather than scrolling the terminal.
The `/tmp/` folder is already in `.gitignore`.

Example:
```bash
cat > /tmp/claude_response.txt << 'EOF'
Your response here
EOF
```

## Branching strategy

### Branches
- **devel**: active development. May be unstable.
- **staging**: known-good, tested state. Merge from devel when quality is validated and tests
  pass. Used for user testing and demos.
- **main**: public-facing, portfolio-ready. Merge from staging after tester validation.

### When to merge devel → staging
- All tests pass
- Manual quality test confirms recommendation quality is good
- No TEMPORARY code committed
- You are happy showing this to someone else

### End of session checklist
At the end of every development session, Claude should ask:

> "Before we wrap up — do you want to merge devel into staging? Checklist:
> ✅ All tests passing?
> ✅ Recommendation quality feels good?
> ✅ No TEMPORARY code committed?
> ✅ Happy to show this to someone else?
> If yes to all → merge now while the session context is fresh."

The developer should make it a habit to signal the end of a session explicitly so this
checklist is not skipped.

### Convention
At the end of each session where quality feels good:
```powershell
git checkout staging
git merge devel
git push origin staging
```

Do not merge experimental features (RAG, new architecture) to staging until they are
validated and ready to replace the previous approach.

## Post-merge verification (ALWAYS do after any merge)

After any merge (devel → staging or staging → main):

1. Confirm the merged branch is up to date:
   ```powershell
   git log staging..devel --oneline
   ```
   (should return empty — means staging has everything devel has)

2. Switch back to devel immediately:
   ```powershell
   git checkout devel
   ```

3. Confirm current branch is devel:
   ```powershell
   git branch --show-current
   ```
   (must show "devel" before continuing any work)

This prevents accidentally developing on staging or main after a merge.