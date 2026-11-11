# u-proxy92 debug log

1. Symptom: run 42908324725 (tag push v2.63.0): bot-gate=skipped, decide=success, build=success, release=SKIPPED. No v2.63.0 Release exists; v2.62.0 has one.
2. Code: build.yml at v2.62.0 had build+release only (release: needs build, if ref_type==tag). bot-gate/decide added later; build got needs [bot-gate, decide] + if always()...; release unchanged.
3. Cause: docs jobs.<job_id>.needs — a skip applies to the whole downstream chain unless the job's own if uses always(). bot-gate skips on pushes -> release skipped despite build success.
4. Fail-before: tests/test_workflow.py 2 tests fail on old file (always() not found; skippable upstream [bot-gate, build...]).
5. Fix: release if -> always() && ref_type==tag && needs.build.result==success (minimal; behavior changes only for tag pushes with green build).
6. Verify: test_workflow.py 2/2 pass; actionlint exit 0; prettier --check clean.
