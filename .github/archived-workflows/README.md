# Archived workflows

These were one-off incident-response/diagnostic workflows, moved out of `.github/workflows/`
(GitHub Actions only scans that directory directly, not subdirectories, so these no longer
run or appear in the Actions tab — content and history are preserved, not deleted).

Archived 2026-09-22 after a full 76-workflow audit across all 5 repos:

| Workflow | Last run before archiving | Why |
|---|---|---|
| `promote-vps-image.yml` | 2026-07-16 | One-off recovery tool hardcoded to republish the exact pre-fix VPS image (`node-scala-testnet-be2dcfc0`) from the image-drift incident that fed into the height-3325 committed-generators-statehash bug. That bug is root-caused and fixed (`ade354adcb`), and the testnet has since been relaunched (2026-08-30) on the fixed, digest-pinned image. The drift condition this workflow exists to patch around no longer applies. |

If any of these are needed again, move the file back to `.github/workflows/` — nothing else
to restore.
